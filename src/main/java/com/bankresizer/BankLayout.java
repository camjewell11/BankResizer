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

/**
 * Geometry for the bank item grid, mirroring [proc,bankmain_build] (script 277)
 * so a resized grid is spaced exactly as vanilla. That script's column count is
 * a hardcoded local, so the grid has to be laid out again after it runs.
 */
final class BankLayout
{
	/** Left inset of the first item column, from the game script. */
	static final int START_X = 51;

	/** Right inset reserved for the scrollbar. */
	static final int RIGHT_INSET = 35;

	/** Item cell width. Never changed; icons keep their vanilla size. */
	static final int ITEM_WIDTH = 36;

	static final int ITEM_HEIGHT = 32;

	/** Top of one row to the top of the next. */
	static final int ROW_PITCH = 36;

	static final int VANILLA_COLUMNS = 8;

	/**
	 * Most columns the client will draw. Measured: at 28 the bank renders and the
	 * game keeps rebuilding it, at 29 it stops rebuilding and nothing is drawn.
	 * Independent of the room available, seen on play areas of 725 and 2952.
	 */
	static final int MAX_COLUMNS = 28;

	/**
	 * Narrowest a potion store entry may become. Its name sits 38px in, so this
	 * leaves roughly the room vanilla gives it; below that the longer potion
	 * names start to clip, which is worse than scrolling.
	 */
	static final int MIN_POTION_ENTRY_WIDTH = 252;

	/** Item container width the unmodified client sets. */
	static final int VANILLA_CONTAINER_WIDTH = 460;

	static final int VANILLA_PADDING = 12;

	/** Width one extra column costs: one cell plus one gap. */
	static final int COLUMN_PITCH = ITEM_WIDTH + VANILLA_PADDING;

	/** Slack the game adds to a non-empty scroll height. */
	static final int SCROLL_PADDING = 8;


	private BankLayout()
	{
	}

	/** Container width for {@code columns} columns at the vanilla item gap. */
	static int containerWidthFor(int columns)
	{
		return VANILLA_CONTAINER_WIDTH + (columns - VANILLA_COLUMNS) * COLUMN_PITCH;
	}

	/**
	 * Most columns fitting {@code availableWidth}, never fewer than vanilla so a
	 * cramped client falls back to stock rather than a narrower broken grid.
	 */
	static int maxColumnsFor(int availableWidth)
	{
		int extra = (availableWidth - VANILLA_CONTAINER_WIDTH) / COLUMN_PITCH;
		return Math.min(MAX_COLUMNS, Math.max(VANILLA_COLUMNS, VANILLA_COLUMNS + extra));
	}

	/**
	 * Gap between item cells, derived as the game script derives it. Guards the
	 * divide so a single-column grid cannot throw.
	 */
	static int paddingFor(int containerWidth, int columns)
	{
		if (columns <= 1)
		{
			return VANILLA_PADDING;
		}

		int usable = containerWidth - START_X - RIGHT_INSET;
		return (usable - columns * ITEM_WIDTH) / (columns - 1);
	}

	/** X offset of the item in column {@code column}. */
	static int itemX(int column, int padding)
	{
		return START_X + column * (ITEM_WIDTH + padding);
	}

	/** Y offset of the item in row {@code row}. */
	static int itemY(int row)
	{
		return row * ROW_PITCH;
	}

	/** Rows {@code itemCount} items occupy at {@code columns} wide. */
	static int rowsFor(int itemCount, int columns)
	{
		if (itemCount <= 0 || columns <= 0)
		{
			return 0;
		}

		return (itemCount + columns - 1) / columns;
	}

	/** Columns the potion store can hold in {@code width} without clipping names. */
	static int potionColumnsFor(int width)
	{
		return Math.max(2, width / MIN_POTION_ENTRY_WIDTH);
	}

	/** Scroll height for {@code rows} rows, with the game's own slack. */
	static int scrollHeightFor(int rows)
	{
		if (rows <= 0)
		{
			return 0;
		}

		return rows * ROW_PITCH + SCROLL_PADDING;
	}
}
