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
	/** Column given to furniture, which owns the full width of its band. */
	static final int FURNITURE_COLUMN = -1;

	private BankGrid()
	{
	}

	/** Where one child ends up, in pixels rather than rows. */
	static final class Cell
	{
		private final int index;

		private final int y;

		private final int column;

		private Cell(int index, int y, int column)
		{
			this.index = index;
			this.y = y;
			this.column = column;
		}

		int getIndex()
		{
			return index;
		}

		int getY()
		{
			return y;
		}

		int getColumn()
		{
			return column;
		}

		boolean isFurniture()
		{
			return column == FURNITURE_COLUMN;
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

	/**
	 * Plans a grid {@code columns} wide from the positions the children already
	 * hold.
	 *
	 * @param xs        current x of each child
	 * @param ys        current y of each child
	 * @param furniture whether each child spans its band rather than being an item
	 * @param columns   items per row in the new grid
	 */
	static Plan plan(int[] xs, int[] ys, boolean[] furniture, int columns)
	{
		if (xs == null || ys == null || furniture == null
			|| xs.length != ys.length || xs.length != furniture.length)
		{
			return new Plan(new ArrayList<>(), 0);
		}

		int width = Math.max(1, columns);

		List<Integer> items = new ArrayList<>();
		List<Integer> extras = new ArrayList<>();
		for (int i = 0; i < xs.length; i++)
		{
			if (furniture[i])
			{
				extras.add(i);
			}
			else
			{
				items.add(i);
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
					cells.add(new Cell(extra, y - (groupY - ys[extra]), FURNITURE_COLUMN));
				}

				pending.clear();
			}

			cells.add(new Cell(item, y, column));
			lastRowY = ys[item];

			if (++column >= width)
			{
				column = 0;
				y += BankLayout.ROW_PITCH;
			}
		}

		if (column > 0)
		{
			column = 0;
			y += BankLayout.ROW_PITCH;
		}

		// Furniture below every item, which is where a trailing rule belongs.
		while (next < extras.size())
		{
			pending.add(extras.get(next++));
		}

		if (!pending.isEmpty())
		{
			for (int extra : pending)
			{
				cells.add(new Cell(extra, y, FURNITURE_COLUMN));
			}

			y += BankLayout.ROW_PITCH;
		}

		return new Plan(cells, y);
	}
}
