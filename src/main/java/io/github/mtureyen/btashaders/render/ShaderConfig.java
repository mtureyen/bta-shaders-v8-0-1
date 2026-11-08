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

/**
 * User settings in {@code .minecraft/config/btashaders.properties}, re-read on every shader reload (F7).
 *
 * <ul>
 *   <li>{@code pack=<folder>}: use shaders from {@code .minecraft/shaderpacks/<folder>/} (files missing
 *       there fall back to the built-in ones). Empty = built-in.</li>
 *   <li>{@code define.NAME=value}: injected as {@code #define NAME value} into every shader, overriding
 *       the defaults (all tunables in our shaders are wrapped in {@code #ifndef}).</li>
 * </ul>
 */
public final class ShaderConfig {
	private static final String DEFAULT_FILE = """
		# BTA Shaders settings. Re-read when shaders reload (F7 in game).
		#
		# pack: folder name inside .minecraft/shaderpacks/ to load shaders from (empty = built-in).
		# Files the pack doesn't contain fall back to the built-in ones, so a pack can override just one file.
		pack=
		#
		# Any define.NAME=value line becomes "#define NAME value" in every shader. Examples (uncomment):
		#define.SHADOW_FILTER_RADIUS=1.6
		#define.AMBIENT_SKY_LIGHT=0.78
		#define.SSAO_STRENGTH=0.6
		#define.WAVING_PLANTS=1
		#define.WATER_REFLECTIONS=1
		#define.GODRAY_INTENSITY=0.45
		#define.SHARPEN=0.45
		#define.TONEMAP_EXPOSURE=1.0
		""";

	private static String pack = "";
	private static final Map<String, String> DEFINES = new LinkedHashMap<>();

	private ShaderConfig() {
	}

	public static synchronized void load() {
		pack = "";
		DEFINES.clear();
		Path file = configFile();
		if (file == null) {
			return;
		}
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
					writer.write(DEFAULT_FILE);
				}
			}
			Properties props = new Properties();
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				props.load(reader);
			}
			pack = props.getProperty("pack", "").trim();
			for (String key : props.stringPropertyNames()) {
				if (key.startsWith("define.")) {
					String name = key.substring("define.".length()).trim();
					if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
						DEFINES.put(name, props.getProperty(key).trim());
					}
				}
			}
			BtaShaders.LOGGER.info("Shader config: pack='{}', {} define override(s).", pack, DEFINES.size());
		} catch (IOException e) {
			BtaShaders.LOGGER.error("Failed to read {}", file, e);
		}
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

	private static Path configFile() {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null) {
			return null;
		}
		return mc.getMinecraftDir().toPath().resolve("config").resolve("btashaders.properties");
	}
}
