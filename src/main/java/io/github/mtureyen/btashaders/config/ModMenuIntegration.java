package io.github.mtureyen.btashaders.config;

import io.github.prospector.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.Screen;

import java.util.function.Function;

/** Mod Menu's "Config" button opens our settings screen (Mod Menu is optional). */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public String getModId() {
		return "btashaders";
	}

	@Override
	public Function<Screen, ? extends Screen> getConfigScreenFactory() {
		return ShaderSettingsScreen::new;
	}
}
