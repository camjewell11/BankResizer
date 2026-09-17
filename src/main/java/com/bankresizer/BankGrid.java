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
	/** Column assigned to a separator, which occupies a row of its own. */
	static final int SEPARATOR_COLUMN = -1;

	private BankGrid()
	{
	}

	/** Where one child ends up. */
	static final class Cell
	{
		private final int index;

		private final int row;

		private final int column;

		private Cell(int index, int row, int column)
		{
			this.index = index;
			this.row = row;
			this.column = column;
		}

		int getIndex()
		{
			return index;
		}

		int getRow()
		{
			return row;
		}

		int getColumn()
		{
			return column;
		}

		boolean isSeparator()
		{
			return column == SEPARATOR_COLUMN;
		}
	}

	/** A plan, and the number of rows it needs. */
	static final class Plan
	{
		private final List<Cell> cells;

		private final int rows;

		private Plan(List<Cell> cells, int rows)
		{
			this.cells = cells;
			this.rows = rows;
		}

		List<Cell> getCells()
		{
			return cells;
		}

		int getRows()
		{
			return rows;
		}
	}

	/**
	 * Plans a grid {@code columns} wide from the positions the children already
	 * hold.
	 *
	 * @param xs         current x of each child
	 * @param ys         current y of each child
	 * @param separators whether each child is a separator rather than an item
	 * @param columns    items per row in the new grid
	 */
	static Plan plan(int[] xs, int[] ys, boolean[] separators, int columns)
	{
		if (xs == null || ys == null || separators == null
			|| xs.length != ys.length || xs.length != separators.length)
		{
			return new Plan(new ArrayList<>(), 0);
		}

		int width = Math.max(1, columns);

		List<Integer> items = new ArrayList<>();
		List<Integer> rules = new ArrayList<>();
		for (int i = 0; i < xs.length; i++)
		{
			if (separators[i])
			{
				rules.add(i);
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
		rules.sort((a, b) -> Integer.compare(ys[a], ys[b]));

		List<Cell> cells = new ArrayList<>(xs.length);
		int row = 0;
		int column = 0;
		int next = 0;

		for (int item : items)
		{
			// Any separator the game drew above this item closes the group.
			while (next < rules.size() && ys[rules.get(next)] < ys[item])
			{
				if (column > 0)
				{
					column = 0;
					row++;
				}

				cells.add(new Cell(rules.get(next++), row, SEPARATOR_COLUMN));
				row++;
			}

			cells.add(new Cell(item, row, column));

			if (++column >= width)
			{
				column = 0;
				row++;
			}
		}

		// Separators below every item, which is where a trailing one belongs.
		while (next < rules.size())
		{
			if (column > 0)
			{
				column = 0;
				row++;
			}

			cells.add(new Cell(rules.get(next++), row, SEPARATOR_COLUMN));
			row++;
		}

		if (column > 0)
		{
			row++;
		}

		return new Plan(cells, row);
	}
}
