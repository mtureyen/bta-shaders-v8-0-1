package io.github.mtureyen.btashaders.render;

import io.github.mtureyen.btashaders.BtaShaders;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * User settings in {@code .minecraft/config/btashaders.properties}, re-read on every shader reload (F7)
 * and written by the in-game settings screen.
 *
 * <ul>
 *   <li>{@code pack=<folder>}: use shaders from {@code .minecraft/shaderpacks/<folder>/} (files missing
 *       there fall back to the built-in ones). Empty = built-in.</li>
 *   <li>{@code shadow.resolution=2048}: shadow map size (Java side).</li>
 *   <li>{@code taa=true}: temporal anti-aliasing (F10 toggles it for the session).</li>
 *   <li>{@code define.NAME=value}: injected as {@code #define NAME value} into every shader, overriding
 *       the defaults (all tunables in our shaders are wrapped in {@code #ifndef}).</li>
 * </ul>
 */
public final class ShaderConfig {
	private static final String HEADER = """
		# BTA Shaders settings. Written by the in-game settings screen (Mod Menu, or Shift+F7);
		# hand edits work too and are re-read when shaders reload (F7 in game).
		#
		# pack: folder name inside .minecraft/shaderpacks/ to load shaders from (empty = built-in).
		# Files the pack doesn't contain fall back to the built-in ones, so a pack can override just one file.
		# shadow.resolution: shadow map size (1024, 2048, 3072, 4096).
		# taa: temporal anti-aliasing on/off.
		# define.NAME=value becomes "#define NAME value" in every shader (see lib/settings.glsl for all names).
		""";

	public static final int[] SHADOW_RESOLUTIONS = {1024, 2048, 3072, 4096};

	private static String pack = "";
	private static int shadowResolution = ShadowRenderer.DEFAULT_RESOLUTION;
	private static boolean taa = true;
	private static final Map<String, String> DEFINES = new LinkedHashMap<>();

	private ShaderConfig() {
	}

	public static synchronized void load() {
		pack = "";
		shadowResolution = ShadowRenderer.DEFAULT_RESOLUTION;
		taa = true;
		DEFINES.clear();
		Path file = configFile();
		if (file == null) {
			return;
		}
		try {
			if (!Files.exists(file)) {
				save();
				return;
			}
			Properties props = new Properties();
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				props.load(reader);
			}
			pack = props.getProperty("pack", "").trim();
			shadowResolution = parseResolution(props.getProperty("shadow.resolution"));
			taa = !"false".equalsIgnoreCase(props.getProperty("taa", "true").trim());
			for (String key : props.stringPropertyNames()) {
				if (key.startsWith("define.")) {
					String name = key.substring("define.".length()).trim();
					if (isValidName(name)) {
						DEFINES.put(name, props.getProperty(key).trim());
					}
				}
			}
			BtaShaders.LOGGER.info("Shader config: pack='{}', shadows {}, {} define override(s).", pack,
				shadowResolution, DEFINES.size());
		} catch (IOException e) {
			BtaShaders.LOGGER.error("Failed to read {}", file, e);
		}
	}

	/** Writes the current state (defines sorted by name). */
	public static synchronized void save() {
		Path file = configFile();
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				writer.write(HEADER);
				writer.write("pack=" + pack + "\n");
				writer.write("shadow.resolution=" + shadowResolution + "\n");
				writer.write("taa=" + taa + "\n");
				for (Map.Entry<String, String> e : new TreeMap<>(DEFINES).entrySet()) {
					writer.write("define." + e.getKey() + "=" + e.getValue() + "\n");
				}
			}
		} catch (IOException e) {
			BtaShaders.LOGGER.error("Failed to write {}", file, e);
		}
	}

	private static int parseResolution(String value) {
		if (value == null) {
			return ShadowRenderer.DEFAULT_RESOLUTION;
		}
		try {
			int v = Integer.parseInt(value.trim());
			return Math.max(512, Math.min(8192, v));
		} catch (NumberFormatException e) {
			return ShadowRenderer.DEFAULT_RESOLUTION;
		}
	}

	private static boolean isValidName(String name) {
		return name.matches("[A-Za-z_][A-Za-z0-9_]*");
	}

	public static synchronized Path packDir() {
		if (pack.isEmpty()) {
			return null;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null) {
			return null;
		}
		Path dir = mc.getMinecraftDir().toPath().resolve("shaderpacks").resolve(pack);
		return Files.isDirectory(dir) ? dir : null;
	}

	public static synchronized Map<String, String> defines() {
		return Map.copyOf(DEFINES);
	}

	/** Sets an override; {@code null} removes it (back to the shader default). */
	public static synchronized void setDefine(String name, String value) {
		if (!isValidName(name)) {
			return;
		}
		if (value == null) {
			DEFINES.remove(name);
		} else {
			DEFINES.put(name, value);
		}
	}

	public static synchronized int shadowResolution() {
		return shadowResolution;
	}

	public static synchronized void setShadowResolution(int resolution) {
		shadowResolution = parseResolution(String.valueOf(resolution));
	}

	public static synchronized boolean taa() {
		return taa;
	}

	public static synchronized void setTaa(boolean on) {
		taa = on;
	}

	private static Path configFile() {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null) {
			return null;
		}
		return mc.getMinecraftDir().toPath().resolve("config").resolve("btashaders.properties");
	}
}
