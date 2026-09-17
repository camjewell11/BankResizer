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
	internalName = "bank-resizer",
	enabledByDefault = false
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
			originalWidths.clear();
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
			originalWidths.clear();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			originalWidths.clear();
		}
	}

	/**
	 * Widens the bank chrome and lays the item grid out again at the configured
	 * column count. Safe to call when the bank is closed, in which case it does
	 * nothing.
	 */
	private void applyLayout()
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null || items.isHidden())
		{
			return;
		}

		int columns = resolveColumns();
		int targetWidth = BankLayout.containerWidthFor(columns);
		int delta = targetWidth - BankLayout.VANILLA_CONTAINER_WIDTH;

		log.debug("Laying out bank at {} columns, container width {} (delta {})",
			columns, targetWidth, delta);

		resizeChrome(delta);
		setWidth(items, targetWidth);
		layoutItems(items, columns, targetWidth);
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
	 * How wide the item container is allowed to become. Derived by measuring the
	 * bank's own non-grid chrome rather than hardcoding it, so it stays correct
	 * if the interface changes.
	 */
	private int availableContainerWidth()
	{
		Widget root = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (root == null)
		{
			return BankLayout.VANILLA_CONTAINER_WIDTH;
		}

		Widget parent = root.getParent();
		int bound = parent != null ? parent.getWidth() : client.getCanvasWidth();

		// Everything in the bank window that is not the item grid.
		int chrome = originalWidthOf(root) - BankLayout.VANILLA_CONTAINER_WIDTH;

		return bound - chrome - EDGE_MARGIN;
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
	 */
	private void applyScroll(Widget items, int scrollHeight)
	{
		items.setScrollHeight(scrollHeight);
		items.revalidateScroll();

		Widget scrollbar = client.getWidget(InterfaceID.Bankmain.SCROLLBAR);
		if (scrollbar == null)
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
