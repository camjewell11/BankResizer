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

import net.runelite.api.widgets.WidgetSizeMode;

/**
 * Which ancestors of the bank window must be resized, from widths and modes
 * alone. Split from {@link BankRoom} so the rule is testable without a fake
 * tree. Live chain: 488 abs, 512 minus, 512 abs slot, 725 minus play area.
 */
final class BankChainPlan
{
	/** One past the last ancestor in play; the full-canvas root and above. */
	private final int end;

	/** Index of the outermost fixed slot, or -1 when there is none. */
	private final int highestSlot;

	/** Index of the narrowest tracking ancestor above the slots, or -1. */
	private final int viewport;

	/**
	 * Whether the chain stopped at something as tall as the canvas but narrower
	 * than it. The game does not build the bank that way; a plugin that restyles
	 * the interface does, and it keeps the bank window at its own width.
	 */
	private final boolean boxed;

	private BankChainPlan(int end, int highestSlot, int viewport, boolean boxed)
	{
		this.end = end;
		this.highestSlot = highestSlot;
		this.viewport = viewport;
		this.boxed = boxed;
	}

	boolean isBoxed()
	{
		return boxed;
	}
	int getEnd()
	{
		return end;
	}

	int getHighestSlot()
	{
		return highestSlot;
	}

	int getViewport()
	{
		return viewport;
	}

	/**
	 * @param widths       rendered width of each ancestor, innermost first
	 * @param heights      rendered height of each, or null when not known
	 * @param modes        width mode of each, matching {@code widths}
	 * @param canvasWidth  width of the game canvas, used to spot the screen
	 * @param canvasHeight height of the game canvas, used for the same
	 */
	static BankChainPlan of(int[] widths, int[] heights, int[] modes,
		int canvasWidth, int canvasHeight)
	{
		if (widths == null || modes == null || widths.length != modes.length)
		{
			return new BankChainPlan(0, -1, -1, false);
		}
		// Stop at the first full-canvas ancestor. That one is the screen rather
		// than a slot, and widening it would push the bank out over the side panel.
		//
		// It counts as the screen when it fills the canvas either way. Width alone
		// misses the containers a restyled interface puts in: one of those is no
		// wider than the play area but as tall as the whole client, and taking it
		// for a slot swallowed the play area, leaving nothing to bound the bank.
		// How it was sized says nothing either, so only the size is asked about.
		int end = widths.length;
		boolean boxed = false;
		for (int i = 0; i < widths.length; i++)
		{
			boolean fillsWidth = widths[i] >= canvasWidth;
			boolean fillsHeight = heights != null && i < heights.length
				&& canvasHeight > 0 && heights[i] >= canvasHeight;

			if (fillsWidth || fillsHeight)
			{
				end = i;
				boxed = fillsHeight && !fillsWidth;
				break;
			}
		}		// Fixed-width ancestors clip, so they have to grow with the window.
		int highestSlot = -1;
		for (int i = 0; i < end; i++)
		{
			if (modes[i] == WidgetSizeMode.ABSOLUTE)
			{
				highestSlot = i;
			}
		}

		// Only tracking ancestors above the outermost slot constrain us. Ones below
		// sit inside something being widened, so they follow along.
		int viewport = -1;
		int narrowest = Integer.MAX_VALUE;
		for (int i = highestSlot + 1; i < end; i++)
		{
			if (widths[i] < narrowest)
			{
				narrowest = widths[i];
				viewport = i;
			}
		}

		return new BankChainPlan(end, highestSlot, viewport, boxed);
	}
}
