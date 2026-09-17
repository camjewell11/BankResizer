/*
 * Copyright (c) 2026, camjewell11
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED. SEE LICENSE FOR DETAIL.
 */
package com.bankresizer;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Bank Resizer",
	description = "Widen the bank interface to show more columns of items",
	tags = {"bank", "interface", "ui", "resize", "columns", "width"},
	internalName = "bank-resizer"
)
public class BankResizerPlugin extends Plugin
{
	/**
	 * Dynamic children of the item container below this index are bank slots.
	 * At and above it they are tab separators. The number comes from
	 * [proc,bankmain_build], which starts its separator sweep at slot 816.
	 */
	private static final int SEPARATOR_INDEX_BASE = 816;

	/** Pixels kept clear between the widened bank and the edge of the viewport. */
	private static final int EDGE_MARGIN = 4;

	/**
	 * Chrome width assumed when the bank frame cannot be measured. Deliberately
	 * generous: overestimating costs a column, underestimating pushes the bank
	 * past the edge of the client.
	 */
	private static final int FALLBACK_CHROME_WIDTH = 60;

	/** Above this, a measured chrome width is treated as a bad reading. */
	private static final int MAX_PLAUSIBLE_CHROME_WIDTH = 200;

	/**
	 * Widgets inside the bank that must be widened by hand.
	 *
	 * Deliberately short. Most of the bank chrome is sized in {@code MINUS} mode,
	 * where the stored value is an inset from the parent rather than a width, so
	 * those widgets follow the window for free. Widening them by hand increases
	 * their inset and makes them shrink instead: FRAME, ITEMS_CONTAINER and
	 * BOTTOM all behaved that way before this list was cut down. SCROLLBAR is
	 * right anchored and moves on its own.
	 *
	 * The bank window and its fixed-width ancestors are not listed here. They are
	 * found by walking the widget tree, see {@link BankRoom}.
	 *
	 * TABS re-centres its own icons from its width, so widening it is enough to
	 * reflow the tabs.
	 */
	private static final int[] WIDTH_TRACKING = {
		InterfaceID.Bankmain.TABS,
		// The title bar is 476 wide in absolute mode, so it would sit short of the
		// right edge of a widened window.
		InterfaceID.Bankmain.TITLE,
	};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private BankResizerConfig config;

	/**
	 * Untouched width of every widget this plugin has widened, keyed by component
	 * id. Widths are always assigned as original plus delta rather than added to,
	 * so that repeated layout passes are idempotent. Cleared whenever the bank
	 * interface unloads, because the widget tree is rebuilt from scratch.
	 */
	private final Map<Integer, WidgetSize> originalWidths = new HashMap<>();

	/**
	 * Ancestors this plugin has resized, held directly rather than looked up.
	 *
	 * The outer slot belongs to the layout interface, not the bank, so it outlives
	 * the bank closing while the bank's own widgets are rebuilt. Without putting it
	 * back explicitly its widened size gets recaptured as the original next time
	 * the bank opens, and it creeps wider on every open.
	 */
	private final List<Widget> resizedAncestors = new ArrayList<>();

	/**
	 * A widget's width as it was before this plugin touched it.
	 *
	 * Records the rendered width as well as the stored one because they differ for
	 * anything not sized in absolute mode, where the stored value is an inset. The
	 * rendered width is what a delta has to be added to; the stored width and mode
	 * are what has to go back on restore.
	 */
	private static final class WidgetSize
	{
		private final int originalWidth;

		private final int widthMode;

		private final int renderedWidth;

		private final int originalHeight;

		private final int heightMode;

		private final int renderedHeight;

		private final int originalX;

		private WidgetSize(Widget widget)
		{
			this.originalX = widget.getOriginalX();
			this.originalWidth = widget.getOriginalWidth();
			this.widthMode = widget.getWidthMode();
			this.renderedWidth = widget.getWidth();
			this.originalHeight = widget.getOriginalHeight();
			this.heightMode = widget.getHeightMode();
			this.renderedHeight = widget.getHeight();
		}
	}

	/**
	 * Whether this plugin currently has the bank in a non-vanilla layout. Lets the
	 * vanilla column count be a genuine no-op while still undoing our own work if
	 * the user turns the column count back down.
	 */
	private boolean modified;

	/** Whether the geometry dump has already run for the current interface load. */
	private boolean loggedGeometry;

	/** Column count and canvas width of the last layout actually applied. */
	private int appliedColumns = -1;

	private int appliedCanvasWidth = -1;

	@Provides
	BankResizerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankResizerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		clientThread.invokeLater(this::applyLayout);
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientThread.invokeLater(() ->
		{
			restoreLayout();
			restoreAncestors();
			resetState();
		});
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		// bankmain_build calls bankmain_finishbuilding as its final statement, so
		// by the time this fires the vanilla layout and scroll size are settled.
		if (event.getScriptId() == ScriptID.BANKMAIN_BUILD
			|| event.getScriptId() == ScriptID.BANKMAIN_SIZE_CHECK)
		{
			applyLayout();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!BankResizerConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		clientThread.invokeLater(this::applyLayout);
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN && event.isUnload())
		{
			restoreAncestors();
			resetState();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			resetState();
		}
	}

	/**
	 * Widens the bank chrome and lays the item grid out again at the configured
	 * column count. Safe to call when the bank is closed, in which case it does
	 * nothing.
	 *
	 * At the vanilla column count this touches no widget at all. The game has
	 * already drawn that layout correctly, so the plugin stays invisible until
	 * the user actually asks for more columns.
	 */
	private void applyLayout()
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null || items.isHidden())
		{
			return;
		}

		int columns = resolveColumns();

		// Vanilla width and vanilla height together mean there is nothing to do.
		// A row count on its own still has to be applied, so it cannot short
		// circuit here just because the width is unchanged.
		if (columns == BankLayout.VANILLA_COLUMNS)
		{
			// Only undo our own work if there is any, then leave the bank alone.
			if (modified)
			{
				restoreLayout();
				modified = false;
			}

			return;
		}

		int targetWidth = BankLayout.containerWidthFor(columns);
		int canvasWidth = client.getCanvasWidth();

		if (isUpToDate(items, columns, canvasWidth, targetWidth))
		{
			return;
		}

		int delta = targetWidth - BankLayout.VANILLA_CONTAINER_WIDTH;

		log.debug("Laying out bank at {} columns, container width {} (delta {})",
			columns, targetWidth, delta);

		boolean dump = log.isDebugEnabled() && !loggedGeometry;
		if (dump)
		{
			logGeometry("before");
		}

		resizeChrome(delta);
		shiftBottomRow(delta);
		setWidth(items, targetWidth);
		layoutItems(items, columns, targetWidth);

		if (dump)
		{
			logGeometry("after");
			loggedGeometry = true;
		}

		modified = true;
		appliedColumns = columns;
		appliedCanvasWidth = canvasWidth;
	}

	/**
	 * Whether the bank already carries the layout we would apply, in which case
	 * there is nothing to do.
	 *
	 * This is what keeps the plugin off the hot path. The layout hooks fire on
	 * every client tick while the bank is open, and without this guard the plugin
	 * relaid 816 widgets and ran a script fifty times a second.
	 *
	 * The item container width is the reliable signal. [proc,bankmain_build]
	 * assigns it an absolute 460 on every rebuild, so seeing our own target width
	 * there proves no rebuild has happened since we last ran.
	 */
	private boolean isUpToDate(Widget items, int columns, int canvasWidth, int targetWidth)
	{
		return modified
			&& columns == appliedColumns
			&& canvasWidth == appliedCanvasWidth
			&& items.getOriginalWidth() == targetWidth;
	}

	/** Restores every widened widget and puts the grid back to vanilla columns. */
	private void restoreLayout()
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null || items.isHidden())
		{
			return;
		}

		resizeChrome(0);
		shiftBottomRow(0);
		setWidth(items, BankLayout.VANILLA_CONTAINER_WIDTH);
		layoutItems(items, BankLayout.VANILLA_COLUMNS, BankLayout.VANILLA_CONTAINER_WIDTH);
	}

	/**
	 * Column count to use, honouring the configured value but never letting the
	 * bank grow past the edge of the viewport.
	 */
	private int resolveColumns()
	{
		int limit = BankLayout.maxColumnsFor(availableContainerWidth());
		int wanted = config.fitToWidth() ? limit : config.columns();

		return Math.max(BankLayout.VANILLA_COLUMNS, Math.min(wanted, limit));
	}

	/**
	 * How wide the item container is allowed to become without any part of the
	 * bank leaving the visible client.
	 *
	 * The bank window is centre anchored, so it grows equally in both directions
	 * and its centre stays put. That makes the binding constraint the distance
	 * from that centre to the nearer edge of the canvas, not the canvas width.
	 * Measured on a live client the centre sits at x=362 on a 940 wide canvas, so
	 * the window can reach 724 wide before its left edge passes zero, well short
	 * of the 940 a plain canvas-width bound would have allowed.
	 */
	private int availableContainerWidth()
	{
		int canvasWidth = client.getCanvasWidth();
		Widget root = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (root == null || canvasWidth <= 0)
		{
			return BankLayout.VANILLA_CONTAINER_WIDTH;
		}

		// The play area, which does not change as the bank grows. Deliberately not
		// derived from the bank's own width or position: those move when we widen
		// it, which fed back into the column count and left it oscillating between
		// nine and ten columns on alternate passes.
		//
		// No separate canvas check is needed. The play area sits inside the canvas
		// already, so a window that fits inside it cannot leave the screen.
		int maxWindow = BankRoom.measure(root, canvasWidth).getLimit() - 2 * EDGE_MARGIN;

		return maxWindow - measuredChrome();
	}

	/**
	 * Width of the bank window that is not item grid: borders, and the inset the
	 * grid sits at. Measured on a live client as 488 minus 460, so 28.
	 *
	 * Read from UNIVERSE, which is the bank window and is sized in ABSOLUTE mode.
	 * FRAME looks like the natural choice but is sized in MINUS mode with a
	 * stored 0, which yields a meaningless negative number.
	 */
	private int measuredChrome()
	{
		Widget root = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (root == null || root.getWidthMode() != WidgetSizeMode.ABSOLUTE)
		{
			return FALLBACK_CHROME_WIDTH;
		}

		int chrome = originalWidthOf(root) - BankLayout.VANILLA_CONTAINER_WIDTH;
		if (chrome < 0 || chrome > MAX_PLAUSIBLE_CHROME_WIDTH)
		{
			return FALLBACK_CHROME_WIDTH;
		}

		return chrome;
	}

	/**
	 * Dumps the geometry this plugin depends on. Debug only, and only while the
	 * widget set is still being confirmed against a running client.
	 */
	private void logGeometry(String phase)
	{
		log.debug("--- bank geometry [{}] canvas {}x{}",
			phase, client.getCanvasWidth(), client.getCanvasHeight());
		logParentChain();
		logBankChildren();
		logWidget("UNIVERSE", InterfaceID.Bankmain.UNIVERSE);
		logWidget("FRAME", InterfaceID.Bankmain.FRAME);
		logWidget("ITEMS_CONTAINER", InterfaceID.Bankmain.ITEMS_CONTAINER);
		logWidget("ITEMS", InterfaceID.Bankmain.ITEMS);
		logWidget("TABS", InterfaceID.Bankmain.TABS);
		logWidget("BOTTOM", InterfaceID.Bankmain.BOTTOM);
		logWidget("SCROLLBAR", InterfaceID.Bankmain.SCROLLBAR);
	}

	/**
	 * Walks from the bank window up to the root of the interface tree.
	 *
	 * This is the measurement that decides whether widening the bank window can
	 * work at all. If an ancestor is narrower than the width we want and clips its
	 * children, growing the window only pushes content out of view instead of
	 * revealing more of it.
	 */
	private void logParentChain()
	{
		Widget widget = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (widget == null)
		{
			log.debug("  parent chain: UNIVERSE is null");
			return;
		}

		int depth = 0;
		for (Widget node = widget; node != null && depth < 12; node = node.getParent(), depth++)
		{
			log.debug("  chain[{}]: id={} group={} child={} origW={} w={} h={} x={} canvasX={} wMode={} xMode={}",
				depth,
				node.getId(),
				node.getId() >>> 16,
				node.getId() & 0xFFFF,
				node.getOriginalWidth(),
				node.getWidth(),
				node.getHeight(),
				node.getRelativeX(),
				node.getCanvasLocation() == null ? -1 : node.getCanvasLocation().getX(),
				node.getWidthMode(),
				node.getXPositionMode());
		}
	}

	/**
	 * Dumps the bank's own buttons and panels.
	 *
	 * The chrome is what decides whether a widened bank is usable. A child pinned
	 * to the left edge keeps its position when the window grows, which is fine for
	 * something on the left and wrong for anything that belongs near the right,
	 * so this prints each one's position mode alongside its bounds.
	 */
	private void logBankChildren()
	{
		Widget window = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (window == null)
		{
			return;
		}

		logChildrenOf("window", window);

		Widget bottom = client.getWidget(InterfaceID.Bankmain.BOTTOM);
		if (bottom != null)
		{
			logChildrenOf("bottom", bottom);
		}
	}

	private void logChildrenOf(String label, Widget parent)
	{
		Widget[] children = parent.getStaticChildren();
		if (children == null)
		{
			log.debug("  {}: no static children", label);
			return;
		}

		log.debug("  {} has {} children, parent w={}", label, children.length, parent.getWidth());
		for (Widget child : children)
		{
			if (child == null || child.isSelfHidden())
			{
				continue;
			}

			log.debug("    {}[{}]: w={} h={} x={} y={} wMode={} xMode={} type={} text={}",
				label,
				child.getId() & 0xFFFF,
				child.getWidth(),
				child.getHeight(),
				child.getRelativeX(),
				child.getRelativeY(),
				child.getWidthMode(),
				child.getXPositionMode(),
				child.getType(),
				child.getText() == null ? "" : child.getText());
		}
	}

	private void logWidget(String label, int componentId)
	{
		Widget widget = client.getWidget(componentId);
		if (widget == null)
		{
			log.debug("  {}: null", label);
			return;
		}

		log.debug("  {}: origW={} w={} origX={} x={} canvasX={} wMode={} xMode={} hidden={}",
			label,
			widget.getOriginalWidth(),
			widget.getWidth(),
			widget.getOriginalX(),
			widget.getRelativeX(),
			widget.getCanvasLocation() == null ? -1 : widget.getCanvasLocation().getX(),
			widget.getWidthMode(),
			widget.getXPositionMode(),
			widget.isHidden());
	}

	/**
	 * Applies {@code delta} extra pixels of width to each chrome widget.
	 *
	 * Skips anything not sized in {@code ABSOLUTE} mode. In the other modes the
	 * stored value is an inset from the parent rather than a width, so adding to
	 * it shrinks the widget instead of growing it.
	 */
	private void resizeChrome(int delta)
	{
		// The bank window and every fixed-width ancestor holding it. Widening the
		// window alone leaves it inside a 512 wide slot that clips it.
		//
		// Outermost first, because revalidate() recomputes only the widget it is
		// called on. Resizing bottom up left the ancestors between the slot and the
		// window still laid out against the old width.
		Widget root = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		BankRoom room = BankRoom.measure(root, client.getCanvasWidth());
		List<Widget> chain = room.getChain();
		int playAreaHeight = room.getHeightLimit();

		for (int i = chain.size() - 1; i >= 0; i--)
		{
			Widget node = chain.get(i);
			WidgetSize size = savedSize(node);

			if (delta == 0)
			{
				if (i == 0)
				{
					// The bank window resolves correctly, so it can have its real
					// mode back and go on tracking the window vertically.
					node.setWidthMode(size.widthMode);
					node.setOriginalWidth(size.originalWidth);
					node.setHeightMode(size.heightMode);
					node.setOriginalHeight(size.originalHeight);
				}
				else
				{
					// Ancestors cannot be restored by mode. Handing back the stored
					// inset and revalidating is what blew the interface root up to
					// the full canvas in the first place, so put the measured pixels
					// back instead. The client rebuilds these with their own modes
					// when the interface next opens.
					node.setWidthMode(WidgetSizeMode.ABSOLUTE);
					node.setOriginalWidth(size.renderedWidth);
					node.setHeightMode(WidgetSizeMode.ABSOLUTE);
					node.setOriginalHeight(size.renderedHeight);
				}

				node.revalidate();
				continue;
			}

			// Set the width outright rather than letting the client derive it. The
			// interface root is handed its size when the interface opens into its
			// slot; its stored inset is 0, so revalidate() resolves it against the
			// screen instead and stretches it to the full canvas.
			node.setWidthMode(WidgetSizeMode.ABSOLUTE);
			node.setOriginalWidth(size.renderedWidth + delta);

			// Same problem vertically, and worse: revalidating the interface root
			// grew it from the play area's 550 to the full 715 canvas, so the bank
			// sat in a container 165px too tall and its lower chrome was pushed
			// down behind the chatbox. Pin the ancestors to the play area, which is
			// measured live so it still tracks a resized client.
			//
			// The bank window itself is left alone here. Its height is either the
			// game's own or the configured row count, handled by the caller.
			if (i > 0 && playAreaHeight > 0)
			{
				node.setHeightMode(WidgetSizeMode.ABSOLUTE);
				node.setOriginalHeight(playAreaHeight);
			}

			if (!resizedAncestors.contains(node))
			{
				resizedAncestors.add(node);
			}

			node.revalidate();
		}

		// Refresh the bank's own group so its inner chrome picks up the new window
		// width. These are children of the window, so the walk above did not reach
		// them.
		if (root != null)
		{
			root.revalidateScroll();
		}

		for (int componentId : WIDTH_TRACKING)
		{
			Widget widget = client.getWidget(componentId);
			if (widget == null)
			{
				continue;
			}

			if (widget.getWidthMode() != WidgetSizeMode.ABSOLUTE)
			{
				log.debug("Skipping widget {} with width mode {}",
					componentId, widget.getWidthMode());
				continue;
			}

			setWidth(widget, originalWidthOf(widget) + delta);
		}
	}

	/**
	 * Moves the right hand end of the bottom button row out with the window.
	 *
	 * Every one of the row's 18 buttons is pinned to the left edge at a fixed
	 * offset, and together they fill the vanilla width exactly, ending 2px short
	 * of the right edge. Widen the row and they all stay put, stranding the right
	 * hand controls mid-window with a gap beside them.
	 *
	 * The row already has a separator near its midpoint, so the split falls where
	 * the interface designers put one: controls left of it keep their place, the
	 * group right of it moves with the edge.
	 *
	 * Offsets come from the saved originals rather than current positions, because
	 * this also runs on passes where the game has not rebuilt the row, and reading
	 * back an already shifted offset would move it twice.
	 */
	private void shiftBottomRow(int delta)
	{
		Widget bottom = client.getWidget(InterfaceID.Bankmain.BOTTOM);
		if (bottom == null)
		{
			return;
		}

		Widget[] children = bottom.getStaticChildren();
		if (children == null)
		{
			return;
		}

		int split = originalWidthOf(bottom) / 2;
		for (Widget child : children)
		{
			if (child == null || child.getXPositionMode() != WidgetPositionMode.ABSOLUTE_LEFT)
			{
				continue;
			}

			WidgetSize size = savedSize(child);
			if (size.originalX < split)
			{
				continue;
			}

			child.setOriginalX(size.originalX + delta);
			child.revalidate();
		}
	}

	/**
	 * Repositions every visible item using the same formula the game script uses,
	 * then resizes the scroll region to match and rebuilds the scrollbar.
	 */
	private void layoutItems(Widget items, int columns, int containerWidth)
	{
		Widget[] children = items.getDynamicChildren();
		if (children == null)
		{
			return;
		}

		int padding = BankLayout.paddingFor(containerWidth, columns);
		int column = 0;
		int row = 0;
		boolean sawSeparator = false;

		for (int i = 0; i < children.length; i++)
		{
			Widget child = children[i];
			if (child == null || child.isSelfHidden())
			{
				continue;
			}

			if (i >= SEPARATOR_INDEX_BASE)
			{
				// Tab separators span a whole row. Close the current row first.
				sawSeparator = true;
				if (column > 0)
				{
					column = 0;
					row++;
				}

				child.setOriginalX(BankLayout.START_X);
				child.setOriginalY(BankLayout.itemY(row));
				child.revalidate();
				row++;
				continue;
			}

			child.setOriginalX(BankLayout.itemX(column, padding));
			child.setOriginalY(BankLayout.itemY(row));
			child.revalidate();

			if (++column >= columns)
			{
				column = 0;
				row++;
			}
		}

		if (column > 0)
		{
			row++;
		}

		if (sawSeparator)
		{
			log.debug("Bank drawn with tab separators; separator placement is approximate");
		}

		applyScroll(items, BankLayout.scrollHeightFor(row));
	}

	/**
	 * Sets the scroll region and asks the game to rebuild the scrollbar so the
	 * thumb matches the new content height.
	 *
	 * The scrollbar rebuild has to be deferred. This runs from a script event, so
	 * the script VM is still on the stack, and calling into it again throws
	 * "scripts are not reentrant".
	 */
	private void applyScroll(Widget items, int scrollHeight)
	{
		items.setScrollHeight(scrollHeight);
		items.revalidateScroll();

		clientThread.invokeLater(() -> rebuildScrollbar(scrollHeight));
	}

	/** Runs the game's own scrollbar rebuild against the new content height. */
	private void rebuildScrollbar(int scrollHeight)
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		Widget scrollbar = client.getWidget(InterfaceID.Bankmain.SCROLLBAR);
		if (items == null || scrollbar == null || items.isHidden())
		{
			return;
		}

		int maxScroll = Math.max(0, scrollHeight - items.getHeight());
		int scrollY = Math.min(items.getScrollY(), maxScroll);

		client.runScript(ScriptID.UPDATE_SCROLLBAR,
			InterfaceID.Bankmain.SCROLLBAR,
			InterfaceID.Bankmain.ITEMS,
			scrollY);
	}

	/**
	 * Puts every resized ancestor back to the size it had before this plugin
	 * touched it.
	 *
	 * Works off held references rather than the widget tree, because this runs
	 * while the bank interface is being torn down and cannot be looked up.
	 */
	private void restoreAncestors()
	{
		for (Widget node : resizedAncestors)
		{
			WidgetSize size = originalWidths.get(node.getId());
			if (size == null)
			{
				continue;
			}

			node.setWidthMode(WidgetSizeMode.ABSOLUTE);
			node.setOriginalWidth(size.renderedWidth);
			node.setHeightMode(WidgetSizeMode.ABSOLUTE);
			node.setOriginalHeight(size.renderedHeight);
			node.revalidate();
		}

		resizedAncestors.clear();
	}

	/**
	 * Forgets everything cached about the current bank interface. Called whenever
	 * the interface is torn down, because the widget tree is rebuilt from scratch
	 * and the captured widths no longer refer to anything.
	 */
	private void resetState()
	{
		originalWidths.clear();
		resizedAncestors.clear();
		modified = false;
		loggedGeometry = false;
		appliedColumns = -1;
		appliedCanvasWidth = -1;
	}

	/** Size the widget had before this plugin first touched it, captured once. */
	private WidgetSize savedSize(Widget widget)
	{
		return originalWidths.computeIfAbsent(widget.getId(), id -> new WidgetSize(widget));
	}

	/**
	 * Rendered width the widget had before this plugin first touched it. This is
	 * the figure a delta is added to, not the stored width, which is an inset for
	 * anything not sized in absolute mode.
	 */
	private int originalWidthOf(Widget widget)
	{
		return savedSize(widget).renderedWidth;
	}

	private void setWidth(Widget widget, int width)
	{
		originalWidthOf(widget);
		widget.setOriginalWidth(width);
		widget.revalidate();
	}
}
