package io.github.mtureyen.btashaders.render.pass;

import io.github.mtureyen.btashaders.render.ColorTarget;
import io.github.mtureyen.btashaders.render.FrameState;
import io.github.mtureyen.btashaders.render.GBuffer;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderHelper;
import net.minecraft.client.render.shader.framebuffer.FrameBufferAttachment;
import org.joml.Matrix4f;

/**
 * Temporal anti-aliasing: the projection is jittered by sub-pixel Halton offsets (applied in
 * WorldRenderer.getProjectionMatrix via mixin), and taa.fsh blends each frame with the reprojected
 * history. The resolved color is also next frame's history.
 */
public final class TaaPass {
	/** Halton(2,3) sub-pixel offsets in [-0.5, 0.5] pixels. */
	private static final float[][] JITTER = buildHalton(8);
	/**
	 * Fraction of a pixel the projection is jittered by. Full-pixel jitter gives the smoothest edges but
	 * visibly softens the image once frames are averaged; half is the usual sharpness compromise.
	 */
	private static final float JITTER_SCALE = 0.5F;

	private final Shader shader = new Shader();
	private final ColorTarget[] history = {new ColorTarget(ColorTarget.GL_RGBA16F), new ColorTarget(ColorTarget.GL_RGBA16F)};
	private int historyIndex = 0;
	private boolean historyValid = false;

	public void reload() {
		PassUtil.compile(this.shader, "taa", "TAA disabled");
		this.historyValid = false;
	}

	public boolean isEnabled() {
		return this.shader.isEnabled();
	}

	public boolean isHistoryValid() {
		return this.historyValid;
	}

	public void invalidate() {
		this.historyValid = false;
	}

	/** Applies this frame's sub-pixel jitter to a projection matrix. */
	public static void jitter(Matrix4f projection, int frameIndex, int width, int height) {
		float[] j = JITTER[frameIndex % JITTER.length];
		projection.translateLocal(j[0] * JITTER_SCALE * 2.0F / width, j[1] * JITTER_SCALE * 2.0F / height, 0.0F);
	}

	public int run(FrameState frame, FrameBufferAttachment worldColor, FrameBufferAttachment worldDepth, int sceneTexture,
				   GBuffer gbuffer) {
		for (ColorTarget target : this.history) {
			if (target.ensureSize(frame.width, frame.height)) {
				this.historyValid = false;
			}
		}
		ColorTarget read = this.history[this.historyIndex];
		ColorTarget write = this.history[1 - this.historyIndex];

		write.bindForWriting();
		Shader s = this.shader;
		s.bind();
		PassUtil.bindScene(s, "colortex0", 0, worldColor, sceneTexture);
		PassUtil.bindAttachment(s, "depthtex0", 1, worldDepth);
		PassUtil.bindTexture(s, "historytex", 2, read.getTexture());
		boolean gbufferOn = gbuffer.isValid();
		if (gbufferOn) {
			PassUtil.bindTexture(s, "btsGNormal", 3, gbuffer.getNormalTexture());
			PassUtil.bindTexture(s, "btsGMotion", 4, gbuffer.getMotionTexture());
		}
		s.uniformBool("btsGBufferValid", gbufferOn);
		s.uniformMat4f("btsProjInv", frame.jitteredProjectionInverse());
		s.uniformMat4f("btsViewInv", frame.viewInverse());
		s.uniformMat4f("btsPrevViewProj", new Matrix4f(frame.prevProjection).mul(frame.prevView));
		s.uniformVec3f("btsCameraDelta",
			(float) (frame.cameraPos.x - frame.prevCameraPos.x),
			(float) (frame.cameraPos.y - frame.prevCameraPos.y),
			(float) (frame.cameraPos.z - frame.prevCameraPos.z));
		s.uniformVec2f("btsViewSize", frame.width, frame.height);
		s.uniformBool("btsHistoryValid", this.historyValid);
		ShaderHelper.drawFullscreenRect();

		this.historyIndex = 1 - this.historyIndex;
		this.historyValid = true;
		return write.getTexture();
	}

	public void delete() {
		for (ColorTarget target : this.history) {
			target.delete();
		}
		this.historyValid = false;
	}

	private static float halton(int index, int base) {
		float f = 1.0F;
		float r = 0.0F;
		int i = index;
		while (i > 0) {
			f /= base;
			r += f * (i % base);
			i /= base;
		}
		return r;
	}

	private static float[][] buildHalton(int count) {
		float[][] out = new float[count][2];
		for (int i = 0; i < count; i++) {
			out[i][0] = halton(i + 1, 2) - 0.5F;
			out[i][1] = halton(i + 1, 3) - 0.5F;
		}
		return out;
	}
}
