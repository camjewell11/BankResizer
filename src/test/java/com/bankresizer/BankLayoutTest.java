package com.bankresizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * These tests pin the layout maths to the behaviour of the game's own
 * [proc,bankmain_build] (script 277). If the client ever changes that script,
 * these are the assertions that should start failing.
 */
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
	public void offCentreBankIsBoundedByTheNearerEdge()
	{
		// Measured on a live client: the bank sits centred at x=362 on a 940 wide
		// canvas, so it can reach 724 wide, not 940. Bounding by canvas width
		// instead would let the window run off the left edge.
		assertEquals(716, BankLayout.maxWindowWidthFor(362, 940, 4));
	}

	@Test
	public void centredBankGetsNearlyTheWholeCanvas()
	{
		assertEquals(932, BankLayout.maxWindowWidthFor(470, 940, 4));
	}

	@Test
	public void bankNearAnEdgeGetsVeryLittleRoom()
	{
		assertEquals(192, BankLayout.maxWindowWidthFor(100, 940, 4));
	}

	@Test
	public void maxWindowWidthIsNeverNegative()
	{
		// A bank centred hard against an edge must clamp to zero rather than
		// return a negative width that would underflow the column maths.
		assertEquals(0, BankLayout.maxWindowWidthFor(0, 940, 4));
		assertEquals(0, BankLayout.maxWindowWidthFor(940, 940, 4));
		assertEquals(0, BankLayout.maxWindowWidthFor(2, 940, 4));
	}

	@Test
	public void maxWindowWidthIsSymmetricAboutTheCentre()
	{
		for (int offset = 0; offset <= 400; offset += 25)
		{
			assertEquals("offset=" + offset,
				BankLayout.maxWindowWidthFor(470 - offset, 940, 4),
				BankLayout.maxWindowWidthFor(470 + offset, 940, 4));
		}
	}

	@Test
	public void boundedWindowNeverLeavesTheCanvas()
	{
		// The property that actually matters: whatever width we allow, drawing it
		// centred must keep both edges inside the canvas.
		int canvas = 940;
		for (int centre = 0; centre <= canvas; centre += 10)
		{
			int width = BankLayout.maxWindowWidthFor(centre, canvas, 4);
			assertTrue("left edge off canvas at centre=" + centre,
				centre - width / 2 >= 0);
			assertTrue("right edge off canvas at centre=" + centre,
				centre + width / 2 <= canvas);
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
