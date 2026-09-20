package com.bankresizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertTrue;
import net.runelite.api.widgets.WidgetSizeMode;
import org.junit.Test;

/** Pins the ancestor rule to a chain measured on a live client. */
public class BankChainPlanTest
{
	private static final int ABS = WidgetSizeMode.ABSOLUTE;

	private static final int MINUS = WidgetSizeMode.MINUS;

	private static final int CANVAS = 940;

	/** Taller than any ancestor in these chains, so only width decides. */
	private static final int NO_HEIGHT = Integer.MAX_VALUE;

	/** The real chain: window, tracking root, fixed slot, play area, canvas. */
	private static BankChainPlan live()
	{
		return BankChainPlan.of(
			new int[]{488, 512, 512, 725, 940},
			new int[]{0, 0, 0, 0, 0},
			new int[]{ABS, MINUS, ABS, MINUS, ABS},
			CANVAS, NO_HEIGHT);
	}

	@Test
	public void stopsAtTheFullCanvasRoot()
	{
		// Widening the canvas-wide ancestor would push the bank out over the side
		// panel, so it is not in play.
		assertEquals(4, live().getEnd());
	}

	@Test
	public void findsTheFixedSlotThatWasClipping()
	{
		// Index 2 is the 512 wide slot. Leaving it behind is what clipped the
		// widened window and made the bank look like it had shifted left.
		assertEquals(2, live().getHighestSlot());
	}

	@Test
	public void picksThePlayAreaAsTheLimit()
	{
		// Index 3 at 725, the canvas less the side panel, not the 940 canvas.
		assertEquals(3, live().getViewport());
	}

	@Test
	public void theLimitIsAboveEveryWidenedSlot()
	{
		// Otherwise we would be bounded by something we are about to resize.
		BankChainPlan plan = live();
		assertTrue(plan.getViewport() > plan.getHighestSlot());
		assertTrue(plan.getViewport() < plan.getEnd());
	}

	@Test
	public void trackingAncestorsAreNeverTreatedAsSlots()
	{
		// A chain with no fixed ancestor needs nothing widened above the window.
		BankChainPlan plan = BankChainPlan.of(
			new int[]{488, 512, 725, 940},
			new int[]{0, 0, 0, 0},
			new int[]{MINUS, MINUS, MINUS, ABS},
			CANVAS, NO_HEIGHT);

		assertEquals(-1, plan.getHighestSlot());
	}

	@Test
	public void handlesAChainWithNothingAboveTheWindow()
	{
		BankChainPlan plan = BankChainPlan.of(
			new int[]{488},
			new int[]{0},
			new int[]{ABS},
			CANVAS, NO_HEIGHT);

		assertEquals(0, plan.getHighestSlot());
		assertEquals(-1, plan.getViewport());
	}

	@Test
	public void aWindowAlreadyFillingTheCanvasIsNotInPlay()
	{
		BankChainPlan plan = BankChainPlan.of(
			new int[]{940, 940},
			new int[]{0, 0},
			new int[]{ABS, ABS},
			CANVAS, NO_HEIGHT);

		assertEquals(0, plan.getEnd());
		assertEquals(-1, plan.getHighestSlot());
	}

	@Test
	public void picksTheNarrowestTrackingAncestorNotTheFirst()
	{
		// The tightest constraint wins, wherever it sits in the chain.
		BankChainPlan plan = BankChainPlan.of(
			new int[]{488, 512, 800, 700, 940},
			new int[]{0, 0, 0, 0, 0},
			new int[]{ABS, ABS, MINUS, MINUS, ABS},
			CANVAS, NO_HEIGHT);

		assertEquals(3, plan.getViewport());
	}

	@Test
	public void readsTheOrdinaryChain()
	{
		// Measured: bank window, its holder, the slot, the play area, the screen.
		BankChainPlan plan = BankChainPlan.of(
			new int[]{488, 512, 512, 691, 941},
			new int[]{330, 334, 334, 550, 715},
			new int[]{ABS, MINUS, ABS, MINUS, MINUS},
			941, 715);

		assertEquals(4, plan.getEnd());
		assertEquals(2, plan.getHighestSlot());
		assertEquals(3, plan.getViewport());
	}

	@Test
	public void readsAChainWithAFullHeightContainerAboveThePlayArea()
	{
		// Measured with a plugin that restyles the interface. The container above
		// the play area is no wider than it but as tall as the whole client, and
		// taking it for a slot left nothing to bound the bank, so the bank could
		// only ever be its own width and dropped back to eight columns.
		BankChainPlan plan = BankChainPlan.of(
			new int[]{488, 512, 512, 692, 692},
			new int[]{330, 334, 334, 550, 715},
			new int[]{ABS, MINUS, ABS, MINUS, ABS},
			941, 715);

		assertEquals(4, plan.getEnd());
		assertEquals(2, plan.getHighestSlot());
		assertEquals(3, plan.getViewport());
	}

	@Test
	public void knowsTheOrdinaryChainIsNotBoxed()
	{
		BankChainPlan plan = BankChainPlan.of(
			new int[]{488, 512, 512, 691, 941},
			new int[]{330, 334, 334, 550, 715},
			new int[]{ABS, MINUS, ABS, MINUS, MINUS},
			941, 715);

		assertFalse(plan.isBoxed());
	}

	@Test
	public void spotsABoxAsTallAsTheClientButNarrowerThanIt()
	{
		// Measured with a plugin that restyles the interface. It keeps the bank
		// window at its own width, so there is no point widening it.
		BankChainPlan plan = BankChainPlan.of(
			new int[]{488, 512, 512, 692, 692},
			new int[]{330, 334, 334, 550, 715},
			new int[]{ABS, MINUS, ABS, MINUS, ABS},
			941, 715);

		assertTrue(plan.isBoxed());
	}

	@Test
	public void toleratesMissingOrMismatchedInput()
	{
		assertEquals(0, BankChainPlan.of(null, null, null, CANVAS, NO_HEIGHT).getEnd());
		assertEquals(0, BankChainPlan.of(
			new int[]{1},
			new int[]{0},
			new int[]{ABS, ABS},
			CANVAS, NO_HEIGHT).getEnd());
		assertEquals(-1, BankChainPlan.of(new int[0], null, new int[0], CANVAS, NO_HEIGHT).getHighestSlot());
	}
}
