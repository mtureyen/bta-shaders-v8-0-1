package io.github.mtureyen.btashaders.render;

import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.ShaderHelper;
import org.lwjgl.opengl.GL41;

/**
 * HDR bloom: downsample the scene into a mip chain (13-tap filter), then walk back up with a tent
 * filter, accumulating every level. The result is a wide, smooth glow that the tonemap pass mixes in
 * energy-conservingly (no brightness threshold, like Complementary/Photon).
 */
public final class BloomRenderer {
	public static final int LEVELS = 6;

	private static final int GL_TEXTURE_2D = 3553;
	private static final int GL_TEXTURE0 = 33984;

	private final Shader down = new Shader();
	private final Shader up = new Shader();
	private final ColorTarget[] downTargets = new ColorTarget[LEVELS];
	private final ColorTarget[] upTargets = new ColorTarget[LEVELS - 1];
	private final int[] widths = new int[LEVELS];
	private final int[] heights = new int[LEVELS];

	public BloomRenderer() {
		for (int i = 0; i < LEVELS; i++) {
			this.downTargets[i] = new ColorTarget(ColorTarget.GL_RGBA16F);
		}
		for (int i = 0; i < LEVELS - 1; i++) {
			this.upTargets[i] = new ColorTarget(ColorTarget.GL_RGBA16F);
		}
	}

	public void reload() {
		compile(this.down, "bloom_down");
		compile(this.up, "bloom_up");
		if (this.down.isEnabled()) {
			this.down.bind();
			this.down.uniformInt("btsSrc", 0);
		}
		if (this.up.isEnabled()) {
			this.up.bind();
			this.up.uniformInt("btsSrc", 0);
			this.up.uniformInt("btsBase", 1);
		}
		GL41.glUseProgram(0);
	}

	private static void compile(Shader shader, String name) {
		shader.delete();
		shader.compile(BtaShadersRenderer.MOD_SHADERS, name);
	}

	public boolean isAvailable() {
		return this.down.isEnabled() && this.up.isEnabled();
	}

	/**
	 * @param sceneTexture gamma-encoded HDR scene at render resolution
	 * @return linear HDR bloom texture (half resolution), sum of all levels (scale by 1/LEVELS)
	 */
	public int render(int sceneTexture, int width, int height) {
		int w = width;
		int h = height;
		for (int i = 0; i < LEVELS; i++) {
			w = Math.max(1, w / 2);
			h = Math.max(1, h / 2);
			this.widths[i] = w;
			this.heights[i] = h;
			this.downTargets[i].ensureSize(w, h);
			if (i < LEVELS - 1) {
				this.upTargets[i].ensureSize(w, h);
			}
		}

		// Down: scene -> level 0 -> ... -> level LEVELS-1
		this.down.bind();
		int srcTexture = sceneTexture;
		int srcW = width;
		int srcH = height;
		for (int i = 0; i < LEVELS; i++) {
			this.downTargets[i].bindForWriting();
			GL41.glActiveTexture(GL_TEXTURE0);
			GL41.glBindTexture(GL_TEXTURE_2D, srcTexture);
			this.down.uniformVec2f("btsSrcTexel", 1.0F / srcW, 1.0F / srcH);
			this.down.uniformInt("btsFirstLevel", i == 0 ? 1 : 0);
			ShaderHelper.drawFullscreenRect();
			srcTexture = this.downTargets[i].getTexture();
			srcW = this.widths[i];
			srcH = this.heights[i];
		}

		// Up: accumulate from the smallest level back to level 0.
		this.up.bind();
		int accum = this.downTargets[LEVELS - 1].getTexture();
		int accumW = this.widths[LEVELS - 1];
		int accumH = this.heights[LEVELS - 1];
		for (int i = LEVELS - 2; i >= 0; i--) {
			this.upTargets[i].bindForWriting();
			GL41.glActiveTexture(GL_TEXTURE0);
			GL41.glBindTexture(GL_TEXTURE_2D, accum);
			GL41.glActiveTexture(GL_TEXTURE0 + 1);
			GL41.glBindTexture(GL_TEXTURE_2D, this.downTargets[i].getTexture());
			GL41.glActiveTexture(GL_TEXTURE0);
			this.up.uniformVec2f("btsSrcTexel", 1.0F / accumW, 1.0F / accumH);
			ShaderHelper.drawFullscreenRect();
			accum = this.upTargets[i].getTexture();
			accumW = this.widths[i];
			accumH = this.heights[i];
		}
		return accum;
	}

	public void delete() {
		for (ColorTarget t : this.downTargets) {
			t.delete();
		}
		for (ColorTarget t : this.upTargets) {
			t.delete();
		}
	}
}
