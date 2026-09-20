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
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;

/**
 * How much room the bank window has to grow, and which ancestors must grow with
 * it. The bank sits in a fixed slot inside the play area, not on the canvas, so
 * the chain above it decides both the bound and what would clip a wider window.
 */
final class BankRoom
{
	/** Ancestors with an absolute width, which have to be widened by hand. */
	private final List<Widget> slots;

	/** Window and ancestors in play, innermost first. Resize in reverse, so a
	 * parent is sized before its children recompute from it. */
	private final List<Widget> chain;

	/**
	 * The play area: the first ancestor above the slots, which keeps its own size.
	 * Measured live at 725x550 on a 940x715 canvas, the canvas less the side panel
	 * and chatbox. Bounding by it keeps a wider bank off both.
	 */
	private final Widget viewport;

	/** Widest the bank window may become before an ancestor would clip it. */
	private final int limit;

	/** Whether the bank sits in a box another plugin keeps to its own size. */
	private final boolean boxed;

	private BankRoom(List<Widget> slots, List<Widget> chain, Widget viewport, int limit,
		boolean boxed)
	{
		this.slots = slots;
		this.chain = chain;
		this.viewport = viewport;
		this.limit = limit;
		this.boxed = boxed;
	}

	@Nullable
	Widget getViewport()
	{
		return viewport;
	}

	/** Tallest the bank window may become, or 0 if the play area is unknown. */
	int getHeightLimit()
	{
		return viewport == null ? 0 : viewport.getHeight();
	}

	List<Widget> getSlots()
	{
		return slots;
	}

	List<Widget> getChain()
	{
		return chain;
	}

	/**
	 * Whether the bank is inside something that fills the height of the client
	 * but not its width. The game never builds it that way, so it means a
	 * plugin has restyled the interface and is placing the bank itself.
	 */
	boolean isBoxed()
	{
		return boxed;
	}

	int getLimit()
	{
		return limit;
	}

	/** Measures the chain from {@code window} up to the root of the tree. */
	static BankRoom measure(Widget window, int canvasWidth, int canvasHeight)
	{
		if (window == null || canvasWidth <= 0)
		{
			return new BankRoom(Collections.emptyList(), Collections.emptyList(), null, 0,
				false);
		}

		List<Widget> chain = new ArrayList<>();
		for (Widget node = window; node != null && chain.size() < MAX_DEPTH; node = node.getParent())
		{
			chain.add(node);
		}

		int[] widths = new int[chain.size()];
		int[] heights = new int[chain.size()];
		int[] modes = new int[chain.size()];
		for (int i = 0; i < chain.size(); i++)
		{
			widths[i] = chain.get(i).getWidth();
			heights[i] = chain.get(i).getHeight();
			modes[i] = chain.get(i).getWidthMode();
		}

		BankChainPlan plan = BankChainPlan.of(widths, heights, modes, canvasWidth,
			canvasHeight);		int highestSlot = plan.getHighestSlot();

		List<Widget> slots = new ArrayList<>();
		for (int i = 0; i <= highestSlot; i++)
		{
			if (modes[i] == WidgetSizeMode.ABSOLUTE)
			{
				slots.add(chain.get(i));
			}
		}

		Widget viewport = plan.getViewport() < 0 ? null : chain.get(plan.getViewport());
		int limit = viewport == null ? window.getWidth() : viewport.getWidth();

		// Stop at the topmost slot. Anything above it is a tracking ancestor that
		// must keep its own size: that one is the limit, not something to widen.
		return new BankRoom(slots, chain.subList(0, highestSlot + 1), viewport, limit,
			plan.isBoxed());
	}

	/** Guards against a malformed tree sending the walk into a long loop. */
	private static final int MAX_DEPTH = 16;
}
