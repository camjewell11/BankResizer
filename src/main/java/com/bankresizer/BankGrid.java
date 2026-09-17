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
 * Where each bank item moves to, in pixels. The container's child order is not
 * its on screen order, so ordering comes from the positions the children already
 * hold: on "view all items" child 0 sits at y=804, below a separator at y=797.
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
		 * A block covering the unused cells at the end of a group's last row. How
		 * many are spare depends on the column count, so it is worked out again
		 * rather than moved. Live widths are n * 48 - 12 for the n cells covered.
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

	/**
	 * What a child of this item id and height is. Keyed on item id, not width:
	 * a filler covering one cell is resized to an item's 36 wide, so a width
	 * test would promote it to an item on the next pass.
	 */
	static Kind kindOf(int itemId, int height)
	{
		if (height < BankLayout.ITEM_HEIGHT)
		{
			return Kind.RULE;
		}

		return itemId == NO_ITEM ? Kind.FILLER : Kind.ITEM;
	}

	/** Item id of a child that holds no item. */
	static final int NO_ITEM = -1;

	/** Plans a grid {@code columns} wide from the positions and sizes the
	 * children already hold. */
	static Plan plan(int[] xs, int[] ys, int[] itemIds, int[] heights, int columns)
	{
		if (xs == null || ys == null || itemIds == null || heights == null
			|| xs.length != ys.length || xs.length != itemIds.length
			|| xs.length != heights.length)
		{
			return new Plan(new ArrayList<>(), 0);
		}

		int width = Math.max(1, columns);

		List<Integer> items = new ArrayList<>();
		List<Integer> extras = new ArrayList<>();
		for (int i = 0; i < xs.length; i++)
		{
			if (kindOf(itemIds[i], heights[i]) == Kind.ITEM)
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
				column = flush(cells, pending, xs, ys, itemIds, heights, y, column, width,
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
			column = flush(cells, pending, xs, ys, itemIds, heights, y, column, width,
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
		int[] itemIds, int[] heights, int y, int column, int width, boolean anyPlaced)
	{
		// A full row has already wrapped the column to zero, which looks like the
		// start of a fresh one. Once an item is placed, a zero column means the
		// row before was full, so nothing is spare and the block collapses.
		int spare = column == 0 && anyPlaced ? 0 : Math.max(0, width - column);

		for (Iterator<Integer> it = pending.iterator(); it.hasNext(); )
		{
			int extra = it.next();
			if (kindOf(itemIds[extra], heights[extra]) != Kind.FILLER)
			{
				continue;
			}

			cells.add(new Cell(extra, Kind.FILLER, y, column, spare));
			it.remove();
		}

		return column;
	}
}
