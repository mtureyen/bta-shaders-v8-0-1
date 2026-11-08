package io.github.mtureyen.btashaders.render;

import net.minecraft.client.render.renderer.Shaders;
import net.minecraft.client.render.shader.Shader;
import org.lwjgl.opengl.GL41;

/**
 * Drives the shared lighting (lib/lighting.glsl) in every world shader we replace: terrain, entity,
 * world, item. Uniforms are uploaded once per frame at the start of the world pass, and lighting is
 * switched off again at the end so GUI rendering (inventory icons, player doll) stays stock.
 */
public final class LightingState {
	/** Values of btsLightingOn, must match lib/lighting.glsl. */
	public static final int OFF = 0;
	public static final int WORLD = 1;
	public static final int HAND = 2;

	/** Texture units used by our samplers (BTA itself uses 0-3). */
	public static final int SHADOW_UNIT = 4;
	public static final int OPAQUE_COLOR_UNIT = 5;
	public static final int OPAQUE_DEPTH_UNIT = 6;

	private static final int GL_TEXTURE0 = 33984;

	private final ShadowRenderer shadows;
	private int mode = OFF;
	/** Uniforms uploaded for this frame; lighting switches on once world geometry starts. */
	private boolean ready = false;

	public LightingState(ShadowRenderer shadows) {
		this.shadows = shadows;
	}

	/** The BTA world shaders we replace with our lit versions (see ShadersMixin). */
	public static Shader[] litShaders() {
		// color_world isn't lit, but needs the sun/sky uniforms for its sky-colored fog.
		return new Shader[]{Shaders.TERRAIN, Shaders.ENTITY, Shaders.WORLD, Shaders.ITEM, Shaders.COLOR_WORLD};
	}

	/**
	 * Right after compiling one of our shaders: point our samplers at their own texture units. GL
	 * forbids samplers of different types (sampler2DShadow vs sampler2D) sharing a unit, and all
	 * samplers default to unit 0 = BTA's color texture.
	 */
	public static void initSamplers(Shader shader) {
		shader.bind();
		shader.uniformInt("btsShadowTex", SHADOW_UNIT);
		shader.uniformInt("btsOpaqueColor", OPAQUE_COLOR_UNIT);
		shader.uniformInt("btsOpaqueDepth", OPAQUE_DEPTH_UNIT);
		shader.uniformInt("btsLightingOn", OFF);
		shader.uniformInt("btsShadowsOn", 0);
		shader.uniformInt("btsWaterPass", 0);
		GL41.glUseProgram(0);
	}

	/**
	 * Start of the world pass: bind the shadow map and upload this frame's lighting to all lit shaders.
	 * Lighting itself stays off until {@link #enterWorldGeometry()} (the sky is drawn first and must stay
	 * stock).
	 */
	public void beginWorld(boolean enabled, boolean eyeInWater, boolean skyFog) {
		this.ready = false;
		this.setMode(OFF);
		if (!enabled || !this.shadows.isLightValid()) {
			this.setSkyFog(false);
			return;
		}
		if (this.shadows.isShadowMapValid()) {
			GL41.glActiveTexture(GL_TEXTURE0 + SHADOW_UNIT);
			GL41.glBindTexture(GL41.GL_TEXTURE_2D, this.shadows.getDepthTexture());
			GL41.glBindSampler(SHADOW_UNIT, this.shadows.getCompareSampler());
			GL41.glActiveTexture(GL_TEXTURE0);
		}
		for (Shader shader : litShaders()) {
			if (!shader.isEnabled()) {
				continue;
			}
			shader.bind();
			this.shadows.applyUniforms(shader);
			shader.uniformInt("btsEyeInWater", eyeInWater ? 1 : 0);
			shader.uniformInt("btsSkyFog", skyFog && !eyeInWater ? 1 : 0);
		}
		GL41.glUseProgram(0);
		this.ready = true;
	}

	private void setSkyFog(boolean on) {
		for (Shader shader : litShaders()) {
			if (shader.isEnabled()) {
				shader.bind();
				shader.uniformInt("btsSkyFog", on ? 1 : 0);
			}
		}
		GL41.glUseProgram(0);
	}

	/** Start of the solid terrain draw: everything from here on (terrain, entities, water, hand) is lit. */
	public void enterWorldGeometry() {
		if (this.ready && this.mode == OFF) {
			this.setMode(WORLD);
		}
	}

	/** End of the world pass: lighting off, unbind our samplers. */
	public void endWorld() {
		this.ready = false;
		this.setMode(OFF);
		this.setSkyFog(false); // GUI rendering (inventory icons etc.) keeps BTA's own fog color
		GL41.glBindSampler(SHADOW_UNIT, 0);
	}

	public void setMode(int mode) {
		if (mode != OFF && this.mode == OFF && mode == HAND) {
			return; // hand mode only makes sense inside an active world pass
		}
		this.mode = mode;
		for (Shader shader : litShaders()) {
			if (!shader.isEnabled()) {
				continue;
			}
			shader.bind();
			shader.uniformInt("btsLightingOn", mode);
		}
		GL41.glUseProgram(0);
	}

	public int getMode() {
		return this.mode;
	}
}
