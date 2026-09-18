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
import java.util.Comparator;
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

	/** Width of the rule the game draws between the store's columns. */
	private static final int DIVIDER_WIDTH = 1;

	/**
	 * Chrome width assumed when the bank frame cannot be measured.
	 * Deliberately generous: overestimating costs a column, underestimating
	 * pushes the bank past the edge of the client.
	 */
	private static final int FALLBACK_CHROME_WIDTH = 60;

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
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null || items.isHidden())
		{
			return;
		}

		// Before any of the early returns below.
		layoutPotionStore();

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

		modified = true;
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
	 * Lays the potion store out in as many columns as its width allows.
	 *
	 * The game draws two columns whatever the size, so a store as wide as a
	 * widened bank wastes most of its room. Entries are repacked, the sections
	 * behind them resized, and the scroll shortened to match.
	 *
	 * Nothing here trusts an entry to be where it was left: these widgets outlive
	 * the bank closing, the game relays some of them on its own, and another
	 * plugin may reorder them. Sections come from the full width blocks behind
	 * them, and the offsets within an entry from what most entries agree on.
	 */
	private void layoutPotionStore()
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

		int width = items.getWidth();
		int columns = BankLayout.potionColumnsFor(width);
		int entryWidth = width / columns;
		if (entryWidth <= 0)
		{
			return;
		}

		List<int[]> entries = new ArrayList<>();
		List<Widget> sections = new ArrayList<>();
		List<Widget> rules = new ArrayList<>();

		for (int i = 0; i < children.length; i++)
		{
			Widget child = children[i];
			if (child == null || child.isSelfHidden())
			{
				continue;
			}

			// A divider is thin whatever its height, which matters because a
			// section of one row is exactly as tall as an entry.
			if (child.getOriginalWidth() <= DIVIDER_WIDTH)
			{
				rules.add(child);
				continue;
			}

			if (child.getOriginalHeight() > BankLayout.ROW_PITCH)
			{
				sections.add(child);
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
			}
		}

		if (entries.size() < 2 || sections.isEmpty())
		{
			return;
		}

		List<int[]> byRow = new ArrayList<>(entries);
		byRow.sort(READING_ORDER);

		int icon = commonOffset(children, entries, Part.ICON);
		int text = commonOffset(children, entries, Part.TEXT);
		int heartInset = heartInsetFrom(children, byRow);

		sections.sort((a, b) -> Integer.compare(a.getOriginalY(), b.getOriginalY()));

		// The gap above the first section is the band its heading sits in, and
		// every section keeps the same gap above it.
		int gap = sections.get(0).getOriginalY();
		int cursor = gap;

		for (Widget section : sections)
		{
			int top = section.getOriginalY();
			int bottom = top + section.getOriginalHeight();

			List<int[]> mine = new ArrayList<>();
			for (int[] entry : byRow)
			{
				if (entry[3] >= top && entry[3] < bottom)
				{
					mine.add(entry);
				}
			}

			if (mine.isEmpty())
			{
				continue;
			}

			int rows = (mine.size() + columns - 1) / columns;
			int height = rows * BankLayout.ROW_PITCH;
			int shift = cursor - top;

			for (int i = 0; i < mine.size(); i++)
			{
				placeEntry(children, mine.get(i), (i % columns) * entryWidth,
					cursor + (i / columns) * BankLayout.ROW_PITCH,
					entryWidth, icon, text, heartInset);
			}

			// The heading above this section travels with it.
			for (Widget child : children)
			{
				if (child != null && !child.isSelfHidden()
					&& child.getOriginalHeight() <= BankLayout.ROW_PITCH
					&& child.getOriginalY() >= top - gap && child.getOriginalY() < top)
				{
					move(child, child.getOriginalX(), child.getOriginalY() + shift);
				}
			}

			resize(section, section.getOriginalWidth(), height, 0, cursor);

			divide(items, rules, top, bottom, columns, entryWidth, height, cursor);

			cursor += height + gap;
		}

		items.setScrollHeight(Math.max(0, cursor - gap));
	}

	/**
	 * Draws the dividers between one section's columns.
	 *
	 * The game makes one divider per section, enough for the two columns it draws
	 * itself, so the rest are made here and the spares hidden. They are found
	 * again on the next pass like any other, so this settles rather than piling
	 * them up.
	 */
	private void divide(Widget items, List<Widget> rules, int top, int bottom,
		int columns, int entryWidth, int height, int y)
	{
		List<Widget> mine = new ArrayList<>();
		for (Widget rule : rules)
		{
			if (rule.getOriginalY() >= top && rule.getOriginalY() < bottom)
			{
				mine.add(rule);
			}
		}

		if (mine.isEmpty())
		{
			return;
		}

		for (int column = 1; column < columns; column++)
		{
			Widget rule = column <= mine.size() ? mine.get(column - 1) : copyOf(items, mine.get(0));
			if (rule == null)
			{
				return;
			}

			rule.setHidden(false);
			resize(rule, DIVIDER_WIDTH, height, column * entryWidth, y);
		}

		// More dividers than boundaries, which happens when the count drops.
		for (int spare = columns - 1; spare < mine.size(); spare++)
		{
			mine.get(spare).setHidden(true);
		}
	}

	/** A new divider styled like the one the game drew. */
	private Widget copyOf(Widget items, Widget original)
	{
		Widget copy = items.createChild(-1, original.getType());
		if (copy == null)
		{
			return null;
		}

		copy.setTextColor(original.getTextColor());
		copy.setOpacity(original.getOpacity());
		copy.setFilled(original.isFilled());
		return copy;
	}

	/** Puts one entry, and every part of it, at {@code x, y}. */
	private void placeEntry(Widget[] children, int[] entry, int x, int y, int entryWidth,
		int icon, int text, int heartInset)
	{
		for (int i = entry[0]; i < entry[1]; i++)
		{
			Widget child = children[i];
			if (child == null || child.isSelfHidden()
				|| child.getOriginalHeight() > BankLayout.ROW_PITCH
				|| !partOfEntry(child, entry))
			{
				continue;
			}

			int down = child.getOriginalY() - entry[3];

			switch (partOf(child))
			{
				case BLOCK:
					resize(child, entryWidth, child.getOriginalHeight(), x, y);
					break;

				case ICON:
					move(child, x + icon, y + down);
					break;

				case HEART:
					move(child, x + entryWidth - Math.max(0, heartInset), y + down);
					break;

				default:
					resize(child, entryWidth, child.getOriginalHeight(), x + text, y + down);
					break;
			}
		}
	}

	private void move(Widget widget, int x, int y)
	{
		if (widget.getOriginalX() != x || widget.getOriginalY() != y)
		{
			widget.setOriginalX(x);
			widget.setOriginalY(y);
			widget.revalidate();
		}
	}

	private void resize(Widget widget, int width, int height, int x, int y)
	{
		if (widget.getOriginalWidth() != width || widget.getOriginalHeight() != height
			|| widget.getOriginalX() != x || widget.getOriginalY() != y)
		{
			widget.setOriginalWidth(width);
			widget.setOriginalHeight(height);
			widget.setOriginalX(x);
			widget.setOriginalY(y);
			widget.revalidate();
		}
	}

	/**
	 * Whether this child is part of the given entry rather than something
	 * drawn between entries. By its y, which is never written here.
	 */
	/**
	 * How far the favourite heart sits from the right of its entry, or -1 when no
	 * row shows one. Worked out again each pass rather than kept: it depends on
	 * the spacing the entries currently have, and that changes.
	 */
	private int heartInsetFrom(Widget[] children, List<int[]> byRow)
	{
		for (int i = 0; i + 1 < byRow.size(); i++)
		{
			int[] left = byRow.get(i);
			int[] right = byRow.get(i + 1);

			if (left[3] != right[3])
			{
				continue;
			}

			int pitch = right[2] - left[2];
			if (pitch <= 0)
			{
				continue;
			}

			int heart = Math.max(heartOffsetIn(children, left), heartOffsetIn(children, right));
			if (heart > 0 && heart < pitch)
			{
				return pitch - heart;
			}
		}

		return -1;
	}

	/** How far right of its own start an entry's heart sits, or -1 without one. */
	private int heartOffsetIn(Widget[] children, int[] entry)
	{
		for (int i = entry[0]; i < entry[1]; i++)
		{
			Widget child = children[i];
			if (child == null || child.isSelfHidden()
				|| child.getOriginalHeight() > BankLayout.ROW_PITCH)
			{
				continue;
			}

			if (isFavouriteHeart(child))
			{
				return child.getOriginalX() - entry[2];
			}
		}

		return -1;
	}

	private boolean partOfEntry(Widget child, int[] entry)
	{
		return child.getOriginalY() >= entry[3]
			&& child.getOriginalY() < entry[3] + BankLayout.ROW_PITCH;
	}

	/** Reading order: down the rows, then left to right within one. */
	private static final Comparator<int[]> READING_ORDER = (a, b) -> a[3] != b[3]
		? Integer.compare(a[3], b[3])
		: a[2] != b[2] ? Integer.compare(a[2], b[2]) : Integer.compare(a[0], b[0]);

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
