package io.github.mtureyen.btashaders.render.pass;

import io.github.mtureyen.btashaders.render.FullscreenQuad;
import io.github.mtureyen.btashaders.render.GBuffer;
import io.github.mtureyen.btashaders.render.LightingState;
import io.github.mtureyen.btashaders.render.OpaqueCopy;
import net.minecraft.client.render.OpenGLHelper;
import net.minecraft.client.render.renderer.Shaders;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.framebuffer.FrameBuffer;
import net.minecraft.client.render.shader.framebuffer.FrameBufferAttachment;
import org.lwjgl.opengl.GL41;

/**
 * Around BTA's translucent terrain draw: copies the opaque scene (color + depth) so water can refract
 * and reflect it, flags the water branch of our terrain shader, and lets translucents write the
 * G-buffer. Runs in the middle of BTA's rendering, so it saves and restores the GL state it touches.
 */
public final class WaterPass {
	private final Shader copyShader = new Shader();
	private final OpaqueCopy opaqueCopy = new OpaqueCopy();
	private final FullscreenQuad quad = new FullscreenQuad();
	private boolean active = false;

	public void reload() {
		PassUtil.compile(this.copyShader, "copy", "no water reflections/refraction");
		if (this.copyShader.isEnabled()) {
			this.copyShader.bind();
			this.copyShader.uniformInt("btsSrcColor", LightingState.OPAQUE_COLOR_UNIT);
			this.copyShader.uniformInt("btsSrcDepth", LightingState.OPAQUE_DEPTH_UNIT);
			GL41.glUseProgram(0);
		}
	}

	/** Before translucent terrain. {@code worldFb} is BTA's world framebuffer (bound again afterwards). */
	public void begin(FrameBuffer worldFb, int width, int height, GBuffer gbuffer) {
		if (!this.copyShader.isEnabled() || worldFb == null || !Shaders.TERRAIN.isEnabled()) {
			return;
		}
		FrameBuffer worldOut = worldFb.getOutputBuffer();
		FrameBufferAttachment worldColor = worldOut.getAttachment(FrameBuffer.AttachmentType.COLOR);
		FrameBufferAttachment worldDepth = worldOut.getAttachment(FrameBuffer.AttachmentType.DEPTH);
		if (worldColor == null || worldDepth == null) {
			return;
		}
		boolean depthTest = GL41.glIsEnabled(GL41.GL_DEPTH_TEST);
		boolean blend = GL41.glIsEnabled(GL41.GL_BLEND);
		boolean cull = GL41.glIsEnabled(GL41.GL_CULL_FACE);
		int program = GL41.glGetInteger(GL41.GL_CURRENT_PROGRAM);
		int activeTexture = GL41.glGetInteger(GL41.GL_ACTIVE_TEXTURE);
		int vao = GL41.glGetInteger(GL41.GL_VERTEX_ARRAY_BINDING);
		GL41.glDisable(GL41.GL_DEPTH_TEST);
		GL41.glDisable(GL41.GL_BLEND);
		GL41.glDisable(GL41.GL_CULL_FACE);

		this.opaqueCopy.ensureSize(width, height);
		this.opaqueCopy.bindForWriting();
		this.copyShader.bind();
		GL41.glActiveTexture(PassUtil.GL_TEXTURE0 + LightingState.OPAQUE_COLOR_UNIT);
		worldColor.bindForOutput();
		GL41.glActiveTexture(PassUtil.GL_TEXTURE0 + LightingState.OPAQUE_DEPTH_UNIT);
		worldDepth.bindForOutput();
		this.quad.draw();

		// Leave the copies bound on those units for the water shader.
		GL41.glActiveTexture(PassUtil.GL_TEXTURE0 + LightingState.OPAQUE_COLOR_UNIT);
		GL41.glBindTexture(PassUtil.GL_TEXTURE_2D, this.opaqueCopy.getColor());
		GL41.glActiveTexture(PassUtil.GL_TEXTURE0 + LightingState.OPAQUE_DEPTH_UNIT);
		GL41.glBindTexture(PassUtil.GL_TEXTURE_2D, this.opaqueCopy.getDepth());
		GL41.glActiveTexture(activeTexture);

		worldFb.bind();
		GL41.glViewport(0, 0, width, height);
		Shaders.TERRAIN.bind();
		Shaders.TERRAIN.uniformInt("btsWaterPass", 1);
		this.active = true;
		// Water and tinted glass write the G-buffer too, so screen-space passes see them instead of
		// whatever lies underneath (their own reflections are done in the water shader).
		gbuffer.setMrt(true);

		GL41.glBindVertexArray(vao);
		GL41.glUseProgram(program);
		if (depthTest) GL41.glEnable(GL41.GL_DEPTH_TEST);
		if (blend) GL41.glEnable(GL41.GL_BLEND);
		if (cull) GL41.glEnable(GL41.GL_CULL_FACE);
		OpenGLHelper.checkError("btashaders opaque copy");
	}

	/** After translucent terrain (also called defensively at the end of the world pass). */
	public void end(GBuffer gbuffer) {
		if (!this.active) {
			return;
		}
		int program = GL41.glGetInteger(GL41.GL_CURRENT_PROGRAM);
		Shaders.TERRAIN.bind();
		Shaders.TERRAIN.uniformInt("btsWaterPass", 0);
		gbuffer.setMrt(false);
		GL41.glUseProgram(program);
		this.active = false;
	}

	public void delete() {
		this.opaqueCopy.delete();
		this.quad.delete();
	}
}
