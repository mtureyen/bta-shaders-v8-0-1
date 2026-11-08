package io.github.mtureyen.btashaders.render.pass;

import io.github.mtureyen.btashaders.render.ColorTarget;
import io.github.mtureyen.btashaders.render.FrameState;
import io.github.mtureyen.btashaders.render.GBuffer;
import io.github.mtureyen.btashaders.render.SceneUniforms;
import io.github.mtureyen.btashaders.render.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderHelper;
import net.minecraft.client.render.shader.framebuffer.FrameBufferAttachment;

/**
 * prepare.fsh (HDR, render resolution): SSAO, deferred reflections, our sky + clouds. Its output is
 * mipmapped so eye adaptation can average it.
 */
public final class PreparePass {
	private final Shader shader = new Shader();
	private final ColorTarget target = new ColorTarget(ColorTarget.GL_RGBA16F, true);

	public void reload() {
		PassUtil.compile(this.shader, "prepare", "no SSAO / reflections / own sky");
	}

	public boolean isEnabled() {
		return this.shader.isEnabled();
	}

	public ColorTarget target() {
		return this.target;
	}

	/** Returns the prepared HDR texture. */
	public int run(Minecraft mc, FrameState frame, ShadowRenderer shadows, GBuffer gbuffer,
				   FrameBufferAttachment worldColor, FrameBufferAttachment worldDepth) {
		this.target.ensureSize(frame.width, frame.height);
		this.target.bindForWriting();
		Shader s = this.shader;
		ShaderHelper.setUniforms(mc, s, mc.timer.partialTicks); // binds; time, eye in liquid
		PassUtil.bindAttachment(s, "colortex0", 0, worldColor);
		PassUtil.bindAttachment(s, "depthtex0", 1, worldDepth);
		SceneUniforms.atmosphere(s, frame, shadows);
		SceneUniforms.clouds(s, mc, shadows, frame.ownSky);
		s.uniformInt("btsOwnSky", frame.ownSky ? 1 : 0);
		boolean gbufferOn = gbuffer.isValid();
		PassUtil.bindTexture(s, "btsGNormal", 2, gbufferOn ? gbuffer.getNormalTexture() : 0);
		PassUtil.bindTexture(s, "btsGData", 3, gbufferOn ? gbuffer.getDataTexture() : 0);
		s.uniformInt("btsGBufferOn", gbufferOn ? 1 : 0);
		s.uniformMat4f("btsView", frame.view);
		// Depth reconstruction needs the projection the scene was drawn with (jittered).
		s.uniformMat4f("btsProjInv", frame.jitteredProjectionInverse());
		s.uniformMat4f("btsProj", frame.projectionJittered);
		s.uniformVec2f("btsViewSize", frame.width, frame.height);
		s.uniformFloat("btsFrame", frame.noiseFrame());
		ShaderHelper.drawFullscreenRect();
		return this.target.getTexture();
	}

	public void delete() {
		this.target.delete();
	}
}
