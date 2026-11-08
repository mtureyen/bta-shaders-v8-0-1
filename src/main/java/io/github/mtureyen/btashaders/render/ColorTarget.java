package io.github.mtureyen.btashaders.render;

import org.lwjgl.opengl.GL41;

/**
 * A single color texture + framebuffer with an arbitrary internal format (BTA's framebuffer helper
 * only does 8-bit RGBA). Used for TAA history (RGBA16F).
 */
public final class ColorTarget {
	public static final int GL_RGBA16F = 34842;

	private static final int GL_TEXTURE_2D = 3553;
	private static final int GL_FRAMEBUFFER = 36160;
	private static final int GL_COLOR_ATTACHMENT0 = 36064;
	private static final int GL_RGBA = 6408;
	private static final int GL_FLOAT = 5126;
	private static final int GL_LINEAR = 9729;
	private static final int GL_CLAMP_TO_EDGE = 33071;

	private final int internalFormat;
	private int texture = 0;
	private int fbo = 0;
	private int width = 0;
	private int height = 0;

	public ColorTarget(int internalFormat) {
		this.internalFormat = internalFormat;
	}

	/** (Re)creates the target if the size changed. Returns true if it was (re)created. */
	public boolean ensureSize(int width, int height) {
		if (this.texture != 0 && this.width == width && this.height == height) {
			return false;
		}
		this.delete();
		this.width = width;
		this.height = height;
		this.texture = GL41.glGenTextures();
		GL41.glBindTexture(GL_TEXTURE_2D, this.texture);
		GL41.glTexImage2D(GL_TEXTURE_2D, 0, this.internalFormat, width, height, 0, GL_RGBA, GL_FLOAT, 0L);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		GL41.glBindTexture(GL_TEXTURE_2D, 0);
		this.fbo = GL41.glGenFramebuffers();
		GL41.glBindFramebuffer(GL_FRAMEBUFFER, this.fbo);
		GL41.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.texture, 0);
		GL41.glBindFramebuffer(GL_FRAMEBUFFER, 0);
		return true;
	}

	public void bindForWriting() {
		GL41.glBindFramebuffer(GL_FRAMEBUFFER, this.fbo);
		GL41.glViewport(0, 0, this.width, this.height);
	}

	public int getTexture() {
		return this.texture;
	}

	public void delete() {
		if (this.fbo != 0) {
			GL41.glDeleteFramebuffers(this.fbo);
			this.fbo = 0;
		}
		if (this.texture != 0) {
			GL41.glDeleteTextures(this.texture);
			this.texture = 0;
		}
		this.width = 0;
		this.height = 0;
	}
}
