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
 * Pure geometry for the bank item grid.
 *
 * Every constant and formula here mirrors the game's own [proc,bankmain_build]
 * (script 277) so that a resized grid is laid out exactly the way the vanilla
 * client would lay it out, only with a different column count. The relevant
 * lines of that script are:
 *
 * <pre>
 *   def_int $int22 = calc(8 - 1);                               // columns - 1
 *   def_int $int23 = calc(if_getwidth($component2) - 51 - 35);  // usable width
 *   def_int $int24 = calc(($int23 - 8 * 36) / $int22);          // x padding
 *   cc_setposition(calc(51 + $int34 * (36 + $int24)), calc($int35 * 36), ...)
 * </pre>
 *
 * The column count is a hardcoded local in that script, which is why it cannot
 * be overridden through script arguments and the grid must be laid out again
 * after the script has run.
 */
final class BankLayout
{
	/** Left inset of the first item column, from the game script. */
	static final int START_X = 51;

	/** Right inset reserved for the scrollbar, from the game script. */
	static final int RIGHT_INSET = 35;

	/** Item cell width. Never changed; item sprites keep their vanilla size. */
	static final int ITEM_WIDTH = 36;

	/** Item cell height. Never changed. */
	static final int ITEM_HEIGHT = 32;

	/** Vertical distance between the top of one row and the top of the next. */
	static final int ROW_PITCH = 36;

	/** Columns the unmodified client uses. */
	static final int VANILLA_COLUMNS = 8;

	/** Item container width the unmodified client sets, in absolute pixels. */
	static final int VANILLA_CONTAINER_WIDTH = 460;

	/** Horizontal padding the vanilla width and column count work out to. */
	static final int VANILLA_PADDING = 12;

	/** Width one extra column costs: one cell plus one gap. */
	static final int COLUMN_PITCH = ITEM_WIDTH + VANILLA_PADDING;

	/** Slack the game adds to a non-empty scroll height. */
	static final int SCROLL_PADDING = 8;


	private BankLayout()
	{
	}

	/**
	 * Container width needed to show {@code columns} columns while keeping the
	 * vanilla gap between items.
	 */
	static int containerWidthFor(int columns)
	{
		return VANILLA_CONTAINER_WIDTH + (columns - VANILLA_COLUMNS) * COLUMN_PITCH;
	}

	/**
	 * Most columns that fit in {@code availableWidth} pixels of container. Never
	 * returns fewer than the vanilla count, so a cramped client falls back to
	 * stock behaviour rather than a broken narrower grid.
	 */
	static int maxColumnsFor(int availableWidth)
	{
		int extra = (availableWidth - VANILLA_CONTAINER_WIDTH) / COLUMN_PITCH;
		return Math.max(VANILLA_COLUMNS, VANILLA_COLUMNS + extra);
	}

	/**
	 * Widest the bank window can be drawn without any part of it leaving the
	 * canvas.
	 *
	 * The window is centre anchored, so it grows equally in both directions and
	 * its centre does not move. The binding constraint is therefore the distance
	 * from that centre to the nearer edge, doubled. A bank centred at 362 on a
	 * 940 wide canvas can reach 724, not 940.
	 *
	 * @param centreX      horizontal centre of the bank window, in canvas pixels
	 * @param canvasWidth  width of the game canvas
	 * @param margin       pixels to keep clear at the nearer edge
	 */
	static int maxWindowWidthFor(int centreX, int canvasWidth, int margin)
	{
		int half = Math.min(centreX, canvasWidth - centreX) - margin;
		return Math.max(0, 2 * half);
	}

	/**
	 * Horizontal gap between item cells, derived from the container width the
	 * same way the game script derives it. Guards the divide so a single-column
	 * grid cannot throw.
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

	/** Number of rows {@code itemCount} items occupy at {@code columns} wide. */
	static int rowsFor(int itemCount, int columns)
	{
		if (itemCount <= 0 || columns <= 0)
		{
			return 0;
		}

		return (itemCount + columns - 1) / columns;
	}

	/**
	 * Scroll height for a grid of {@code rows} rows, matching the slack the game
	 * adds in [proc,bankmain_finishbuilding].
	 */
	static int scrollHeightFor(int rows)
	{
		if (rows <= 0)
		{
			return 0;
		}

		return rows * ROW_PITCH + SCROLL_PADDING;
	}
}
