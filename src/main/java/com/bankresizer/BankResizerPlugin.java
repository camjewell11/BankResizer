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
import java.util.TreeMap;
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
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsService;

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
	 * Height of a bank item cell. A dynamic child of the item container that is
	 * this tall is an item; anything else is a tab separator.
	 *
	 * This replaced a fixed child index of 816, taken from the slot at which
	 * [proc,bankmain_build] begins its separator sweep. That index is the bank's
	 * capacity, which Jagex has raised since, so on a large bank real items were
	 * being mistaken for separators and given a row to themselves. The symptom was
	 * the "view all items" tab appearing to lose its first group.
	 *
	 * The test is "shorter than an item", not "a different height from an item".
	 * When a bank tag layout is involved the client pads empty slots out to 48x36
	 * so that the grid has no gaps, so an inequality test called every padded
	 * empty a separator and gave it a row of its own. LayoutManager.resetWidgets
	 * uses the same less-than test to find where the items stop.
	 */
	private static final int ITEM_CELL_HEIGHT = BankLayout.ITEM_HEIGHT;

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
	 * TABS is deliberately absent. It centres its own icons within its width, so
	 * widening it spread the tab strip across the wider window and opened a gap
	 * between the first tab and the left edge of the bank. Left at its vanilla
	 * width the strip keeps vanilla spacing and stays aligned with the item grid,
	 * which is where the eye expects it.
	 */
	private static final int[] WIDTH_TRACKING = {
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
	 * The bank tags plugin, when it is loaded, used only to ask whether a layout
	 * currently owns the item positions.
	 *
	 * Optional because a client without the bank tags plugin has no binding for
	 * it, and a missing binding would stop this plugin loading at all. Everything
	 * that reads it treats null as "no layout active".
	 */
	@com.google.inject.Inject(optional = true)
	private BankTagsService bankTagsService;

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

		private final int xPositionMode;

		private final int renderedX;

		private WidgetSize(Widget widget)
		{
			this.originalX = widget.getOriginalX();
			this.xPositionMode = widget.getXPositionMode();
			this.renderedX = widget.getRelativeX();
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

	/**
	 * Mix of cell sizes last dumped, so that a view is recorded when its shape
	 * changes rather than only when the bank is opened.
	 *
	 * Potion storage, the group storage and a tag tab all replace the container's
	 * contents without the bank closing, so a once-per-open dump never saw them.
	 */
	private String loggedShape;

	/** Geometry of the potion store last dumped, for the same reason. */
	private String loggedPotionShape;


	/** Layout passes since the last timing report. */
	private int passes;

	/** Nanoseconds spent laying out since the last timing report. */
	private long totalNanos;

	/** Longest single pass since the last timing report. */
	private long worstNanos;

	/** Widgets repositioned by the most recent pass. */
	private int movedWidgets;

	/** Widgets repositioned since the last timing report. */
	private long totalMoved;

	/** When the current timing window started. */
	private long windowStartedNanos;

	/**
	 * Column count asked for when the bank was opened, held until it closes.
	 *
	 * Changing the count with the bank open left it half rebuilt, because the game
	 * positions its own widgets only when it builds the interface, so a new count
	 * reached some of them and not others. {@code -1} means nothing is latched and
	 * the next layout will take the configured value.
	 */
	private int latchedColumns = -1;

	/** Column count and canvas width of the last layout actually applied. */
	private int appliedColumns = -1;

	private int appliedCanvasWidth = -1;

	/** Canvas height the current layout was worked out against. */
	private int appliedCanvasHeight = -1;

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

		// No check for an open bank here. Config events arrive on the AWT thread,
		// where widgets cannot be read, and a check here would not hold anyway: the
		// bank's own scripts call applyLayout on every rebuild and would pick the
		// new value up regardless. The count is latched at open instead.
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

		// Before any of the early returns below. The store is its own container and
		// its widgets outlive the bank closing, so reopening a bank that was last
		// showing the store finds them still where they were left. On that pass the
		// store is not visible yet, and by the time it is, the bank's own layout is
		// up to date and every later pass returns before reaching here. That left
		// the store wrong until it was closed and reopened by hand.
		spreadPotionEntries();

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
		int canvasHeight = client.getCanvasHeight();

		// A resize invalidates everything measured so far: the play area, the
		// ancestor widths, and the sizes saved to undo them by. Rather than try to
		// re-derive all of it against a bank the client is midway through relaying
		// out, hand the bank back to the game and stay out of the way until it is
		// reopened. Otherwise the bank can be left in a state that has to be closed
		// with a hotkey before it works again.
		if (modified && (canvasWidth != appliedCanvasWidth || canvasHeight != appliedCanvasHeight))
		{
			log.debug("Client resized to {}x{}; returning the bank to {} columns until reopened",
				canvasWidth, canvasHeight, BankLayout.VANILLA_COLUMNS);

			restoreLayout();
			restoreAncestors();
			originalWidths.clear();

			modified = false;
			latchedColumns = BankLayout.VANILLA_COLUMNS;
			appliedCanvasWidth = canvasWidth;
			appliedCanvasHeight = canvasHeight;

			// One redraw so the bank is usable straight away rather than at the
			// next rebuild.
			Widget root = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
			if (root != null)
			{
				root.revalidateScroll();
			}

			return;
		}

		if (isUpToDate(items, columns, canvasWidth, targetWidth))
		{
			return;
		}

		int delta = targetWidth - BankLayout.VANILLA_CONTAINER_WIDTH;
		long startedNanos = System.nanoTime();

		log.debug("Laying out bank at {} columns, container width {} (delta {}), because {}",
			columns, targetWidth, delta, staleReason(items, columns, canvasWidth, targetWidth));

		boolean dump = log.isDebugEnabled() && !loggedGeometry;
		if (dump)
		{
			logGeometry("before");
		}

		// Before resizeChrome, and it has to stay that way. Pinning reads the
		// position the strip is sitting at, and once the window has been widened
		// the strip has already re-centred into it, so reading it afterwards
		// captures the centred position and pins the strip right back where it was.
		pinTabsLeft(delta);
		resizeChrome(delta);
		shiftBottomRow(delta);
		setWidth(items, targetWidth);

		if (itemsOwnedByAnotherPlugin(items))
		{
			log.debug("Another plugin owns the item positions; widening the frame only");
		}
		else
		{
			layoutItems(items, columns, targetWidth);
		}

		if (dump)
		{
			logGeometry("after");
			loggedGeometry = true;
		}

		if (log.isDebugEnabled())
		{
			logPotionStore();
		}

		recordPass(System.nanoTime() - startedNanos);

		modified = true;
		appliedColumns = columns;
		appliedCanvasWidth = canvasWidth;
		appliedCanvasHeight = canvasHeight;
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
		return staleReason(items, columns, canvasWidth, targetWidth) == null;
	}

	/**
	 * Why the layout has to be applied again, or null when it does not.
	 *
	 * Reported alongside each pass so that a bank relaid out repeatedly says which
	 * of its inputs keeps changing, rather than leaving it to be guessed at.
	 */
	private String staleReason(Widget items, int columns, int canvasWidth, int targetWidth)
	{
		if (!modified)
		{
			return "nothing applied yet";
		}

		if (columns != appliedColumns)
		{
			return "columns changed from " + appliedColumns + " to " + columns;
		}

		if (canvasWidth != appliedCanvasWidth)
		{
			return "canvas changed from " + appliedCanvasWidth + " to " + canvasWidth;
		}

		if (client.getCanvasHeight() != appliedCanvasHeight)
		{
			return "canvas height changed from " + appliedCanvasHeight
				+ " to " + client.getCanvasHeight();
		}

		if (items.getOriginalWidth() != targetWidth)
		{
			return "the game rebuilt the item container, its width is "
				+ items.getOriginalWidth() + " not " + targetWidth;
		}

		return null;
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
		pinTabsLeft(0);
		shiftBottomRow(0);
		setWidth(items, BankLayout.VANILLA_CONTAINER_WIDTH);

		if (!itemsOwnedByAnotherPlugin(items))
		{
			layoutItems(items, BankLayout.VANILLA_COLUMNS, BankLayout.VANILLA_CONTAINER_WIDTH);
		}
	}

	/**
	 * Whether something other than the game script is deciding where the items go.
	 *
	 * A bank tag layout stores an item per position in a flat array and draws it
	 * at a position derived from its index with a hardcoded eight per row, in
	 * LayoutManager. Relaying those items out underneath it produces a grid that
	 * disagrees with the layout the user arranged, so when a layout is active the
	 * items are left exactly where it put them and only the frame is widened.
	 *
	 * Plugins that present their own view of the bank through a bank tag, rather
	 * than by positioning widgets themselves, are covered by the same check.
	 */
	private boolean itemsOwnedByAnotherPlugin(Widget items)
	{
		if (bankTagsService != null && bankTagsService.getActiveLayout() != null)
		{
			return true;
		}

		// A layout pads its empty slots out to exactly one cell plus one gap, 48x36,
		// so that its grid closes up. Inventory Setups is drawn this way while
		// getActiveLayout() reports nothing, so this size is the more reliable
		// signal of the two.
		//
		// The test is that exact pair and not merely "not the game's 36x32", which
		// was the first attempt. A plain bank evidently holds at least one visible
		// cell of some other size, so the looser test fired on an ordinary tab and
		// stopped the columns being applied at all.
		Widget[] children = items.getDynamicChildren();
		if (children == null)
		{
			return false;
		}

		for (Widget child : children)
		{
			if (child == null || child.isSelfHidden())
			{
				continue;
			}

			if (child.getOriginalWidth() == BankLayout.COLUMN_PITCH
				&& child.getOriginalHeight() == BankLayout.ROW_PITCH)
			{
				log.debug("Padded {}x{} cell found; another plugin owns this view",
					child.getOriginalWidth(), child.getOriginalHeight());
				return true;
			}
		}

		return false;
	}

	/**
	 * Column count to use, honouring the configured value but never letting the
	 * bank grow past the edge of the viewport.
	 */
	private int resolveColumns()
	{
		if (latchedColumns < 0)
		{
			// Fit to width is latched as "as many as possible" rather than as a
			// number, so that it still follows a client resized while the bank is
			// open, which is the one case where relaying out is wanted.
			latchedColumns = config.fitToWidth() ? Integer.MAX_VALUE : config.columns();
		}

		int limit = BankLayout.maxColumnsFor(availableContainerWidth());

		return Math.max(BankLayout.VANILLA_COLUMNS, Math.min(latchedColumns, limit));
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
		logChildrenOf("tabs", client.getWidget(InterfaceID.Bankmain.TABS));
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
		if (parent == null)
		{
			log.debug("  {}: null", label);
			return;
		}

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
	 * Holds the tab strip against the left of the widened window.
	 *
	 * The strip is centre anchored, so it re-centres itself in the wider window
	 * and leaves a growing gap between the first tab and the left edge of the
	 * bank. Taking it out of the widening list was not enough on its own, because
	 * the anchor, not the width, is what moves it.
	 *
	 * Pinning uses the position the strip rendered at before anything was touched,
	 * so it lands exactly where the unmodified client drew it. That is why this
	 * runs before the window is widened: afterwards the strip has re-centred and
	 * the position read back is the one being corrected.
	 */
	private void pinTabsLeft(int delta)
	{
		Widget tabs = client.getWidget(InterfaceID.Bankmain.TABS);
		if (tabs == null)
		{
			return;
		}

		WidgetSize size = savedSize(tabs);

		if (delta == 0)
		{
			tabs.setXPositionMode(size.xPositionMode);
			tabs.setOriginalX(size.originalX);
		}
		else
		{
			tabs.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
			tabs.setOriginalX(size.renderedX);
		}

		tabs.revalidate();
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
	 * Accumulates the cost of one layout pass and reports it periodically.
	 *
	 * The bank rebuilds several times a second while it is open and this plugin
	 * follows each rebuild, so the figure that matters is not one pass but the
	 * share of a second they add up to. Reported every 25 passes with the worst
	 * single pass alongside the average, because an occasional long pass is what
	 * would show as a stutter rather than a lower frame rate.
	 */
	private void recordPass(long nanos)
	{
		if (passes == 0)
		{
			windowStartedNanos = System.nanoTime();
		}

		passes++;
		totalNanos += nanos;
		totalMoved += movedWidgets;
		worstNanos = Math.max(worstNanos, nanos);

		if (passes < 25)
		{
			return;
		}

		long elapsed = Math.max(1L, System.nanoTime() - windowStartedNanos);

		log.debug("Bank Resizer: {} passes in {} ms, {} us each on average, worst {} us,"
				+ " {} widgets per pass, {}% of wall clock",
			passes,
			elapsed / 1_000_000,
			totalNanos / passes / 1_000,
			worstNanos / 1_000,
			totalMoved / passes,
			String.format("%.2f", 100.0 * totalNanos / elapsed));

		passes = 0;
		totalNanos = 0;
		worstNanos = 0;
		totalMoved = 0;
	}

	/**
	 * Spreads the potion store's entries to match the width they were given.
	 *
	 * The store is an overlay covering the item area, so its container has to span
	 * the widened bank and cannot simply be held at its old size; doing that left a
	 * strip down its left uncovered with the bank showing through.
	 *
	 * The game script sizes each entry from the container width but lays the two
	 * columns out on a pitch fixed at the vanilla width, so at a container of 521
	 * the entries come out 252 wide on a 204 pitch and overlap by 48px. At the
	 * vanilla 425 the two agree, which is why an unmodified client never shows it.
	 *
	 * Known gap, to revisit: stepping the column count down to 8 and back up with
	 * the store as the last open tab still leaves entries misaligned until the
	 * store itself is reopened. Reopening the bank does not clear it. The offsets
	 * agreed on by most entries are what gets applied, so a store that is mostly
	 * wrong agrees on the wrong answer, and nothing in a bank rebuild makes the
	 * game relay the store to break the tie.
	 *
	 * Nothing here trusts an entry to be where it was left. The store's widgets
	 * outlive the bank closing, the game relays some of them on its own schedule,
	 * and another plugin may reorder them, so an entry can be found part way
	 * through someone else's arrangement. A column comes from the entry's place in
	 * its row, and the offsets inside an entry come from what most entries agree
	 * on, so a store found in a bad state is put right rather than carried forward.
	 */
	private void spreadPotionEntries()
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
		if (items == null || items.isHidden())
		{
			return;
		}

		Widget[] children = items.getDynamicChildren();
		if (children == null)
		{
			return;
		}

		// Each entry as {first child, one past its last, block x, block y}.
		List<int[]> entries = new ArrayList<>();
		int entryWidth = 0;

		for (int i = 0; i < children.length; i++)
		{
			Widget child = children[i];
			if (child == null || child.isSelfHidden()
				|| child.getOriginalHeight() > BankLayout.ROW_PITCH)
			{
				continue;
			}

			if (isEntryBackground(child))
			{
				if (!entries.isEmpty())
				{
					entries.get(entries.size() - 1)[1] = i;
				}

				entries.add(new int[]{i, children.length, child.getOriginalX(),
					child.getOriginalY()});
				entryWidth = Math.max(entryWidth, child.getOriginalWidth());
			}
		}

		if (entries.size() < 2 || entryWidth <= 0)
		{
			return;
		}

		// Reading order within a row, so the leftmost entry of each row is its
		// first column however the entries are currently placed.
		List<int[]> byRow = new ArrayList<>(entries);
		byRow.sort((a, b) -> a[3] != b[3]
			? Integer.compare(a[3], b[3])
			: a[2] != b[2] ? Integer.compare(a[2], b[2]) : Integer.compare(a[0], b[0]));

		// Every entry is built the same way, so the offset of each part is taken as
		// the one most of them agree on. Earlier versions measured each entry
		// against its own block, which carried that entry's damage forward: an
		// entry left crooked by a previous pass stayed crooked, and its icon and
		// text drifted behind the neighbouring column. A handful of crooked entries
		// cannot outvote the rest, so they are put right instead.
		int icon = commonOffset(children, entries, Part.ICON);
		int text = commonOffset(children, entries, Part.TEXT);
		int heart = commonOffset(children, entries, Part.HEART);
		int pitch = columnPitch(byRow);
		int inset = pitch > 0 && heart > 0 && heart < pitch ? pitch - heart : -1;

		int column = 0;
		int row = Integer.MIN_VALUE;

		for (int[] entry : byRow)
		{
			column = entry[3] == row ? column + 1 : 0;
			row = entry[3];

			int base = column * entryWidth;

			for (int i = entry[0]; i < entry[1]; i++)
			{
				Widget child = children[i];
				if (child == null || child.isSelfHidden()
					|| child.getOriginalHeight() > BankLayout.ROW_PITCH
					|| !partOfEntry(child, entry))
				{
					continue;
				}

				int moved = base + offsetFor(child, icon, text, heart, inset, entryWidth);
				if (moved != child.getOriginalX())
				{
					child.setOriginalX(moved);
					child.revalidate();
				}
			}
		}
	}

	/**
	 * Whether this child is part of the given entry rather than something drawn
	 * between entries.
	 *
	 * By its y, which is never written here. An entry's range runs from its block
	 * to the next one, so a section heading such as "Potions" or "Vials" falls
	 * inside the range of whichever entry precedes it. Treating those as the
	 * entry's own text moved them to where an entry's text belongs.
	 */
	private boolean partOfEntry(Widget child, int[] entry)
	{
		return child.getOriginalY() >= entry[3]
			&& child.getOriginalY() < entry[3] + BankLayout.ROW_PITCH;
	}

	/** The parts an entry is built from, told apart by size and type. */
	private enum Part
	{
		BLOCK, ICON, TEXT, HEART
	}

	private Part partOf(Widget child)
	{
		if (child.getType() != WidgetType.GRAPHIC)
		{
			return Part.TEXT;
		}

		if (child.getOriginalWidth() > BankLayout.ITEM_WIDTH)
		{
			return Part.BLOCK;
		}

		return child.getOriginalWidth() == BankLayout.ITEM_WIDTH ? Part.ICON : Part.HEART;
	}

	private boolean isEntryBackground(Widget child)
	{
		return partOf(child) == Part.BLOCK;
	}

	private boolean isFavouriteHeart(Widget child)
	{
		return partOf(child) == Part.HEART;
	}

	/** Where in its entry a part belongs, once the entry starts at zero. */
	private int offsetFor(Widget child, int icon, int text, int heart, int inset, int entryWidth)
	{
		switch (partOf(child))
		{
			case BLOCK:
				return 0;

			case ICON:
				return icon;

			case HEART:
				// The one part the game holds against the entry's right edge.
				return inset >= 0 ? entryWidth - inset : heart;

			default:
				return text;
		}
	}

	/** The offset most entries put {@code part} at, or 0 if none agree. */
	private int commonOffset(Widget[] children, List<int[]> entries, Part part)
	{
		Map<Integer, Integer> counts = new HashMap<>();

		for (int[] entry : entries)
		{
			for (int i = entry[0]; i < entry[1]; i++)
			{
				Widget child = children[i];
				if (child == null || child.isSelfHidden()
					|| child.getOriginalHeight() > BankLayout.ROW_PITCH
					|| !partOfEntry(child, entry)
					|| partOf(child) != part)
				{
					continue;
				}

				counts.merge(child.getOriginalX() - entry[2], 1, Integer::sum);
			}
		}

		int best = 0;
		int seen = 0;

		for (Map.Entry<Integer, Integer> offset : counts.entrySet())
		{
			if (offset.getValue() > seen && offset.getKey() >= 0)
			{
				seen = offset.getValue();
				best = offset.getKey();
			}
		}

		return best;
	}

	/** Gap between the two entries of a row, or 0 when no row holds two. */
	private int columnPitch(List<int[]> byRow)
	{
		for (int i = 0; i + 1 < byRow.size(); i++)
		{
			if (byRow.get(i)[3] == byRow.get(i + 1)[3])
			{
				int pitch = byRow.get(i + 1)[2] - byRow.get(i)[2];
				if (pitch > 0)
				{
					return pitch;
				}
			}
		}

		return 0;
	}

	/**
	 * Dumps the potion store's geometry when it changes.
	 *
	 * The potion store is drawn into POTIONSTORE_ITEMS, a container of its own
	 * rather than the bank's item container, so nothing this plugin does to the
	 * item grid reaches it. What does reach it is the window growing underneath
	 * it, which is the likely source of its misalignment.
	 */
	private void logPotionStore()
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
		if (items == null || items.isHidden())
		{
			return;
		}

		StringBuilder shape = new StringBuilder();
		int[] parts = {
			InterfaceID.Bankmain.POTIONSTORE_CONTAINER,
			InterfaceID.Bankmain.POTIONSTORE_BACKGROUND,
			InterfaceID.Bankmain.POTIONSTORE_ITEMS,
			InterfaceID.Bankmain.POTIONSTORE_SCROLLBAR,
		};

		for (int part : parts)
		{
			Widget widget = client.getWidget(part);
			if (widget == null)
			{
				continue;
			}

			shape.append(String.format(" [%d origW=%d w=%d origX=%d x=%d wMode=%d xMode=%d]",
				part & 0xffff, widget.getOriginalWidth(), widget.getWidth(),
				widget.getOriginalX(), widget.getRelativeX(),
				widget.getWidthMode(), widget.getXPositionMode()));
		}

		Widget[] children = items.getDynamicChildren();
		if (children != null)
		{
			int shown = 0;
			StringBuilder tall = new StringBuilder();

			for (Widget child : children)
			{
				if (child == null || child.isSelfHidden())
				{
					continue;
				}

				if (child.getOriginalHeight() > BankLayout.ROW_PITCH)
				{
					tall.append(String.format(" [%dx%d x=%d y=%d type=%d]",
						child.getOriginalWidth(), child.getOriginalHeight(),
						child.getOriginalX(), child.getOriginalY(), child.getType()));
					continue;
				}

				if (shown++ < 8)
				{
					shape.append(String.format(" child[%dx%d x=%d y=%d type=%d]",
						child.getOriginalWidth(), child.getOriginalHeight(),
						child.getOriginalX(), child.getOriginalY(), child.getType()));
				}
			}

			shape.append(" taller than a row:").append(tall.length() == 0 ? " none" : tall);
		}

		String text = shape.toString();
		if (!text.equals(loggedPotionShape))
		{
			loggedPotionShape = text;
			log.debug("potion store:{}", text);
		}
	}

	/**
	 * Dumps the shape of the item container once per bank open.
	 *
	 * Diagnostic only. The "view all items" tab has twice been laid out wrongly
	 * because the separator and item children were assumed to be arranged in a way
	 * they are not, so this records what is actually there rather than what was
	 * expected: how many children exist, which of them are short enough to be
	 * separators, and where the first visible items sit.
	 */
	private void logItemChildren(Widget[] children)
	{
		int visible = 0;
		int hidden = 0;
		StringBuilder shorts = new StringBuilder();
		StringBuilder first = new StringBuilder();

		// Every distinct size, counted. Sampling only the first few cells is what
		// let a stray size in a plain bank go unnoticed and trip the ownership
		// check on an ordinary tab.
		Map<String, Integer> sizes = new TreeMap<>();

		for (int i = 0; i < children.length; i++)
		{
			Widget child = children[i];
			if (child == null)
			{
				continue;
			}

			if (child.isSelfHidden())
			{
				hidden++;
				continue;
			}

			visible++;

			// Rules are keyed by height alone. This plugin stretches them, so
			// keying them by width made the shape flip between the game's value
			// and ours on alternate passes and dumped the view twice over.
			sizes.merge(child.getOriginalHeight() < ITEM_CELL_HEIGHT
				? "rule h" + child.getOriginalHeight()
				: child.getOriginalWidth() + "x" + child.getOriginalHeight(), 1, Integer::sum);

			if (child.getOriginalWidth() != BankLayout.ITEM_WIDTH)
			{
				// Everything that is not an ordinary cell, with its item id, which
				// is what tells a double width item from a heading. Guessing that
				// from the width alone is what put a wide cell on a row of its own.
				shorts.append(String.format(" [%d %dx%d item=%d x=%d y=%d type=%d text=%s]",
					i, child.getOriginalWidth(), child.getOriginalHeight(), child.getItemId(),
					child.getOriginalX(), child.getOriginalY(), child.getType(), child.getText()));
			}
			else if (visible <= 6)
			{
				first.append(String.format(" [%d item=%d h=%d w=%d x=%d y=%d]",
					i, child.getItemId(), child.getOriginalHeight(), child.getOriginalWidth(),
					child.getOriginalX(), child.getOriginalY()));
			}
		}

		String shape = sizes.toString();
		if (shape.equals(loggedShape))
		{
			return;
		}

		loggedShape = shape;

		log.debug("item container: {} children, {} visible, {} hidden", children.length, visible, hidden);
		log.debug("  first visible:{}", first.length() == 0 ? " none" : first.toString());
		log.debug("  not an ordinary cell:{}", shorts.length() == 0 ? " none" : shorts.toString());
		log.debug("  cell sizes: {}", shape);
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

		if (log.isDebugEnabled())
		{
			logItemChildren(children);
		}

		List<Widget> visible = new ArrayList<>();
		for (Widget child : children)
		{
			if (child != null && !child.isSelfHidden())
			{
				visible.add(child);
			}
		}

		int count = visible.size();
		int[] xs = new int[count];
		int[] ys = new int[count];
		int[] itemIds = new int[count];
		int[] heights = new int[count];

		for (int i = 0; i < count; i++)
		{
			Widget child = visible.get(i);
			xs[i] = child.getOriginalX();
			ys[i] = child.getOriginalY();

			// Read straight off the widget. savedSize caches by widget id, and every
			// dynamic child of the container shares its parent's id, so all 1438 of
			// them resolved to one cached entry and were classified alike.
			itemIds[i] = child.getItemId();
			heights[i] = child.getOriginalHeight();
		}

		BankGrid.Plan plan = BankGrid.plan(xs, ys, itemIds, heights, columns);
		int padding = BankLayout.paddingFor(containerWidth, columns);

		// The game's own separator width, 374 at the stock container width.
		int ruleWidth = containerWidth - BankLayout.START_X - BankLayout.RIGHT_INSET;

		for (BankGrid.Cell cell : plan.getCells())
		{
			Widget child = visible.get(cell.getIndex());
			child.setOriginalY(cell.getY());

			switch (cell.getKind())
			{
				case RULE:
					child.setOriginalX(BankLayout.START_X);
					child.setOriginalWidth(ruleWidth);
					break;

				case FILLER:
					// Covers whatever cells are spare at the end of the row. A row
					// that comes out full leaves none, so the block collapses.
					child.setOriginalX(BankLayout.itemX(cell.getColumn(), padding));
					child.setOriginalWidth(cell.getSpan() == 0
						? 0
						: cell.getSpan() * (BankLayout.ITEM_WIDTH + padding) - padding);
					break;

				default:
					child.setOriginalX(BankLayout.itemX(cell.getColumn(), padding));
					break;
			}

			child.revalidate();
		}

		movedWidgets = plan.getCells().size();

		applyScroll(items, plan.getHeight() == 0 ? 0 : plan.getHeight() + BankLayout.SCROLL_PADDING);
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
		loggedShape = null;
		loggedPotionShape = null;
		latchedColumns = -1;
		appliedColumns = -1;
		appliedCanvasWidth = -1;
		appliedCanvasHeight = -1;
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
