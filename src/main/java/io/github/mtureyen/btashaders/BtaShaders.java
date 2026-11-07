package io.github.mtureyen.btashaders;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import turniplabs.halplibe.HalpLibe;

public class BtaShaders implements ModInitializer {
	public static final String MOD_ID = HalpLibe.registerMod("btashaders", true);
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("BTA Shaders initialized.");
	}
}
