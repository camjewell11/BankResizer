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
		keyName = "fitToWidth",
		name = "Fit to window width",
		description = "Use as many columns as fit in the client window. Overrides the column count below.",
		position = 0
	)
	default boolean fitToWidth()
	{
		return false;
	}

	@Range(
		min = BankLayout.VANILLA_COLUMNS,
		max = 24
	)
	@ConfigItem(
		keyName = "columns",
		name = "Columns",
		description = "Items per row in the bank. Capped at whatever fits in your client window. 8 is vanilla.",
		position = 1
	)
	default int columns()
	{
		return 10;
	}
}
