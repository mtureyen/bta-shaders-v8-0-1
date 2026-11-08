package io.github.mtureyen.btashaders.render.pass;

import io.github.mtureyen.btashaders.render.BloomRenderer;
import io.github.mtureyen.btashaders.render.ColorTarget;
import io.github.mtureyen.btashaders.render.ExposureRenderer;
import io.github.mtureyen.btashaders.render.FrameState;
import io.github.mtureyen.btashaders.render.GpuProfiler;
import io.github.mtureyen.btashaders.render.LightingState;
import io.github.mtureyen.btashaders.render.SceneUniforms;
import io.github.mtureyen.btashaders.render.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderHelper;
import net.minecraft.client.render.shader.framebuffer.FrameBufferAttachment;
import org.lwjgl.opengl.GL41;

/**
 * Bloom chain + eye adaptation, then tonemap.fsh: aerial haze, underwater fog, volumetric light,
 * bloom, exposure, Lottes curve. HDR in, LDR (gamma) out.
 */
public final class TonemapPass {
	private final Shader shader = new Shader();
	private final BloomRenderer bloom = new BloomRenderer();
	private final ExposureRenderer exposure = new ExposureRenderer();
	private final ColorTarget mapped = new ColorTarget(ColorTarget.GL_RGBA16F);

	public void reload() {
		PassUtil.compile(this.shader, "tonemap", "no HDR tone mapping / bloom / atmosphere effects");
		this.bloom.reload();
		this.exposure.reload();
		if (this.shader.isEnabled()) {
			ShadowRenderer.bindUniformBlocks(this.shader);
			this.shader.bind();
			this.shader.uniformInt("btsShadowTex", LightingState.OPAQUE_COLOR_UNIT); // never share unit 0
			GL41.glUseProgram(0);
		}
	}

	public boolean isEnabled() {
		return this.shader.isEnabled();
	}

	/** Eye adaptation starts over (world change, shaders toggled). */
	public void invalidate() {
		this.exposure.invalidate();
	}

	/**
	 * @param sceneTexture HDR scene from the prepare pass, or -1 for the raw world color
	 * @param prepared     the prepare pass target (mipmapped: eye adaptation), or null
	 */
	public int run(Minecraft mc, FrameState frame, ShadowRenderer shadows, GpuProfiler profiler,
				   FrameBufferAttachment worldColor, FrameBufferAttachment worldDepth,
				   int sceneTexture, ColorTarget prepared) {
		profiler.begin(GpuProfiler.Section.BLOOM);
		int exposureTexture = -1;
		if (this.exposure.isAvailable() && prepared != null && sceneTexture == prepared.getTexture()) {
			prepared.generateMipmaps();
			exposureTexture = this.exposure.update(prepared);
		}
		int bloomTexture = -1;
		if (this.bloom.isAvailable()) {
			int src = sceneTexture >= 0 ? sceneTexture : worldColor.getId(); // no prepare: straight from the world
			bloomTexture = this.bloom.render(src, frame.width, frame.height);
		}
		profiler.end(GpuProfiler.Section.BLOOM);

		profiler.begin(GpuProfiler.Section.TONEMAP);
		this.mapped.ensureSize(frame.width, frame.height);
		this.mapped.bindForWriting();
		Shader s = this.shader;
		s.bind();
		PassUtil.bindScene(s, "colortex0", 0, worldColor, sceneTexture);
		PassUtil.bindAttachment(s, "depthtex0", 1, worldDepth);
		PassUtil.bindTexture(s, "btsBloom", 2, bloomTexture);
		PassUtil.bindTexture(s, "btsExposure", 3, exposureTexture);
		s.uniformInt("btsBloomOn", bloomTexture >= 0 ? 1 : 0);
		s.uniformInt("btsExposureOn", exposureTexture >= 0 ? 1 : 0);
		s.uniformFloat("btsBloomScale", 1.0F / BloomRenderer.LEVELS);
		s.uniformVec2f("btsViewSize", frame.width, frame.height);
		ShaderHelper.setUniforms(mc, s, mc.timer.partialTicks);
		SceneUniforms.atmosphere(s, frame, shadows);
		SceneUniforms.clouds(s, mc, shadows, frame.ownSky);
		s.uniformFloat("btsFrame", frame.noiseFrame());
		// Volumetric light reads the shadow map through the compare sampler.
		boolean shadowsValid = shadows.isShadowMapValid();
		GL41.glActiveTexture(PassUtil.GL_TEXTURE0 + LightingState.OPAQUE_COLOR_UNIT);
		GL41.glBindTexture(PassUtil.GL_TEXTURE_2D, shadowsValid ? shadows.getDepthTexture() : 0);
		GL41.glBindSampler(LightingState.OPAQUE_COLOR_UNIT, shadowsValid ? shadows.getCompareSampler() : 0);
		GL41.glActiveTexture(PassUtil.GL_TEXTURE0);
		s.uniformInt("btsShadowTex", LightingState.OPAQUE_COLOR_UNIT);
		s.uniformInt("btsShadowsOn", shadowsValid ? 1 : 0);
		s.uniformMat4f("btsShadowMatrix", shadows.getShadowMatrix());
		s.uniformFloat("btsShadowStrength", shadows.getShadowStrength());
		// Colored shadows: light shafts through tinted glass / water take on their color.
		boolean colored = shadowsValid && shadows.isColoredShadowsValid();
		GL41.glActiveTexture(PassUtil.GL_TEXTURE0 + LightingState.SHADOW_TRANSLUCENT_UNIT);
		GL41.glBindTexture(PassUtil.GL_TEXTURE_2D, colored ? shadows.getTranslucentDepthTexture() : 0);
		GL41.glBindSampler(LightingState.SHADOW_TRANSLUCENT_UNIT, colored ? shadows.getCompareSampler() : 0);
		GL41.glActiveTexture(PassUtil.GL_TEXTURE0 + LightingState.SHADOW_COLOR_UNIT);
		GL41.glBindTexture(PassUtil.GL_TEXTURE_2D, colored ? shadows.getShadowColorTexture() : 0);
		GL41.glActiveTexture(PassUtil.GL_TEXTURE0);
		s.uniformInt("btsShadowTexTranslucent", LightingState.SHADOW_TRANSLUCENT_UNIT);
		s.uniformInt("btsShadowColor", LightingState.SHADOW_COLOR_UNIT);
		s.uniformInt("btsColoredShadowsOn", colored ? 1 : 0);
		ShaderHelper.drawFullscreenRect();
		GL41.glBindSampler(LightingState.OPAQUE_COLOR_UNIT, 0);
		GL41.glBindSampler(LightingState.SHADOW_TRANSLUCENT_UNIT, 0);
		profiler.end(GpuProfiler.Section.TONEMAP);
		return this.mapped.getTexture();
	}

	public void delete() {
		this.mapped.delete();
		this.bloom.delete();
		this.exposure.delete();
	}
}
