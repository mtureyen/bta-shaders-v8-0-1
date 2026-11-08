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
	/** Same shadow map without the compare sampler (PCSS blocker search). */
	public static final int SHADOW_RAW_UNIT = 7;
	/** Colored shadows: translucent-caster depth (compare), same raw, and the casters' color. */
	public static final int SHADOW_TRANSLUCENT_UNIT = 8;
	public static final int SHADOW_TRANSLUCENT_RAW_UNIT = 9;
	public static final int SHADOW_COLOR_UNIT = 10;
	/** labPBR atlas layers (PbrAtlas). */
	public static final int PBR_NORMAL_UNIT = 11;
	public static final int PBR_SPECULAR_UNIT = 12;
	/** Highest unit any of our passes uses. */
	public static final int LAST_UNIT = 14;

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
		shader.uniformInt("btsShadowDepthRaw", SHADOW_RAW_UNIT);
		shader.uniformInt("btsShadowTexTranslucent", SHADOW_TRANSLUCENT_UNIT);
		shader.uniformInt("btsShadowDepthTranslucent", SHADOW_TRANSLUCENT_RAW_UNIT);
		shader.uniformInt("btsShadowColor", SHADOW_COLOR_UNIT);
		shader.uniformInt("btsColoredShadowsOn", 0);
		shader.uniformInt("btsNormalTex", PBR_NORMAL_UNIT);
		shader.uniformInt("btsSpecularTex", PBR_SPECULAR_UNIT);
		shader.uniformInt("btsPbrOn", 0);
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
		// Every unit we own gets a defined binding each frame: the real texture, or nothing. Leftovers from
		// other passes (e.g. a G-buffer target that is also attached to the world framebuffer) must never
		// sit on a unit a world shader declares a sampler for.
		boolean shadowsValid = this.shadows.isShadowMapValid();
		boolean coloredValid = shadowsValid && this.shadows.isColoredShadowsValid();
		int compare = this.shadows.getCompareSampler();
		bindUnit(SHADOW_UNIT, shadowsValid ? this.shadows.getDepthTexture() : placeholderShadow(), shadowsValid ? compare : 0);
		bindUnit(SHADOW_RAW_UNIT, shadowsValid ? this.shadows.getDepthTexture() : 0, 0);
		bindUnit(SHADOW_TRANSLUCENT_UNIT, coloredValid ? this.shadows.getTranslucentDepthTexture() : placeholderShadow(), coloredValid ? compare : 0);
		bindUnit(SHADOW_TRANSLUCENT_RAW_UNIT, coloredValid ? this.shadows.getTranslucentDepthTexture() : 0, 0);
		bindUnit(SHADOW_COLOR_UNIT, coloredValid ? this.shadows.getShadowColorTexture() : 0, 0);
		int pbrNormal = PbrAtlas.texture(PbrAtlas.NORMAL_LAYER);
		int pbrSpecular = PbrAtlas.texture(PbrAtlas.SPECULAR_LAYER);
		boolean pbr = pbrNormal != 0 && pbrSpecular != 0;
		bindUnit(PBR_NORMAL_UNIT, pbr ? pbrNormal : 0, 0);
		bindUnit(PBR_SPECULAR_UNIT, pbr ? pbrSpecular : 0, 0);
		GL41.glActiveTexture(GL_TEXTURE0);
		for (Shader shader : litShaders()) {
			if (!shader.isEnabled()) {
				continue;
			}
			shader.bind();
			this.shadows.applyUniforms(shader);
			shader.uniformInt("btsPbrOn", pbr ? 1 : 0);
			shader.uniformInt("btsEyeInWater", eyeInWater ? 1 : 0);
			shader.uniformInt("btsSkyFog", skyFog && !eyeInWater ? 1 : 0);
		}
		GL41.glUseProgram(0);
		this.ready = true;
	}

	private static int placeholderShadow = 0;

	/**
	 * 1x1 depth texture (depth 1 = fully lit) with depth comparison enabled on the texture itself. Bound
	 * to the units our shaders read through sampler2DShadow whenever there is no real shadow map: with
	 * no texture there, drivers report "shadow sampler on a non-depth texture" for every draw call, and
	 * with GL debug logging on (BTA enables it on GL 4.3) that floods the log and costs most of the fps.
	 */
	public static int placeholderShadow() {
		if (placeholderShadow == 0) {
			placeholderShadow = GL41.glGenTextures();
			GL41.glBindTexture(GL41.GL_TEXTURE_2D, placeholderShadow);
			GL41.glTexImage2D(GL41.GL_TEXTURE_2D, 0, GL41.GL_DEPTH_COMPONENT32F, 1, 1, 0, GL41.GL_DEPTH_COMPONENT,
				GL41.GL_FLOAT, new float[]{1.0F});
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_MIN_FILTER, GL41.GL_NEAREST);
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_MAG_FILTER, GL41.GL_NEAREST);
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_COMPARE_MODE, GL41.GL_COMPARE_REF_TO_TEXTURE);
			GL41.glTexParameteri(GL41.GL_TEXTURE_2D, GL41.GL_TEXTURE_COMPARE_FUNC, GL41.GL_LEQUAL);
			GL41.glBindTexture(GL41.GL_TEXTURE_2D, 0);
		}
		return placeholderShadow;
	}

	private static void bindUnit(int unit, int texture, int sampler) {
		GL41.glActiveTexture(GL_TEXTURE0 + unit);
		GL41.glBindTexture(GL41.GL_TEXTURE_2D, texture);
		GL41.glBindSampler(unit, sampler);
	}

	/** End of the frame: nothing of ours stays bound on units 4.. (they may be render targets next frame). */
	public static void clearUnits() {
		for (int unit = SHADOW_UNIT; unit <= LAST_UNIT; unit++) {
			boolean shadowSampler = unit == SHADOW_UNIT || unit == SHADOW_TRANSLUCENT_UNIT;
			bindUnit(unit, shadowSampler ? placeholderShadow() : 0, 0);
		}
		GL41.glActiveTexture(GL_TEXTURE0);
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
		GL41.glBindSampler(SHADOW_TRANSLUCENT_UNIT, 0);
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
