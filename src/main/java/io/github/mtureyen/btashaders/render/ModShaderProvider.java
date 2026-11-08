package io.github.mtureyen.btashaders.render;

import io.github.mtureyen.btashaders.BtaShaders;
import net.minecraft.client.render.shader.ShaderProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads GLSL sources bundled in this mod's jar, e.g. {@code /assets/btashaders/shaders/composite.fsh}.
 * Uses our own classloader so it works regardless of the active asset pack.
 *
 * <p>Dev mode: if the system property {@code btashaders.devShaderDir} points at a directory (the
 * Gradle runClient task sets it to {@code src/main/resources/assets/btashaders/shaders}), sources are
 * read from there first. Edit a shader, press F7 in-game, see the result without restarting.
 */
public class ModShaderProvider implements ShaderProvider {
	private static final Path DEV_DIR = devDir();

	private final String dir;

	public ModShaderProvider(String dir) {
		this.dir = dir.endsWith("/") ? dir : dir + "/";
	}

	private static Path devDir() {
		String prop = System.getProperty("btashaders.devShaderDir");
		if (prop == null || prop.isBlank()) {
			return null;
		}
		Path path = Path.of(prop);
		if (Files.isDirectory(path)) {
			BtaShaders.LOGGER.info("Dev shader directory: {} (F7 reloads from here)", path);
			return path;
		}
		BtaShaders.LOGGER.warn("btashaders.devShaderDir {} is not a directory; using bundled shaders.", path);
		return null;
	}

	@Override
	public String getShaderSource(String name) {
		if (DEV_DIR != null) {
			Path file = DEV_DIR.resolve(name);
			if (Files.isRegularFile(file)) {
				try {
					return Files.readString(file, StandardCharsets.UTF_8);
				} catch (IOException e) {
					BtaShaders.LOGGER.error("Failed to read dev shader {}", file, e);
				}
			}
		}
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
