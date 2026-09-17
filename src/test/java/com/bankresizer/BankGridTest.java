package com.bankresizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.List;
import org.junit.Test;

/**
 * Pins the ordering rule that the "view all items" tab exposed: the item
 * container's child order is not its on screen order, and separators are held at
 * the end of the array rather than between the groups they divide.
 */
public class BankGridTest
{
	private static BankGrid.Cell cellFor(BankGrid.Plan plan, int index)
	{
		for (BankGrid.Cell cell : plan.getCells())
		{
			if (cell.getIndex() == index)
			{
				return cell;
			}
		}

		throw new AssertionError("no cell for child " + index);
	}

	@Test
	public void ordersByPositionRatherThanChildIndex()
	{
		// Child 0 is drawn below child 1, as happens on the view all tab.
		BankGrid.Plan plan = BankGrid.plan(
			new int[]{51, 51},
			new int[]{804, 0},
			new boolean[]{false, false},
			8);

		// Both fit on row 0 at eight columns, so the order shows in the column:
		// the child drawn higher up is laid out first despite its later index.
		assertEquals(0, cellFor(plan, 1).getColumn());
		assertEquals(1, cellFor(plan, 0).getColumn());
	}

	@Test
	public void readsLeftToRightWithinARow()
	{
		BankGrid.Plan plan = BankGrid.plan(
			new int[]{99, 51},
			new int[]{0, 0},
			new boolean[]{false, false},
			8);

		assertEquals(0, cellFor(plan, 1).getColumn());
		assertEquals(1, cellFor(plan, 0).getColumn());
	}

	@Test
	public void wrapsAtTheColumnCount()
	{
		int[] xs = new int[10];
		int[] ys = new int[10];
		for (int i = 0; i < 10; i++)
		{
			xs[i] = i * 48;
		}

		BankGrid.Plan plan = BankGrid.plan(xs, ys, new boolean[10], 8);

		assertEquals(0, cellFor(plan, 7).getY());
		assertEquals(BankLayout.ROW_PITCH, cellFor(plan, 8).getY());
		assertEquals(0, cellFor(plan, 8).getColumn());
		assertEquals(2 * BankLayout.ROW_PITCH, plan.getHeight());
	}

	@Test
	public void separatorHeldAtTheEndOfTheArrayLandsBetweenItsGroups()
	{
		// The live shape: items at 0 and 1, the separator last, and its y says it
		// belongs between them rather than after both.
		BankGrid.Plan plan = BankGrid.plan(
			new int[]{51, 51, 51},
			new int[]{0, 804, 797},
			new boolean[]{false, false, true},
			8);

		assertEquals(0, cellFor(plan, 0).getY());
		assertTrue(cellFor(plan, 2).getY() > 0);
		assertTrue(cellFor(plan, 1).getY() > cellFor(plan, 2).getY());
		assertTrue(cellFor(plan, 2).isFurniture());
	}

	@Test
	public void separatorClosesAPartFilledRow()
	{
		// Three items then a separator, at eight columns: the separator must not
		// share the row the items were still filling.
		BankGrid.Plan plan = BankGrid.plan(
			new int[]{51, 99, 147, 51},
			new int[]{0, 0, 0, 10},
			new boolean[]{false, false, false, true},
			8);

		assertEquals(0, cellFor(plan, 2).getY());
		assertTrue(cellFor(plan, 3).getY() >= BankLayout.ROW_PITCH);
	}

	@Test
	public void separatorBelowEverythingStaysAtTheBottom()
	{
		BankGrid.Plan plan = BankGrid.plan(
			new int[]{51, 51},
			new int[]{0, 900},
			new boolean[]{false, true},
			8);

		assertEquals(0, cellFor(plan, 0).getY());
		assertEquals(BankLayout.ROW_PITCH, cellFor(plan, 1).getY());
		assertEquals(2 * BankLayout.ROW_PITCH, plan.getHeight());
	}

	@Test
	public void everyChildIsPlacedExactlyOnce()
	{
		int[] xs = {51, 99, 147, 51, 51};
		int[] ys = {0, 0, 0, 100, 50};
		boolean[] seps = {false, false, false, false, true};

		List<BankGrid.Cell> cells = BankGrid.plan(xs, ys, seps, 8).getCells();

		assertEquals(5, cells.size());
		for (int i = 0; i < 5; i++)
		{
			cellFor(BankGrid.plan(xs, ys, seps, 8), i);
		}
	}

	@Test
	public void widerGridUsesFewerRows()
	{
		int[] xs = new int[20];
		int[] ys = new int[20];
		for (int i = 0; i < 20; i++)
		{
			xs[i] = (i % 8) * 48;
			ys[i] = (i / 8) * 36;
		}

		assertTrue(BankGrid.plan(xs, ys, new boolean[20], 12).getHeight()
			< BankGrid.plan(xs, ys, new boolean[20], 8).getHeight());
	}

	@Test
	public void reproducesTheGamesOwnGapAtAGroupBoundary()
	{
		// The live shape. One row of a group ends at y=756, the rule sits at 797
		// and the next group starts at 804, so the boundary costs one row step plus
		// 12px of slack, not a whole extra row.
		BankGrid.Plan plan = BankGrid.plan(
			new int[]{51, 51, 51},
			new int[]{756, 804, 797},
			new boolean[]{false, false, true},
			8);

		int firstRow = cellFor(plan, 0).getY();
		int nextGroup = cellFor(plan, 1).getY();

		assertEquals(0, firstRow);
		assertEquals(BankLayout.ROW_PITCH + 12, nextGroup);
		assertEquals(nextGroup - 7, cellFor(plan, 2).getY());
	}

	@Test
	public void furnitureKeepsItsOffsetAboveTheGroupItIntroduces()
	{
		// A rule and a heading drawn at different heights above the same group keep
		// their spacing relative to it rather than each being given a row.
		BankGrid.Plan plan = BankGrid.plan(
			new int[]{51, 51, 51, 51},
			new int[]{756, 804, 797, 799},
			new boolean[]{false, false, true, true},
			8);

		int nextGroup = cellFor(plan, 1).getY();

		assertEquals(nextGroup - 7, cellFor(plan, 2).getY());
		assertEquals(nextGroup - 5, cellFor(plan, 3).getY());
	}

	@Test
	public void noBoundaryAddsNoSlack()
	{
		// Two plain rows a row pitch apart stay a row pitch apart.
		BankGrid.Plan plan = BankGrid.plan(
			new int[]{51, 51},
			new int[]{0, BankLayout.ROW_PITCH},
			new boolean[]{false, false},
			1);

		assertEquals(0, cellFor(plan, 0).getY());
		assertEquals(BankLayout.ROW_PITCH, cellFor(plan, 1).getY());
	}

	@Test
	public void toleratesEmptyAndMismatchedInput()
	{
		assertEquals(0, BankGrid.plan(new int[0], new int[0], new boolean[0], 8).getHeight());
		assertEquals(0, BankGrid.plan(null, null, null, 8).getHeight());
		assertEquals(0, BankGrid.plan(new int[]{1}, new int[]{1, 2}, new boolean[]{false}, 8).getHeight());
	}

	@Test
	public void aZeroColumnGridDoesNotHang()
	{
		BankGrid.Plan plan = BankGrid.plan(
			new int[]{51, 99},
			new int[]{0, 0},
			new boolean[]{false, false},
			0);

		assertEquals(2 * BankLayout.ROW_PITCH, plan.getHeight());
	}
}
