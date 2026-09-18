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
	 * Height of a bank item cell. A dynamic child of the item container that
	 * is this tall is an item; anything else is a tab separator.
	 */
	private static final int ITEM_CELL_HEIGHT = BankLayout.ITEM_HEIGHT;

	/** Pixels kept clear between the widened bank and the edge of the viewport. */
	private static final int EDGE_MARGIN = 4;

	/**
	 * Chrome width assumed when the bank frame cannot be measured.
	 * Deliberately generous: overestimating costs a column, underestimating
	 * pushes the bank past the edge of the client.
	 */
	private static final int FALLBACK_CHROME_WIDTH = 60;

	/**
	 * The script that lays out the bank settings menu, which the game runs each
	 * time the bank is opened. It has no name in the api. Identified by watching
	 * which script was running whenever that menu's rows moved: this one always
	 * produces the layout the menu is drawn with, so its result is what gets
	 * remembered and put back.
	 */
	private static final int BANKMAIN_SETTINGS_LAYOUT = 191;

	/** Above this, a measured chrome width is treated as a bad reading. */
	private static final int MAX_PLAUSIBLE_CHROME_WIDTH = 200;

	/**
	 * Widgets inside the bank that must be widened by hand. Deliberately
	 * short.
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
	 * The bank tags plugin, when it is loaded, used only to ask whether a
	 * layout currently owns the item positions.
	 */
	@com.google.inject.Inject(optional = true)
	private BankTagsService bankTagsService;

	/**
	 * Untouched width of every widget this plugin has widened, keyed by
	 * component id. Widths are always assigned as original plus delta rather
	 * than added to, so that repeated layout passes are idempotent.
	 */
	private final Map<Integer, WidgetSize> originalWidths = new HashMap<>();

	/**
	 * Ancestors this plugin has resized, held directly rather than looked up.
	 * The outer slot belongs to the layout interface, not the bank, so it
	 * outlives the bank closing while the bank's own widgets are rebuilt.
	 */
	private final List<Widget> resizedAncestors = new ArrayList<>();

	/**
	 * Row positions of the bank settings menu as the game lays them out, kept
	 * so they can be put back after widening makes it lay them out again.
	 */
	private int[] vanillaSettingsRows;

	/** A widget's width as it was before this plugin touched it. */
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
	 * Whether this plugin currently has the bank in a non-vanilla layout.
	 * Lets the vanilla column count be a genuine no-op while still undoing
	 * our own work if the user turns the column count back down.
	 */
	private boolean modified;

	/**
	 * The chain measured while the bank was still as the game drew it. Held for
	 * the life of the interface: the rule that finds it reads the width modes
	 * this plugin overwrites, so measuring again would read back its own work.
	 */
	private BankRoom room;

	/** Column count asked for when the bank was opened, held until it closes. */
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
			unpinSettingsMenu();
			restoreLayout();
			restoreAncestors();
			resetState();
		});
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == BANKMAIN_SETTINGS_LAYOUT)
		{
			saveSettingsRows();
		}


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

		// No check for an open bank here.
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
	 * Widens the bank chrome and lays the item grid out again at the
	 * configured column count. Safe to call when the bank is closed, in which
	 * case it does nothing.
	 */
	private void applyLayout()
	{
		pinSettingsMenu();
		trackSettingsRows();

		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null || items.isHidden())
		{
			return;
		}

		// Before any of the early returns below.
		spreadPotionEntries();

		// Fixed mode lays the interface out differently and the measurement
		// that bounds the width finds the whole canvas rather than the game
		// area, so extra columns ran out over the inventory and minimap.
		if (!client.isResized())
		{
			if (modified)
			{
				restoreLayout();
				restoreAncestors();
				originalWidths.clear();
				room = null;
				modified = false;
			}

			latchedColumns = BankLayout.VANILLA_COLUMNS;
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
		int canvasHeight = client.getCanvasHeight();

		// A resize invalidates everything measured so far: the play area, the
		// ancestor widths, and the sizes saved to undo them by.
		if (modified && (canvasWidth != appliedCanvasWidth || canvasHeight != appliedCanvasHeight))
		{
			restoreLayout();
			restoreAncestors();
			originalWidths.clear();
			room = null;

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

		// The one line kept for support: it fires only when the plugin actually
		// changes the bank, so a report says at once whether it was involved.
		log.debug("Laying out bank at {} columns, container width {}", columns, targetWidth);

		// Before resizeChrome, and it has to stay that way.
		pinTabsLeft(delta);
		resizeChrome(delta);
		shiftBottomRow(delta);
		setWidth(items, targetWidth);

		// A bank tag layout or a plugin that arranges the items itself keeps its
		// own positions; only the frame widens around them.
		if (!itemsOwnedByAnotherPlugin(items))
		{
			layoutItems(items, columns, targetWidth);
		}

		// Widening the window makes the game lay the settings menu out again, so
		// its rows are put back in the same pass rather than a tick later.
		modified = true;
		trackSettingsRows();

		appliedColumns = columns;
		appliedCanvasWidth = canvasWidth;
		appliedCanvasHeight = canvasHeight;
	}

	/**
	 * Whether the bank already carries the layout we would apply, in which
	 * case there is nothing to do. This is what keeps the plugin off the hot
	 * path.
	 */
	private boolean isUpToDate(Widget items, int columns, int canvasWidth, int targetWidth)
	{
		return modified
			&& columns == appliedColumns
			&& canvasWidth == appliedCanvasWidth
			&& items.getOriginalWidth() == targetWidth;
	}

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

	/** Whether another plugin, not the game script, is placing the items. */
	private boolean itemsOwnedByAnotherPlugin(Widget items)
	{
		if (bankTagsService != null && bankTagsService.getActiveLayout() != null)
		{
			return true;
		}

		// A layout pads its empty slots out to exactly one cell plus one gap,
		// 48x36, so that its grid closes up.
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
				return true;
			}
		}

		return false;
	}

	/** Column count to use, capped to what the viewport can hold. */
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

	/** The bank's room to grow, measured once while the tree is untouched. */
	private BankRoom room()
	{
		if (room == null)
		{
			room = BankRoom.measure(client.getWidget(InterfaceID.Bankmain.UNIVERSE),
				client.getCanvasWidth());
		}

		return room;
	}

	/** Widest the item container may become without leaving the viewport. */
	private int availableContainerWidth()
	{
		int canvasWidth = client.getCanvasWidth();
		Widget root = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (root == null || canvasWidth <= 0)
		{
			return BankLayout.VANILLA_CONTAINER_WIDTH;
		}

		// The play area, which does not change as the bank grows.
		int maxWindow = room().getLimit() - 2 * EDGE_MARGIN;

		return maxWindow - measuredChrome();
	}

	/** Bank window width that is not item grid. Live: 488 less 460, so 28. */
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
	 * Applies {@code delta} extra pixels of width to each chrome widget.
	 * Skips anything not sized in {@code ABSOLUTE} mode.
	 */
	private void resizeChrome(int delta)
	{
		// The bank window and every fixed-width ancestor holding it. Widening
		// the window alone leaves it inside a 512 wide slot that clips it.
		Widget root = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		BankRoom room = room();
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
					// Ancestors cannot be restored by mode.
					node.setWidthMode(WidgetSizeMode.ABSOLUTE);
					node.setOriginalWidth(size.renderedWidth);
					node.setHeightMode(WidgetSizeMode.ABSOLUTE);
					node.setOriginalHeight(size.renderedHeight);
				}

				node.revalidate();
				continue;
			}

			// Set the width outright rather than letting the client derive
			// it.
			node.setWidthMode(WidgetSizeMode.ABSOLUTE);
			node.setOriginalWidth(size.renderedWidth + delta);

			// Same problem vertically, and worse: revalidating the interface
			// root grew it from the play area's 550 to the full 715 canvas,
			// so the bank sat in a container 165px too tall and its lower
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
				continue;
			}

			setWidth(widget, originalWidthOf(widget) + delta);
		}
	}

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
	 * Holds the bank settings menu at the width the game laid it out for. Its
	 * rows are two columns, the left one anchored right and the right one
	 * anchored left, so a wider container slides them into each other.
	 */
	private void pinSettingsMenu()
	{
		Widget menu = client.getWidget(InterfaceID.Bankmain.MENU_CONTAINER);
		Widget root = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		if (menu == null || root == null)
		{
			return;
		}

		// Saved as a mode and an inset rather than a resolved width, so it stays
		// correct even if the first pass happens with the bank already widened.
		WidgetSize size = savedSize(menu);
		if (size.widthMode != WidgetSizeMode.MINUS)
		{
			return;
		}

		int vanilla = originalWidthOf(root) - size.originalWidth;
		if (vanilla <= 0
			|| (menu.getWidthMode() == WidgetSizeMode.ABSOLUTE && menu.getOriginalWidth() == vanilla))
		{
			return;
		}

		menu.setWidthMode(WidgetSizeMode.ABSOLUTE);
		menu.setOriginalWidth(vanilla);
		menu.revalidate();
	}

	/**
	 * Remembers where the game just put the rows of the bank settings menu.
	 * Only called straight after the game has laid that menu out, because the
	 * rows keep whatever positions they were left with between bank opens, so
	 * reading them at any other moment can pick up our own work.
	 */
	private void saveSettingsRows()
	{
		Widget menu = client.getWidget(InterfaceID.Bankmain.MENU_CONTAINER);
		if (menu == null)
		{
			return;
		}

		Widget[] rows = menu.getStaticChildren();
		if (rows == null)
		{
			return;
		}

		int[] saved = new int[rows.length];
		for (int i = 0; i < rows.length; i++)
		{
			// The drawn position, not the original. The game sets where a row is
			// drawn before the value it was positioned from catches up, so reading
			// the latter here returns whatever this plugin left behind last time.
			saved[i] = rows[i] == null ? 0 : rows[i].getRelativeY();
		}

		vanillaSettingsRows = saved;
	}


	/**
	 * Keeps the rows of the bank settings menu where the game first put them.
	 * Widening the window makes the game lay that menu out again, and it works
	 * its rows out from the width of the window rather than of the menu, which
	 * drops the first three rows on top of the next two.
	 */
	private void trackSettingsRows()
	{
		// Nothing to put back when the bank is its normal width, and the game is
		// free to lay the menu out however it likes.
		if (!modified)
		{
			return;
		}

		Widget menu = client.getWidget(InterfaceID.Bankmain.MENU_CONTAINER);
		if (menu == null)
		{
			return;
		}

		Widget[] rows = menu.getStaticChildren();
		if (rows == null)
		{
			return;
		}

		if (vanillaSettingsRows == null || vanillaSettingsRows.length != rows.length)
		{
			return;
		}

		for (int i = 0; i < rows.length; i++)
		{
			Widget row = rows[i];
			if (row == null || row.getOriginalY() == vanillaSettingsRows[i])
			{
				continue;
			}

			row.setOriginalY(vanillaSettingsRows[i]);

			// Revalidating the menu itself makes the game lay it out again, which is
			// what moved the rows in the first place. Each row is settled on its own.
			row.revalidate();
		}
	}


	/** Gives the settings menu back to the game. */
	private void unpinSettingsMenu()
	{
		Widget menu = client.getWidget(InterfaceID.Bankmain.MENU_CONTAINER);
		if (menu == null)
		{
			return;
		}

		WidgetSize size = originalWidths.get(menu.getId());
		if (size == null)
		{
			return;
		}

		menu.setWidthMode(size.widthMode);
		menu.setOriginalWidth(size.originalWidth);
		menu.revalidate();
	}


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

		// Every entry is built the same way, so the offset of each part is
		// taken as the one most of them agree on.
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
	 * Whether this child is part of the given entry rather than something
	 * drawn between entries. By its y, which is never written here.
	 */
	private boolean partOfEntry(Widget child, int[] entry)
	{
		return child.getOriginalY() >= entry[3]
			&& child.getOriginalY() < entry[3] + BankLayout.ROW_PITCH;
	}

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
	 * Repositions every visible item using the same formula the game script
	 * uses, then resizes the scroll region to match and rebuilds the
	 * scrollbar.
	 */
	private void layoutItems(Widget items, int columns, int containerWidth)
	{
		Widget[] children = items.getDynamicChildren();
		if (children == null)
		{
			return;
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

		applyScroll(items, plan.getHeight() == 0 ? 0 : plan.getHeight() + BankLayout.SCROLL_PADDING);
	}

	/**
	 * Sets the scroll region and asks the game to rebuild the scrollbar so
	 * the thumb matches the new content height. The scrollbar rebuild has to
	 * be deferred.
	 */
	private void applyScroll(Widget items, int scrollHeight)
	{
		items.setScrollHeight(scrollHeight);
		items.revalidateScroll();

		clientThread.invokeLater(() -> rebuildScrollbar(scrollHeight));
	}

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

	/** Puts every resized ancestor back to its untouched size. */
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

	/** Forgets everything cached about the current bank interface. */
	private void resetState()
	{
		originalWidths.clear();
		resizedAncestors.clear();
		vanillaSettingsRows = null;
		room = null;
		modified = false;
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

	/** Rendered width before this plugin touched the widget. */
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
