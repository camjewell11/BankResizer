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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Works out which row and column each bank item should move to.
 *
 * The one thing this exists to get right is that <b>the order of the children in
 * the item container is not the order they appear on screen</b>. Laying them out
 * in array order was wrong, and it was wrong in a way that was invisible on an
 * ordinary tab and obvious on "view all items". Measured on a live client:
 *
 * <pre>
 *   child 0     an item, at y=804
 *   child 1410  a separator, 2px tall and 374 wide, at y=797
 * </pre>
 *
 * Child 0 is drawn below the first separator, so the group the game puts at the
 * top of the tab is held in children with much higher indices. Re-flowing in
 * array order moved that whole group to the bottom of a scroll more than a
 * thousand items long, which read as the first group having gone missing.
 *
 * The existing positions are therefore the only record of the intended order,
 * and this sorts by them. Separators are not interleaved with the items at all;
 * they sit at the end of the array and are placed purely by their y, so they are
 * merged back in by comparing that y against the items around them.
 */
final class BankGrid
{
	/** What a child in the item container is. */
	enum Kind
	{
		/** An ordinary item cell. */
		ITEM,
		/** A thin rule dividing two groups, spanning the grid. */
		RULE,
		/**
		 * A block covering the unused cells at the end of a group's last row.
		 *
		 * Measured live, these are item height, carry no item, and are exactly
		 * {@code n * 48 - 12} wide for the n cells they cover: 84 for two, 180 for
		 * four, 324 for seven. How many cells are spare depends on the column
		 * count, so a filler cannot be moved, only worked out again.
		 */
		FILLER
	}

	private BankGrid()
	{
	}

	/** Where one child ends up. */
	static final class Cell
	{
		private final int index;

		private final Kind kind;

		private final int y;

		private final int column;

		private final int span;

		private Cell(int index, Kind kind, int y, int column, int span)
		{
			this.index = index;
			this.kind = kind;
			this.y = y;
			this.column = column;
			this.span = span;
		}

		int getIndex()
		{
			return index;
		}

		Kind getKind()
		{
			return kind;
		}

		int getY()
		{
			return y;
		}

		int getColumn()
		{
			return column;
		}

		/** Cells covered, for a filler. Zero when the row came out full. */
		int getSpan()
		{
			return span;
		}
	}

	/** A plan, and the height in pixels it occupies. */
	static final class Plan
	{
		private final List<Cell> cells;

		private final int height;

		private Plan(List<Cell> cells, int height)
		{
			this.cells = cells;
			this.height = height;
		}

		List<Cell> getCells()
		{
			return cells;
		}

		int getHeight()
		{
			return height;
		}
	}

	/** What a child of the given size is. */
	static Kind kindOf(int width, int height)
	{
		if (height < BankLayout.ITEM_HEIGHT)
		{
			return Kind.RULE;
		}

		return width == BankLayout.ITEM_WIDTH ? Kind.ITEM : Kind.FILLER;
	}

	/**
	 * Plans a grid {@code columns} wide from the positions and sizes the children
	 * already hold.
	 */
	static Plan plan(int[] xs, int[] ys, int[] widths, int[] heights, int columns)
	{
		if (xs == null || ys == null || widths == null || heights == null
			|| xs.length != ys.length || xs.length != widths.length
			|| xs.length != heights.length)
		{
			return new Plan(new ArrayList<>(), 0);
		}

		int width = Math.max(1, columns);

		List<Integer> items = new ArrayList<>();
		List<Integer> extras = new ArrayList<>();
		for (int i = 0; i < xs.length; i++)
		{
			if (kindOf(widths[i], heights[i]) == Kind.ITEM)
			{
				items.add(i);
			}
			else
			{
				extras.add(i);
			}
		}

		// Reading order, which is the only place the intended order survives.
		items.sort((a, b) -> ys[a] != ys[b]
			? Integer.compare(ys[a], ys[b])
			: Integer.compare(xs[a], xs[b]));
		extras.sort((a, b) -> ys[a] != ys[b]
			? Integer.compare(ys[a], ys[b])
			: Integer.compare(xs[a], xs[b]));

		List<Cell> cells = new ArrayList<>(xs.length);
		List<Integer> pending = new ArrayList<>();
		int y = 0;
		int column = 0;
		int next = 0;
		int lastRowY = Integer.MIN_VALUE;

		for (int item : items)
		{
			while (next < extras.size() && ys[extras.get(next)] < ys[item])
			{
				pending.add(extras.get(next++));
			}

			if (!pending.isEmpty())
			{
				column = flush(cells, pending, xs, ys, widths, heights, y, column, width,
					lastRowY != Integer.MIN_VALUE);

				if (column > 0)
				{
					column = 0;
					y += BankLayout.ROW_PITCH;
				}

				// Reproduce the gap the game left for this boundary rather than
				// spending a whole row on it. A row step is already in y, so only
				// the slack beyond one step is added.
				int groupY = ys[item];
				if (lastRowY != Integer.MIN_VALUE)
				{
					y += Math.max(0, groupY - lastRowY - BankLayout.ROW_PITCH);
				}

				int shift = 0;
				for (int extra : pending)
				{
					shift = Math.max(shift, groupY - ys[extra] - y);
				}

				y += Math.max(0, shift);

				for (int extra : pending)
				{
					cells.add(new Cell(extra, Kind.RULE, y - (groupY - ys[extra]), -1, 0));
				}

				pending.clear();
			}

			cells.add(new Cell(item, Kind.ITEM, y, column, 1));
			lastRowY = ys[item];

			if (++column >= width)
			{
				column = 0;
				y += BankLayout.ROW_PITCH;
			}
		}

		while (next < extras.size())
		{
			pending.add(extras.get(next++));
		}

		if (!pending.isEmpty())
		{
			column = flush(cells, pending, xs, ys, widths, heights, y, column, width,
				lastRowY != Integer.MIN_VALUE);

			if (column > 0)
			{
				column = 0;
				y += BankLayout.ROW_PITCH;
			}

			for (int extra : pending)
			{
				cells.add(new Cell(extra, Kind.RULE, y, -1, 0));
			}

			if (!pending.isEmpty())
			{
				y += BankLayout.ROW_PITCH;
			}

			pending.clear();
		}
		else if (column > 0)
		{
			y += BankLayout.ROW_PITCH;
		}

		return new Plan(cells, y);
	}

	/**
	 * Places any fillers waiting in {@code pending} across the spare cells of the
	 * row in progress, and drops them from the list so only rules are left.
	 */
	private static int flush(List<Cell> cells, List<Integer> pending, int[] xs, int[] ys,
		int[] widths, int[] heights, int y, int column, int width, boolean anyPlaced)
	{
		// A row that came out exactly full has already wrapped the column back to
		// zero, which otherwise looks the same as the start of a fresh row. Once
		// any item has been placed, a zero column means the row before it was
		// full, so there is nothing spare and the block collapses.
		int spare = column == 0 && anyPlaced ? 0 : Math.max(0, width - column);

		for (Iterator<Integer> it = pending.iterator(); it.hasNext(); )
		{
			int extra = it.next();
			if (kindOf(widths[extra], heights[extra]) != Kind.FILLER)
			{
				continue;
			}

			cells.add(new Cell(extra, Kind.FILLER, y, column, spare));
			it.remove();
		}

		return column;
	}
}
