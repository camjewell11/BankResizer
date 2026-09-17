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
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;

/**
 * How much room the bank window has to grow, and which ancestors have to grow
 * with it.
 *
 * The bank does not sit directly on the canvas. Measured on a live client, the
 * chain above the bank window is:
 *
 * <pre>
 *   bank window      488 absolute
 *   group 12:0       512 minus      tracks its parent
 *   group 164:16     512 absolute   a fixed slot
 *   group 164:15     725 minus      the viewport, canvas minus the side panel
 *   group 164:91     940 absolute   the whole canvas
 * </pre>
 *
 * Two kinds of ancestor matter. One sized in {@code MINUS} mode stores an inset
 * and follows its parent, so it needs no help. One sized in {@code ABSOLUTE} mode
 * is a fixed slot: leave it alone and it clips the widened window, which is what
 * made the bank appear to shift left and lose a column off each edge.
 *
 * So every absolute ancestor below the full-canvas root is widened too, and the
 * limit is the narrowest tracking ancestor above them, 725 here rather than the
 * full 940. Walking the chain avoids naming group 164 child 16 directly, which
 * would be a magic number against a layout Jagex can change.
 */
final class BankRoom
{
	/** Ancestors with an absolute width, which have to be widened by hand. */
	private final List<Widget> slots;

	/**
	 * The bank window and every ancestor in play, innermost first. Callers resize
	 * in reverse so that a parent is sized before its children recompute from it.
	 */
	private final List<Widget> chain;

	/** Widest the bank window may become before an ancestor would clip it. */
	private final int limit;

	private BankRoom(List<Widget> slots, List<Widget> chain, int limit)
	{
		this.slots = slots;
		this.chain = chain;
		this.limit = limit;
	}

	List<Widget> getSlots()
	{
		return slots;
	}

	List<Widget> getChain()
	{
		return chain;
	}

	int getLimit()
	{
		return limit;
	}

	/**
	 * Measures the chain from {@code window} up to the root of the interface tree.
	 *
	 * @param window       the bank window widget
	 * @param canvasWidth  width of the game canvas, used to spot the root
	 */
	static BankRoom measure(Widget window, int canvasWidth)
	{
		if (window == null || canvasWidth <= 0)
		{
			return new BankRoom(Collections.emptyList(), Collections.emptyList(), 0);
		}

		List<Widget> chain = new ArrayList<>();
		for (Widget node = window; node != null && chain.size() < MAX_DEPTH; node = node.getParent())
		{
			chain.add(node);
		}

		// Everything below the first full-canvas ancestor is in play. That root is
		// the whole screen rather than a slot, and widening it would push the bank
		// out over the side panel.
		int end = chain.size();
		for (int i = 0; i < chain.size(); i++)
		{
			Widget node = chain.get(i);
			if (node.getWidthMode() == WidgetSizeMode.ABSOLUTE && node.getWidth() >= canvasWidth)
			{
				end = i;
				break;
			}
		}

		List<Widget> slots = new ArrayList<>();
		int highestSlot = -1;
		for (int i = 0; i < end; i++)
		{
			if (chain.get(i).getWidthMode() == WidgetSizeMode.ABSOLUTE)
			{
				slots.add(chain.get(i));
				highestSlot = i;
			}
		}

		// Only tracking ancestors above the topmost slot constrain us. Ones below
		// it sit inside something we are widening, so they follow along.
		int limit = Integer.MAX_VALUE;
		for (int i = highestSlot + 1; i < end; i++)
		{
			limit = Math.min(limit, chain.get(i).getWidth());
		}

		if (limit == Integer.MAX_VALUE)
		{
			limit = window.getWidth();
		}

		// Stop at the topmost slot. Anything above it is a tracking ancestor that
		// must keep its own size: that one is the limit, not something to widen.
		return new BankRoom(slots, chain.subList(0, highestSlot + 1), limit);
	}

	/** Guards against a malformed tree sending the walk into a long loop. */
	private static final int MAX_DEPTH = 16;
}
