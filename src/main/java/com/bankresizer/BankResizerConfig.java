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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(BankResizerConfig.GROUP)
public interface BankResizerConfig extends Config
{
	String GROUP = "bankresizer";

	@ConfigItem(
		keyName = "columns",
		name = "Columns (8 or more)",
		description = "Items per row in the bank, minimum 8."
			+ "<br><br>8 is the normal game layout and leaves the bank untouched."
			+ "<br><br>Takes effect the next time you open the bank."
			+ "<br><br>Capped to whatever fits in your client window, so asking for"
			+ " more than there is room for gives you as many as fit."
			+ "<br><br>Tabs with a saved bank tag layout keep their own 8 column"
			+ " arrangement, so that the items stay where you put them."
			+ "<br><br>Has no effect in fixed mode, which has no room to spare.",
		position = 0
	)
	@Range(
		min = BankLayout.VANILLA_COLUMNS,
		max = 24
	)
	default int columns()
	{
		// Defaults to the unmodified game layout so that installing the plugin
		// changes nothing until the user asks for it.
		return BankLayout.VANILLA_COLUMNS;
	}

	@ConfigItem(
		keyName = "fitToWidth",
		name = "Fit to window width",
		description = "Use as many columns as fit in your client window,"
			+ " ignoring the column count above."
			+ "<br><br>Takes effect the next time you open the bank."
			+ "<br><br>Resizing the client returns the bank to 8 columns until you"
			+ " reopen it, when it is fitted to the new size.",
		position = 1
	)
	default boolean fitToWidth()
	{
		return false;
	}
}
