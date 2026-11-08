package io.github.mtureyen.btashaders.config;

import io.github.mtureyen.btashaders.render.ShaderConfig;

import java.util.Locale;

/**
 * One setting in the in-game menu. Most map to a {@code #define} in lib/settings.glsl (written to the
 * config as {@code define.NAME=value}); a few are Java-side (shadow resolution, TAA).
 */
public final class ShaderOption {
	public enum Kind {
		/** 0/1 define. */
		TOGGLE,
		/** Float define, slider. */
		FLOAT,
		/** Integer define (sample counts), slider. */
		INT,
		/** Java: shadow map size, cycles through {@link ShaderConfig#SHADOW_RESOLUTIONS}. */
		SHADOW_RESOLUTION,
		/** Java: temporal anti-aliasing. */
		TAA
	}

	public final String key;
	public final String label;
	public final Kind kind;
	public final float min;
	public final float max;
	public final float step;
	/** Fallback when the default can't be read from settings.glsl. */
	private final float fallback;
	private String description = "";

	private ShaderOption(String key, String label, Kind kind, float min, float max, float step, float fallback) {
		this.key = key;
		this.label = label;
		this.kind = kind;
		this.min = min;
		this.max = max;
		this.step = step;
		this.fallback = fallback;
	}

	public static ShaderOption toggle(String define, String label) {
		return new ShaderOption(define, label, Kind.TOGGLE, 0, 1, 1, 1);
	}

	public static ShaderOption slider(String define, String label, float min, float max, float step) {
		return new ShaderOption(define, label, Kind.FLOAT, min, max, step, min);
	}

	public static ShaderOption samples(String define, String label, int min, int max) {
		return new ShaderOption(define, label, Kind.INT, min, max, 1, min);
	}

	public static ShaderOption shadowResolution() {
		return new ShaderOption("shadow.resolution", "Shadow Resolution", Kind.SHADOW_RESOLUTION, 0, 0, 0, 2048)
			.describe("Shadow map size: sharper shadows at higher values, costs GPU time and memory.");
	}

	public static ShaderOption taa() {
		return new ShaderOption("taa", "Anti-Aliasing (TAA)", Kind.TAA, 0, 1, 1, 1)
			.describe("Temporal anti-aliasing: smooth edges, also resolves the noise of shadows/reflections.");
	}

	public ShaderOption describe(String text) {
		this.description = text;
		return this;
	}

	public String description() {
		if (!this.description.isEmpty()) {
			return this.description;
		}
		return ShaderOptions.defaultComment(this.key);
	}

	public boolean isDefine() {
		return this.kind == Kind.TOGGLE || this.kind == Kind.FLOAT || this.kind == Kind.INT;
	}

	public float defaultValue() {
		return switch (this.kind) {
			case SHADOW_RESOLUTION -> 2048;
			case TAA -> 1;
			default -> {
				Float parsed = ShaderOptions.defaultValue(this.key);
				yield parsed != null ? parsed : this.fallback;
			}
		};
	}

	/** Current value: the config override, or the shader default. */
	public float value() {
		switch (this.kind) {
			case SHADOW_RESOLUTION:
				return ShaderConfig.shadowResolution();
			case TAA:
				return ShaderConfig.taa() ? 1 : 0;
			default:
				String override = ShaderConfig.defines().get(this.key);
				if (override != null) {
					try {
						return Float.parseFloat(override.trim());
					} catch (NumberFormatException ignored) {
						// not a plain number (e.g. a vec3); fall back to the default
					}
				}
				return this.defaultValue();
		}
	}

	public void set(float value) {
		switch (this.kind) {
			case SHADOW_RESOLUTION -> ShaderConfig.setShadowResolution(Math.round(value));
			case TAA -> ShaderConfig.setTaa(value > 0.5F);
			default -> {
				float v = this.snap(value);
				// Store only real changes, so the config stays readable and follows future defaults.
				boolean isDefault = Math.abs(v - this.defaultValue()) < this.step * 0.25F;
				ShaderConfig.setDefine(this.key, isDefault ? null : this.format(v));
			}
		}
	}

	public void reset() {
		this.set(this.defaultValue());
	}

	public float snap(float value) {
		if (this.kind == Kind.TOGGLE || this.kind == Kind.TAA) {
			return value > 0.5F ? 1 : 0;
		}
		float clamped = Math.max(this.min, Math.min(this.max, value));
		if (this.step <= 0) {
			return clamped;
		}
		return this.min + Math.round((clamped - this.min) / this.step) * this.step;
	}

	/** GLSL literal: floats always carry a decimal point (int/float mixing can break expressions). */
	public String format(float value) {
		if (this.kind != Kind.FLOAT) {
			return Integer.toString(Math.round(value));
		}
		String s = String.format(Locale.ROOT, "%.3f", value);
		while (s.endsWith("0") && !s.endsWith(".0")) {
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}

	public float toSlider(float value) {
		return this.max > this.min ? (value - this.min) / (this.max - this.min) : 0;
	}

	public float fromSlider(double t) {
		return this.snap((float) (this.min + t * (this.max - this.min)));
	}

	public String displayText() {
		float v = this.value();
		String text = switch (this.kind) {
			case TOGGLE, TAA -> v > 0.5F ? "On" : "Off";
			case SHADOW_RESOLUTION -> Math.round(v) + "px";
			case INT -> Integer.toString(Math.round(v));
			case FLOAT -> this.format(v);
		};
		return this.label + ": " + text;
	}

	/** Next value for click-to-cycle options. */
	public void cycle() {
		if (this.kind == Kind.SHADOW_RESOLUTION) {
			int current = ShaderConfig.shadowResolution();
			int[] options = ShaderConfig.SHADOW_RESOLUTIONS;
			int next = options[0];
			for (int i = 0; i < options.length; i++) {
				if (options[i] == current) {
					next = options[(i + 1) % options.length];
					break;
				}
			}
			this.set(next);
		} else {
			this.set(this.value() > 0.5F ? 0 : 1);
		}
	}
}
