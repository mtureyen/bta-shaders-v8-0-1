package io.github.mtureyen.btashaders.config;

import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import io.github.mtureyen.btashaders.render.ModShaderProvider;
import io.github.mtureyen.btashaders.render.ShaderConfig;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the settings menu shows: pages of options and quality presets. Defaults and descriptions
 * come straight from lib/settings.glsl (the active one: dev dir, shader pack, or built-in), so the
 * shader stays the single source of truth.
 */
public final class ShaderOptions {
	public record Page(String title, List<ShaderOption> options) {
	}

	public enum Preset {
		LOW("Low"), MEDIUM("Medium"), HIGH("High"), ULTRA("Ultra");

		public final String label;

		Preset(String label) {
			this.label = label;
		}
	}

	private static final Pattern DEFINE = Pattern.compile(
		"^\\s*#define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+(-?[0-9]*\\.?[0-9]+)\\s*(?://\\s*(.*))?$");

	private static final Map<String, Float> DEFAULTS = new HashMap<>();
	private static final Map<String, String> COMMENTS = new HashMap<>();

	public static final List<Page> PAGES = List.of(
		new Page("Lighting", List.of(
			ShaderOption.slider("SUN_BRIGHTNESS", "Sunlight", 0.0F, 2.0F, 0.05F),
			ShaderOption.slider("MOON_BRIGHTNESS", "Moonlight", 0.0F, 3.0F, 0.1F),
			ShaderOption.slider("AMBIENT_STRENGTH", "Shade Brightness", 0.0F, 1.0F, 0.02F),
			ShaderOption.slider("AMBIENT_HUE_STRENGTH", "Sky Tint In Shade", 0.0F, 1.5F, 0.05F),
			ShaderOption.slider("BOUNCE_LIGHT", "Bounce Light", 0.0F, 0.5F, 0.01F),
			ShaderOption.slider("BLOCKLIGHT_STRENGTH", "Torch Light", 0.0F, 2.0F, 0.05F),
			ShaderOption.slider("MIN_LIGHT", "Cave Minimum Light", 0.0F, 2.0F, 0.05F),
			ShaderOption.slider("FACE_SHADE_BOTTOM", "Underside Shading", 0.3F, 1.0F, 0.02F),
			ShaderOption.slider("BAKED_SHADE_STRENGTH", "Corner Darkening", 0.0F, 1.0F, 0.05F),
			ShaderOption.slider("LEAF_SUBSURFACE", "Leaf Subsurface", 0.0F, 1.0F, 0.02F),
			ShaderOption.slider("PLANT_SUBSURFACE", "Plant Subsurface", 0.0F, 1.0F, 0.02F),
			ShaderOption.slider("LEAF_TRANSLUCENCY", "Leaf Translucency", 0.0F, 1.0F, 0.05F)
		)),
		new Page("Shadows", List.of(
			ShaderOption.shadowResolution(),
			ShaderOption.samples("SHADOW_SAMPLES", "Shadow Samples", 4, 24),
			ShaderOption.samples("FOLIAGE_SHADOW_SAMPLES", "Foliage Samples", 4, 32),
			ShaderOption.slider("SHADOW_FILTER_RADIUS", "Shadow Softness", 0.5F, 4.0F, 0.1F),
			ShaderOption.toggle("PCSS", "Contact Hardening"),
			ShaderOption.slider("PCSS_SOFTNESS", "Penumbra Width", 0.0F, 0.1F, 0.005F),
			ShaderOption.slider("PCSS_MAX_RADIUS", "Max Penumbra", 2.0F, 16.0F, 0.5F),
			ShaderOption.toggle("ENTITY_SHADOWS", "Entity Shadows"),
			ShaderOption.toggle("COLORED_SHADOWS", "Colored Shadows"),
			ShaderOption.toggle("CAUSTICS", "Water Caustics"),
			ShaderOption.slider("CAUSTICS_STRENGTH", "Caustics Strength", 0.0F, 2.0F, 0.05F),
			ShaderOption.slider("CLOUD_SHADOW_STRENGTH", "Cloud Shadows", 0.0F, 1.0F, 0.05F),
			ShaderOption.toggle("SSAO", "Ambient Occlusion"),
			ShaderOption.samples("SSAO_SAMPLES", "AO Samples", 4, 16),
			ShaderOption.slider("SSAO_STRENGTH", "AO Strength", 0.0F, 1.5F, 0.05F)
		)),
		new Page("Sky", List.of(
			ShaderOption.toggle("CLOUDS", "Clouds"),
			ShaderOption.slider("CLOUD_COVERAGE", "Cloud Coverage", 0.0F, 1.0F, 0.02F),
			ShaderOption.slider("CLOUD_THICKNESS", "Cloud Thickness", 2.0F, 24.0F, 1.0F),
			ShaderOption.samples("CLOUD_STEPS", "Cloud Quality", 6, 32),
			ShaderOption.slider("CLOUD_SPEED", "Cloud Speed", 0.0F, 3.0F, 0.1F),
			ShaderOption.slider("SKY_BRIGHTNESS", "Sky Brightness", 0.5F, 2.0F, 0.05F),
			ShaderOption.slider("SKY_SUN_GLOW", "Sun Glow", 0.0F, 2.0F, 0.05F),
			ShaderOption.toggle("VOLUMETRIC_LIGHT", "Volumetric Light"),
			ShaderOption.slider("VL_STRENGTH", "Light Shaft Strength", 0.0F, 0.3F, 0.01F),
			ShaderOption.samples("VL_STEPS", "Light Shaft Quality", 4, 32),
			ShaderOption.slider("ATMOS_STRENGTH", "Distance Haze", 0.0F, 0.4F, 0.01F),
			ShaderOption.toggle("BORDER_FOG", "Border Fog"),
			ShaderOption.slider("BORDER_FOG_START", "Border Fog Start", 0.3F, 0.9F, 0.01F),
			ShaderOption.slider("UNDERWATER_FOG", "Underwater Fog", 0.0F, 1.0F, 0.05F)
		)),
		new Page("Surfaces", List.of(
			ShaderOption.toggle("WATER_REFLECTIONS", "Water Reflections"),
			ShaderOption.samples("WATER_SSR_STEPS", "Water Reflection Quality", 8, 48),
			ShaderOption.slider("WATER_WAVE_STRENGTH", "Water Waves", 0.0F, 2.0F, 0.05F),
			ShaderOption.slider("WATER_ABSORPTION", "Water Murkiness", 0.0F, 3.0F, 0.1F),
			ShaderOption.toggle("REFLECTIONS", "Block Reflections"),
			ShaderOption.samples("SSR_STEPS", "Reflection Quality", 8, 48),
			ShaderOption.slider("REFLECTION_STRENGTH", "Reflection Strength", 0.0F, 1.5F, 0.05F),
			ShaderOption.toggle("SPECULAR", "Sun Highlights"),
			ShaderOption.slider("SPECULAR_STRENGTH", "Highlight Strength", 0.0F, 3.0F, 0.1F),
			ShaderOption.toggle("RAIN_WETNESS", "Wet Surfaces In Rain"),
			ShaderOption.toggle("RAIN_PUDDLES", "Puddles"),
			ShaderOption.slider("PUDDLE_COVERAGE", "Puddle Coverage", 0.0F, 1.0F, 0.05F),
			ShaderOption.toggle("RAIN_RIPPLES", "Rain Ripples"),
			ShaderOption.slider("RIPPLE_STRENGTH", "Ripple Strength", 0.0F, 3.0F, 0.1F),
			ShaderOption.toggle("LABPBR", "Resource Pack PBR"),
			ShaderOption.slider("NORMAL_MAP_STRENGTH", "Normal Map Strength", 0.0F, 2.0F, 0.05F),
			ShaderOption.slider("PBR_EMISSION", "PBR Emission", 0.0F, 4.0F, 0.1F),
			ShaderOption.toggle("WAVING_PLANTS", "Waving Plants"),
			ShaderOption.toggle("WAVING_LEAVES", "Waving Leaves"),
			ShaderOption.slider("WAVING_STRENGTH", "Waving Strength", 0.0F, 2.0F, 0.05F)
		)),
		new Page("Camera", List.of(
			ShaderOption.taa(),
			ShaderOption.slider("SHARPEN", "Sharpening", 0.0F, 1.5F, 0.05F),
			ShaderOption.toggle("BLOOM", "Bloom"),
			ShaderOption.slider("BLOOM_STRENGTH", "Bloom Strength", 0.0F, 1.5F, 0.05F),
			ShaderOption.toggle("AUTO_EXPOSURE", "Eye Adaptation"),
			ShaderOption.slider("AUTO_EXPOSURE_STRENGTH", "Adaptation Strength", 0.0F, 1.0F, 0.05F),
			ShaderOption.slider("TONEMAP_EXPOSURE", "Exposure", 0.5F, 2.0F, 0.05F),
			ShaderOption.slider("TONEMAP_CONTRAST", "Tone Contrast", 0.8F, 1.6F, 0.01F),
			ShaderOption.slider("TONEMAP_SATURATION", "Saturation", 0.5F, 1.6F, 0.01F),
			ShaderOption.slider("WARMTH", "Warmth", 0.0F, 1.5F, 0.05F),
			ShaderOption.slider("VIBRANCE", "Vibrance", 0.0F, 1.0F, 0.05F),
			ShaderOption.slider("CONTRAST", "Contrast", 0.0F, 0.5F, 0.01F),
			ShaderOption.slider("VIGNETTE", "Vignette", 0.0F, 1.0F, 0.05F)
		))
	);

	private ShaderOptions() {
	}

	/** Re-reads defaults/comments from the active settings.glsl (call when the menu opens). */
	public static synchronized void refreshDefaults() {
		DEFAULTS.clear();
		COMMENTS.clear();
		if (!(BtaShadersRenderer.MOD_SHADERS instanceof ModShaderProvider provider)) {
			return;
		}
		String source = provider.readSource("lib/settings.glsl");
		if (source == null) {
			return;
		}
		for (String line : source.split("\r?\n")) {
			Matcher m = DEFINE.matcher(line);
			if (m.matches()) {
				try {
					DEFAULTS.put(m.group(1), Float.parseFloat(m.group(2)));
				} catch (NumberFormatException ignored) {
					continue;
				}
				if (m.group(3) != null) {
					String comment = m.group(3).trim();
					COMMENTS.put(m.group(1), Character.toUpperCase(comment.charAt(0)) + comment.substring(1));
				}
			}
		}
	}

	static synchronized Float defaultValue(String define) {
		return DEFAULTS.get(define);
	}

	static synchronized String defaultComment(String define) {
		return COMMENTS.getOrDefault(define, "");
	}

	public static ShaderOption find(String key) {
		for (Page page : PAGES) {
			for (ShaderOption option : page.options()) {
				if (option.key.equals(key)) {
					return option;
				}
			}
		}
		return null;
	}

	/** Performance-relevant settings per preset; anything not listed stays as the user set it. */
	public static Map<String, Float> presetValues(Preset preset) {
		Map<String, Float> v = new LinkedHashMap<>();
		switch (preset) {
			case LOW -> {
				v.put("shadow.resolution", 1024F);
				v.put("SHADOW_SAMPLES", 6F);
				v.put("FOLIAGE_SHADOW_SAMPLES", 8F);
				v.put("PCSS", 0F);
				v.put("ENTITY_SHADOWS", 0F);
				v.put("COLORED_SHADOWS", 0F);
				v.put("SSAO", 0F);
				v.put("CLOUD_STEPS", 8F);
				v.put("VOLUMETRIC_LIGHT", 0F);
				v.put("REFLECTIONS", 0F);
				v.put("SSR_STEPS", 10F);
				v.put("WATER_SSR_STEPS", 12F);
				v.put("RAIN_PUDDLES", 0F);
			}
			case MEDIUM -> {
				v.put("shadow.resolution", 2048F);
				v.put("SHADOW_SAMPLES", 8F);
				v.put("FOLIAGE_SHADOW_SAMPLES", 12F);
				v.put("PCSS", 0F);
				v.put("ENTITY_SHADOWS", 1F);
				v.put("SSAO", 1F);
				v.put("SSAO_SAMPLES", 6F);
				v.put("COLORED_SHADOWS", 1F);
				v.put("CLOUD_STEPS", 10F);
				v.put("VOLUMETRIC_LIGHT", 1F);
				v.put("VL_STEPS", 8F);
				v.put("REFLECTIONS", 1F);
				v.put("SSR_STEPS", 14F);
				v.put("WATER_SSR_STEPS", 16F);
				v.put("RAIN_PUDDLES", 1F);
			}
			case HIGH -> {
				// The shader defaults.
				for (String key : new String[]{"shadow.resolution", "SHADOW_SAMPLES", "FOLIAGE_SHADOW_SAMPLES",
					"PCSS", "ENTITY_SHADOWS", "COLORED_SHADOWS", "SSAO", "SSAO_SAMPLES", "CLOUD_STEPS", "VOLUMETRIC_LIGHT",
					"VL_STEPS", "REFLECTIONS", "SSR_STEPS", "WATER_SSR_STEPS", "RAIN_PUDDLES"}) {
					ShaderOption option = find(key);
					if (option != null) {
						v.put(key, option.defaultValue());
					}
				}
			}
			case ULTRA -> {
				v.put("shadow.resolution", 4096F);
				v.put("SHADOW_SAMPLES", 16F);
				v.put("FOLIAGE_SHADOW_SAMPLES", 24F);
				v.put("PCSS", 1F);
				v.put("ENTITY_SHADOWS", 1F);
				v.put("SSAO", 1F);
				v.put("SSAO_SAMPLES", 12F);
				v.put("COLORED_SHADOWS", 1F);
				v.put("CLOUD_STEPS", 20F);
				v.put("VOLUMETRIC_LIGHT", 1F);
				v.put("VL_STEPS", 20F);
				v.put("REFLECTIONS", 1F);
				v.put("SSR_STEPS", 32F);
				v.put("WATER_SSR_STEPS", 40F);
				v.put("RAIN_PUDDLES", 1F);
			}
		}
		return v;
	}

	public static void applyPreset(Preset preset) {
		for (Map.Entry<String, Float> e : presetValues(preset).entrySet()) {
			ShaderOption option = find(e.getKey());
			if (option != null) {
				option.set(e.getValue());
			}
		}
	}

	/** The preset the current settings match exactly, or null ("Custom"). */
	public static Preset currentPreset() {
		for (Preset preset : Preset.values()) {
			boolean matches = true;
			for (Map.Entry<String, Float> e : presetValues(preset).entrySet()) {
				ShaderOption option = find(e.getKey());
				if (option != null && Math.abs(option.value() - e.getValue()) > 1e-3F) {
					matches = false;
					break;
				}
			}
			if (matches) {
				return preset;
			}
		}
		return null;
	}

	/** Shader defaults for everything, Java options included. */
	public static void resetAll() {
		for (Page page : PAGES) {
			for (ShaderOption option : page.options()) {
				option.reset();
			}
		}
		ShaderConfig.setShadowResolution(2048);
		ShaderConfig.setTaa(true);
	}
}
