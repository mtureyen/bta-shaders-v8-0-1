package io.github.mtureyen.btashaders.render.pass;

import io.github.mtureyen.btashaders.render.GBuffer;
import io.github.mtureyen.btashaders.render.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderHelper;
import net.minecraft.client.render.shader.framebuffer.FrameBuffer;
import net.minecraft.client.render.shader.framebuffer.FrameBufferAttachment;
import net.minecraft.client.render.shader.framebuffer.FrameBufferSingleSample;
import net.minecraft.client.render.shader.framebuffer.FrameBufferTextureSingleSample;
import org.joml.Vector2i;
import org.lwjgl.opengl.GL41;

import java.util.Objects;

/**
 * Window resolution: BTA's own post shader (color correction etc.; its bloom/FXAA switched off where
 * ours replace them) into a post buffer, then composite.fsh (sharpening, grading, debug views) into
 * BTA's game framebuffer.
 */
public final class CompositePass {
	private static final int GL_RGBA = 6408;
	private static final int GL_COLOR_ATTACHMENT0 = 36064;
	private static final int GL_LINEAR = 9729;
	private static final int GL_TEXTURE_MIN_FILTER = 10241;
	private static final int GL_TEXTURE_MAG_FILTER = 10240;
	private static final int GL_TEXTURE_WRAP_S = 10242;
	private static final int GL_TEXTURE_WRAP_T = 10243;
	private static final int GL_CLAMP_TO_EDGE = 33071;

	private final Shader shader = new Shader();
	private FrameBuffer postBuffer = null;
	private int postWidth = -1;
	private int postHeight = -1;

	public void reload() {
		PassUtil.compile(this.shader, "composite", "using stock BTA post-processing");
	}

	public boolean isEnabled() {
		return this.shader.isEnabled();
	}

	/** Debug options for the final image. */
	public record Debug(boolean shadowMap, int gbufferView) {
	}

	/**
	 * @param postShader  BTA's post shader ("post/post"), may be disabled
	 * @param toGameFb    true: draw into {@code gameFb}; false: straight to the default framebuffer
	 * @param noFxaa      TAA resolved the frame: BTA's FXAA would only blur it again
	 * @param ownBloom    our HDR bloom ran: BTA's LDR bloom stays off
	 */
	public void run(Minecraft mc, float partialTicks, Shader postShader, FrameBuffer gameFb, boolean toGameFb,
					int fbWidth, int fbHeight, FrameBufferAttachment worldColor, FrameBufferAttachment worldDepth,
					int sceneTexture, boolean noFxaa, boolean ownBloom, ShadowRenderer shadows, GBuffer gbuffer,
					Debug debug) {
		this.ensurePostBuffer(fbWidth, fbHeight);

		// BTA post -> postBuffer.
		this.postBuffer.bind();
		GL41.glViewport(0, 0, fbWidth, fbHeight);
		boolean postRan = false;
		if (postShader.isEnabled()) {
			postShader.bind();
			PassUtil.bindScene(postShader, "colortex0", 0, worldColor, sceneTexture);
			PassUtil.bindAttachment(postShader, "depthtex0", 1, worldDepth);
			mc.ppm.enabled = true;
			ShaderHelper.setUniforms(mc, postShader, partialTicks);
			mc.ppm.enabled = false;
			if (noFxaa) {
				postShader.uniformBool("fxaa", false);
			}
			if (ownBloom) {
				postShader.uniformInt("bloom", 0);
			}
			ShaderHelper.drawFullscreenRect();
			postRan = true;
		}

		// Composite -> game framebuffer.
		if (toGameFb) {
			gameFb.bind();
		} else {
			gameFb.unbind();
		}
		GL41.glViewport(0, 0, fbWidth, fbHeight);
		Shader s = this.shader;
		s.bind();
		if (postRan) {
			PassUtil.bindAttachment(s, "colortex0", 0,
				Objects.requireNonNull(this.postBuffer.getAttachment(FrameBuffer.AttachmentType.COLOR)));
		} else {
			PassUtil.bindScene(s, "colortex0", 0, worldColor, sceneTexture);
		}
		PassUtil.bindAttachment(s, "depthtex0", 1, worldDepth);
		boolean showShadowMap = debug.shadowMap() && shadows.isShadowMapValid();
		if (showShadowMap) {
			PassUtil.bindTexture(s, "shadowtex0", 2, shadows.getDepthTexture());
		}
		ShaderHelper.setUniforms(mc, s, partialTicks);
		s.uniformBool("btsDebugShadowMap", showShadowMap);
		int view = gbuffer.isValid() ? debug.gbufferView() : 0;
		if (view > 0) {
			PassUtil.bindTexture(s, "btsGNormal", 3, gbuffer.getNormalTexture());
			PassUtil.bindTexture(s, "btsGData", 4, gbuffer.getDataTexture());
		}
		s.uniformInt("btsDebugView", view);
		ShaderHelper.drawFullscreenRect();
	}

	private void ensurePostBuffer(int width, int height) {
		if (this.postBuffer != null && this.postWidth == width && this.postHeight == height) {
			return;
		}
		if (this.postBuffer != null) {
			this.postBuffer.delete();
		}
		this.postWidth = width;
		this.postHeight = height;
		this.postBuffer = new FrameBufferSingleSample(new FrameBufferTextureSingleSample(
			GL_RGBA, GL_COLOR_ATTACHMENT0, new Vector2i(width, height), parameters -> {
				parameters.set(GL_TEXTURE_MIN_FILTER, GL_LINEAR);
				parameters.set(GL_TEXTURE_MAG_FILTER, GL_LINEAR);
				parameters.set(GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
				parameters.set(GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			}));
	}

	public void delete() {
		if (this.postBuffer != null) {
			this.postBuffer.delete();
			this.postBuffer = null;
		}
		this.postWidth = -1;
		this.postHeight = -1;
	}
}
