package com.bankresizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import net.runelite.api.widgets.WidgetSizeMode;
import org.junit.Test;

/** Pins the ancestor rule to a chain measured on a live client. */
public class BankChainPlanTest
{
	private static final int ABS = WidgetSizeMode.ABSOLUTE;

	private static final int MINUS = WidgetSizeMode.MINUS;

	private static final int CANVAS = 940;

	/** The real chain: window, tracking root, fixed slot, play area, canvas. */
	private static BankChainPlan live()
	{
		return BankChainPlan.of(
			new int[]{488, 512, 512, 725, 940},
			new int[]{ABS, MINUS, ABS, MINUS, ABS},
			CANVAS);
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
			new int[]{MINUS, MINUS, MINUS, ABS},
			CANVAS);

		assertEquals(-1, plan.getHighestSlot());
	}

	@Test
	public void handlesAChainWithNothingAboveTheWindow()
	{
		BankChainPlan plan = BankChainPlan.of(
			new int[]{488},
			new int[]{ABS},
			CANVAS);

		assertEquals(0, plan.getHighestSlot());
		assertEquals(-1, plan.getViewport());
	}

	@Test
	public void aWindowAlreadyFillingTheCanvasIsNotInPlay()
	{
		BankChainPlan plan = BankChainPlan.of(
			new int[]{940, 940},
			new int[]{ABS, ABS},
			CANVAS);

		assertEquals(0, plan.getEnd());
		assertEquals(-1, plan.getHighestSlot());
	}

	@Test
	public void picksTheNarrowestTrackingAncestorNotTheFirst()
	{
		// The tightest constraint wins, wherever it sits in the chain.
		BankChainPlan plan = BankChainPlan.of(
			new int[]{488, 512, 800, 700, 940},
			new int[]{ABS, ABS, MINUS, MINUS, ABS},
			CANVAS);

		assertEquals(3, plan.getViewport());
	}

	@Test
	public void toleratesMissingOrMismatchedInput()
	{
		assertEquals(0, BankChainPlan.of(null, null, CANVAS).getEnd());
		assertEquals(0, BankChainPlan.of(new int[]{1}, new int[]{ABS, ABS}, CANVAS).getEnd());
		assertEquals(-1, BankChainPlan.of(new int[0], new int[0], CANVAS).getHighestSlot());
	}
}
