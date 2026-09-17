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
import java.util.HashMap;
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
	 * Widgets whose width tracks the item container. The tab strip re-centres its
	 * own icons from its width, so widening it is enough to reflow the tabs.
	 */
	private static final int[] WIDTH_TRACKING = {
		InterfaceID.Bankmain.UNIVERSE,
		InterfaceID.Bankmain.FRAME,
		InterfaceID.Bankmain.ITEMS_CONTAINER,
		InterfaceID.Bankmain.TABS,
		InterfaceID.Bankmain.BOTTOM,
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
	private final Map<Integer, Integer> originalWidths = new HashMap<>();

	/**
	 * Whether this plugin currently has the bank in a non-vanilla layout. Lets the
	 * vanilla column count be a genuine no-op while still undoing our own work if
	 * the user turns the column count back down.
	 */
	private boolean modified;

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
		int delta = targetWidth - BankLayout.VANILLA_CONTAINER_WIDTH;

		log.debug("Laying out bank at {} columns, container width {} (delta {})",
			columns, targetWidth, delta);
		logGeometry();

		resizeChrome(delta);
		setWidth(items, targetWidth);
		layoutItems(items, columns, targetWidth);
		modified = true;
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
	 * How wide the item container is allowed to become.
	 *
	 * The bound is the client canvas, not any bank widget. Measuring the bank's
	 * own root is circular: it is an interface layer whose width is not the width
	 * of the visible bank window, which made this collapse to vanilla.
	 *
	 * Chrome is taken from the bank frame where that looks sane, and otherwise
	 * falls back to a conservative constant, so a surprising widget tree costs a
	 * column or two rather than pushing the bank off screen.
	 */
	private int availableContainerWidth()
	{
		int canvasWidth = client.getCanvasWidth();
		if (canvasWidth <= 0)
		{
			return BankLayout.VANILLA_CONTAINER_WIDTH;
		}

		return canvasWidth - measuredChrome() - 2 * EDGE_MARGIN;
	}

	/**
	 * Width of the bank window that is not item grid: borders, and the inset the
	 * grid sits at. Only trusted when it falls in a plausible range.
	 */
	private int measuredChrome()
	{
		Widget frame = client.getWidget(InterfaceID.Bankmain.FRAME);
		if (frame == null)
		{
			return FALLBACK_CHROME_WIDTH;
		}

		int chrome = originalWidthOf(frame) - BankLayout.VANILLA_CONTAINER_WIDTH;
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
	private void logGeometry()
	{
		if (!log.isDebugEnabled())
		{
			return;
		}

		log.debug("canvas {}x{}", client.getCanvasWidth(), client.getCanvasHeight());
		logWidget("UNIVERSE", InterfaceID.Bankmain.UNIVERSE);
		logWidget("FRAME", InterfaceID.Bankmain.FRAME);
		logWidget("ITEMS_CONTAINER", InterfaceID.Bankmain.ITEMS_CONTAINER);
		logWidget("ITEMS", InterfaceID.Bankmain.ITEMS);
		logWidget("TABS", InterfaceID.Bankmain.TABS);
		logWidget("BOTTOM", InterfaceID.Bankmain.BOTTOM);
		logWidget("SCROLLBAR", InterfaceID.Bankmain.SCROLLBAR);
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

	/** Applies {@code delta} extra pixels of width to each chrome widget. */
	private void resizeChrome(int delta)
	{
		for (int componentId : WIDTH_TRACKING)
		{
			Widget widget = client.getWidget(componentId);
			if (widget == null)
			{
				continue;
			}

			setWidth(widget, originalWidthOf(widget) + delta);
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
	 * Forgets everything cached about the current bank interface. Called whenever
	 * the interface is torn down, because the widget tree is rebuilt from scratch
	 * and the captured widths no longer refer to anything.
	 */
	private void resetState()
	{
		originalWidths.clear();
		modified = false;
	}

	/** Width the widget had before this plugin first touched it. */
	private int originalWidthOf(Widget widget)
	{
		return originalWidths.computeIfAbsent(widget.getId(), id -> widget.getOriginalWidth());
	}

	private void setWidth(Widget widget, int width)
	{
		originalWidthOf(widget);
		widget.setOriginalWidth(width);
		widget.revalidate();
	}
}
