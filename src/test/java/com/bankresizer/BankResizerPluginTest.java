package com.bankresizer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BankResizerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BankResizerPlugin.class);
		RuneLite.main(args);
	}
}
