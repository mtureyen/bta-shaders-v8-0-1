package io.github.mtureyen.btashaders.render;

import io.github.mtureyen.btashaders.BtaShaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.LightmapHelper;
import net.minecraft.client.render.OpenGLHelper;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderHelper;
import net.minecraft.client.render.shader.ShaderProvider;
import net.minecraft.client.render.shader.ShadersRenderer;
import net.minecraft.client.render.shader.framebuffer.FrameBuffer;
import net.minecraft.client.render.shader.framebuffer.FrameBufferAttachment;
import net.minecraft.client.render.shader.framebuffer.FrameBufferSingleSample;
import net.minecraft.client.render.shader.framebuffer.FrameBufferTextureSingleSample;
import org.joml.Vector2i;
import org.lwjgl.opengl.GL41;

import java.util.Objects;

/**
 * Replaces BTA's stock {@link ShadersRenderer}.
 *
 * <p>Frame flow at the end of world rendering:
 * <pre>
 *   worldFrameBuffer (color + depth)
 *     -> stock post shader (bloom, FXAA, color correction)  -> postBuffer
 *     -> our composite shader (reads postBuffer + world depth) -> gameFrameBuffer
 * </pre>
 * If our composite shader fails to compile, we fall back to the stock path, so a broken
 * shader never means a black screen.
 */
public class BtaShadersRenderer extends ShadersRenderer {
	private static final int GL_RGBA = 6408;
	private static final int GL_COLOR_ATTACHMENT0 = 36064;
	private static final int GL_LINEAR = 9729;
	private static final int GL_TEXTURE_MIN_FILTER = 10241;
	private static final int GL_TEXTURE_MAG_FILTER = 10240;
	private static final int GL_TEXTURE_WRAP_S = 10242;
	private static final int GL_TEXTURE_WRAP_T = 10243;
	private static final int GL_CLAMP_TO_EDGE = 33071;
	private static final int GL_TEXTURE0 = 33984;
	private static final int GL_TEXTURE1 = 33985;

	/** Matches the near plane in WorldRenderer.getProjectionMatrix. */
	private static final float NEAR_PLANE = 0.05F;

	private final ShaderProvider modShaders = new ModShaderProvider("/assets/btashaders/shaders/");
	private final Shader compositeShader = new Shader();

	private FrameBuffer postBuffer = null;
	private int postWidth = -1;
	private int postHeight = -1;

	public BtaShadersRenderer(Minecraft minecraft) {
		super(minecraft);
	}

	@Override
	public void endRenderWorld(float partialTicks) {
		if (!this.compositeShader.isEnabled()) {
			super.endRenderWorld(partialTicks);
			return;
		}

		OpenGLHelper.checkError("pre btashaders end render world");
		FrameBuffer worldFb = Objects.requireNonNull(this.worldFrameBuffer);
		FrameBuffer gameFb = Objects.requireNonNull(this.gameFrameBuffer);
		this.ensurePostBuffer();

		worldFb.blit();
		FrameBuffer worldOut = worldFb.getOutputBuffer();
		FrameBufferAttachment worldColor = Objects.requireNonNull(worldOut.getAttachment(FrameBuffer.AttachmentType.COLOR));
		FrameBufferAttachment worldDepth = Objects.requireNonNull(worldOut.getAttachment(FrameBuffer.AttachmentType.DEPTH));

		// Pass 1: stock post shader -> postBuffer. Skipped if the stock post shader is unavailable,
		// in which case the composite pass reads the raw world color instead.
		FrameBufferAttachment compositeInput = worldColor;
		if (this.postShader.isEnabled()) {
			this.postBuffer.bind();
			GL41.glViewport(0, 0, this.fbWidth, this.fbHeight);
			this.postShader.bind();
			GL41.glActiveTexture(GL_TEXTURE0);
			worldColor.bindForOutput();
			this.postShader.uniformInt("colortex0", 0);
			GL41.glActiveTexture(GL_TEXTURE1);
			worldDepth.bindForOutput();
			this.postShader.uniformInt("depthtex0", 1);
			GL41.glActiveTexture(GL_TEXTURE0);
			this.mc.ppm.enabled = true;
			ShaderHelper.setUniforms(this.mc, this.postShader, partialTicks);
			this.mc.ppm.enabled = false;
			ShaderHelper.drawFullscreenRect();
			compositeInput = Objects.requireNonNull(this.postBuffer.getAttachment(FrameBuffer.AttachmentType.COLOR));
		}

		// Pass 2: our composite -> game framebuffer (or the default framebuffer when the final shader is off,
		// mirroring what the stock renderer does).
		if (this.finalShader.isEnabled()) {
			gameFb.bind();
		} else {
			gameFb.unbind();
		}
		GL41.glViewport(0, 0, this.fbWidth, this.fbHeight);

		this.compositeShader.bind();
		GL41.glActiveTexture(GL_TEXTURE0);
		compositeInput.bindForOutput();
		this.compositeShader.uniformInt("colortex0", 0);
		GL41.glActiveTexture(GL_TEXTURE1);
		worldDepth.bindForOutput();
		this.compositeShader.uniformInt("depthtex0", 1);
		GL41.glActiveTexture(GL_TEXTURE0);
		ShaderHelper.setUniforms(this.mc, this.compositeShader, partialTicks);
		this.compositeShader.uniformFloat("near", NEAR_PLANE);
		this.compositeShader.uniformFloat("far", this.mc.worldRenderer.farPlaneDistance * 2.0F);
		ShaderHelper.drawFullscreenRect();

		LightmapHelper.instance.enableLightmapRendering();
		GL41.glUseProgram(0);
		OpenGLHelper.checkError("btashaders end render world");
	}

	private void ensurePostBuffer() {
		if (this.postBuffer != null && this.postWidth == this.fbWidth && this.postHeight == this.fbHeight) {
			return;
		}
		if (this.postBuffer != null) {
			this.postBuffer.delete();
		}
		this.postWidth = this.fbWidth;
		this.postHeight = this.fbHeight;
		this.postBuffer = new FrameBufferSingleSample(new FrameBufferTextureSingleSample(
			GL_RGBA, GL_COLOR_ATTACHMENT0, new Vector2i(this.fbWidth, this.fbHeight), parameters -> {
				parameters.set(GL_TEXTURE_MIN_FILTER, GL_LINEAR);
				parameters.set(GL_TEXTURE_MAG_FILTER, GL_LINEAR);
				parameters.set(GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
				parameters.set(GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			}));
	}

	@Override
	public void reload() {
		super.reload();
		this.compositeShader.delete();
		this.compositeShader.compile(this.modShaders, "composite");
		if (this.compositeShader.isEnabled()) {
			BtaShaders.LOGGER.info("Composite shader compiled.");
		} else {
			BtaShaders.LOGGER.warn("Composite shader failed to compile; using stock BTA post-processing.");
		}
		OpenGLHelper.checkError("btashaders reload");
	}

	@Override
	public void delete() {
		super.delete();
		if (this.postBuffer != null) {
			this.postBuffer.delete();
			this.postBuffer = null;
		}
		this.postWidth = -1;
		this.postHeight = -1;
	}
}
