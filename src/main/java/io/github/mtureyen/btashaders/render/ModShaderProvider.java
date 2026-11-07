package io.github.mtureyen.btashaders.render;

import io.github.mtureyen.btashaders.BtaShaders;
import net.minecraft.client.render.shader.ShaderProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads GLSL sources bundled in this mod's jar, e.g. {@code /assets/btashaders/shaders/composite.fsh}.
 * Uses our own classloader so it works regardless of the active asset pack.
 */
public class ModShaderProvider implements ShaderProvider {
	private final String dir;

	public ModShaderProvider(String dir) {
		this.dir = dir.endsWith("/") ? dir : dir + "/";
	}

	@Override
	public String getShaderSource(String name) {
		String path = this.dir + name;
		try (InputStream in = BtaShaders.class.getResourceAsStream(path)) {
			if (in == null) {
				BtaShaders.LOGGER.error("Shader source {} not found in mod jar", path);
				return null;
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BtaShaders.LOGGER.error("Failed to read shader source {}", path, e);
			return null;
		}
	}
}
