package com.bankresizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Pins the layout maths to [proc,bankmain_build]; these fail first if it changes. */
public class BankLayoutTest
{
	@Test
	public void vanillaWidthYieldsVanillaPadding()
	{
		// The script derives padding as (width - 51 - 35 - 8 * 36) / 7, which at
		// the stock width of 460 works out to the documented 12px gap.
		int padding = BankLayout.paddingFor(
			BankLayout.VANILLA_CONTAINER_WIDTH, BankLayout.VANILLA_COLUMNS);

		assertEquals(BankLayout.VANILLA_PADDING, padding);
	}

	@Test
	public void vanillaColumnsNeedVanillaWidth()
	{
		assertEquals(BankLayout.VANILLA_CONTAINER_WIDTH,
			BankLayout.containerWidthFor(BankLayout.VANILLA_COLUMNS));
	}

	@Test
	public void widerGridKeepsVanillaPadding()
	{
		// Adding columns must not change the gap between items, otherwise the
		// grid would visibly re-space itself as the count changes.
		for (int columns = 8; columns <= 24; columns++)
		{
			int width = BankLayout.containerWidthFor(columns);
			assertEquals("columns=" + columns,
				BankLayout.VANILLA_PADDING, BankLayout.paddingFor(width, columns));
		}
	}

	@Test
	public void eachColumnCostsOnePitch()
	{
		assertEquals(BankLayout.COLUMN_PITCH,
			BankLayout.containerWidthFor(11) - BankLayout.containerWidthFor(10));
	}

	@Test
	public void maxColumnsNeverDropsBelowVanilla()
	{
		// A cramped or fixed-mode client must fall back to stock behaviour rather
		// than compute a narrower, broken grid.
		assertEquals(BankLayout.VANILLA_COLUMNS, BankLayout.maxColumnsFor(0));
		assertEquals(BankLayout.VANILLA_COLUMNS, BankLayout.maxColumnsFor(100));
		assertEquals(BankLayout.VANILLA_COLUMNS,
			BankLayout.maxColumnsFor(BankLayout.VANILLA_CONTAINER_WIDTH));
	}

	@Test
	public void maxColumnsRoundTripsWithRequiredWidth()
	{
		for (int columns = 8; columns <= 24; columns++)
		{
			int width = BankLayout.containerWidthFor(columns);
			assertEquals("columns=" + columns, columns, BankLayout.maxColumnsFor(width));

			// One pixel short of the requirement must not allow the extra column.
			assertEquals("columns=" + columns,
				columns - 1 < 8 ? 8 : columns - 1, BankLayout.maxColumnsFor(width - 1));
		}
	}

	@Test
	public void firstItemSitsAtScriptOrigin()
	{
		assertEquals(BankLayout.START_X, BankLayout.itemX(0, BankLayout.VANILLA_PADDING));
		assertEquals(0, BankLayout.itemY(0));
	}

	@Test
	public void itemPositionsMatchScriptFormula()
	{
		// x = 51 + column * (36 + padding), y = row * 36
		int padding = BankLayout.VANILLA_PADDING;
		assertEquals(51 + 3 * (36 + padding), BankLayout.itemX(3, padding));
		assertEquals(5 * BankLayout.ROW_PITCH, BankLayout.itemY(5));
	}

	@Test
	public void lastVanillaColumnStaysInsideContainer()
	{
		int padding = BankLayout.VANILLA_PADDING;
		int rightEdge = BankLayout.itemX(BankLayout.VANILLA_COLUMNS - 1, padding)
			+ BankLayout.ITEM_WIDTH;

		assertTrue("last column overflows: " + rightEdge,
			rightEdge <= BankLayout.VANILLA_CONTAINER_WIDTH - BankLayout.RIGHT_INSET);
	}

	@Test
	public void rowsForCountsPartialRows()
	{
		assertEquals(0, BankLayout.rowsFor(0, 10));
		assertEquals(1, BankLayout.rowsFor(1, 10));
		assertEquals(1, BankLayout.rowsFor(10, 10));
		assertEquals(2, BankLayout.rowsFor(11, 10));
	}

	@Test
	public void moreColumnsNeverNeedsMoreRows()
	{
		for (int items = 0; items <= 200; items++)
		{
			assertTrue("items=" + items,
				BankLayout.rowsFor(items, 12) <= BankLayout.rowsFor(items, 8));
		}
	}

	@Test
	public void emptyGridHasNoScroll()
	{
		assertEquals(0, BankLayout.scrollHeightFor(0));
	}

	@Test
	public void scrollHeightAddsScriptSlack()
	{
		assertEquals(4 * BankLayout.ROW_PITCH + BankLayout.SCROLL_PADDING,
			BankLayout.scrollHeightFor(4));
	}

	@Test
	public void singleColumnDoesNotDivideByZero()
	{
		assertEquals(BankLayout.VANILLA_PADDING, BankLayout.paddingFor(460, 1));
		assertEquals(BankLayout.VANILLA_PADDING, BankLayout.paddingFor(460, 0));
	}
}
