package io.github.mtureyen.btashaders.render;

import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderHelper;
import org.lwjgl.opengl.GL41;

/**
 * Eye adaptation, entirely on the GPU (no read-back stall): a 1x1 exposure value, ping-ponged between
 * two tiny targets. Each frame exposure.fsh reads a small mip of the HDR scene, computes the target
 * exposure and eases last frame's value towards it.
 */
public final class ExposureRenderer {
	private static final int GL_TEXTURE_2D = 3553;
	private static final int GL_TEXTURE0 = 33984;
	/** Use a mip with roughly this many pixels on its long side for the average. */
	private static final int MEASURE_SIZE = 16;

	private final Shader shader = new Shader();
	private final ColorTarget[] targets = {new ColorTarget(ColorTarget.GL_RGBA16F), new ColorTarget(ColorTarget.GL_RGBA16F)};
	private int index = 0;
	private boolean valid = false;
	private long lastNanos = 0L;

	public void reload() {
		this.shader.delete();
		this.shader.compile(BtaShadersRenderer.MOD_SHADERS, "exposure");
		if (this.shader.isEnabled()) {
			this.shader.bind();
			this.shader.uniformInt("btsScene", 0);
			this.shader.uniformInt("btsPrevExposure", 1);
			GL41.glUseProgram(0);
		}
		this.valid = false;
	}

	public boolean isAvailable() {
		return this.shader.isEnabled();
	}

	public void invalidate() {
		this.valid = false;
	}

	/**
	 * @param scene mipmapped HDR scene target (mips must be generated)
	 * @return texture holding the current exposure (1x1, red channel)
	 */
	public int update(ColorTarget scene) {
		long now = System.nanoTime();
		float dt = this.lastNanos == 0L ? 0.0F : Math.min((now - this.lastNanos) / 1.0e9F, 0.5F);
		this.lastNanos = now;

		int max = scene.maxMipLevel();
		int lod = 0;
		int size = 1 << max;
		while (size > MEASURE_SIZE && lod < max) {
			size >>= 1;
			lod++;
		}

		ColorTarget read = this.targets[this.index];
		ColorTarget write = this.targets[1 - this.index];
		read.ensureSize(1, 1);
		write.ensureSize(1, 1);

		write.bindForWriting();
		this.shader.bind();
		GL41.glActiveTexture(GL_TEXTURE0);
		GL41.glBindTexture(GL_TEXTURE_2D, scene.getTexture());
		GL41.glActiveTexture(GL_TEXTURE0 + 1);
		GL41.glBindTexture(GL_TEXTURE_2D, read.getTexture());
		GL41.glActiveTexture(GL_TEXTURE0);
		this.shader.uniformFloat("btsSceneLod", (float) lod);
		this.shader.uniformFloat("btsDeltaTime", dt);
		this.shader.uniformInt("btsExposureValid", this.valid ? 1 : 0);
		ShaderHelper.drawFullscreenRect();

		this.index = 1 - this.index;
		this.valid = true;
		return write.getTexture();
	}

	public void delete() {
		for (ColorTarget t : this.targets) {
			t.delete();
		}
		this.valid = false;
	}
}
