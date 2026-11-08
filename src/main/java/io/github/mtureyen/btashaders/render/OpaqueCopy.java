package io.github.mtureyen.btashaders.render;

import org.lwjgl.opengl.GL41;

/**
 * Copy of the opaque scene taken right before the translucent pass: RGBA16F color + R32F depth (as a
 * color texture, so it doesn't depend on the depth buffer's internal format). Water samples both.
 */
public final class OpaqueCopy {
	private static final int GL_TEXTURE_2D = 3553;
	private static final int GL_FRAMEBUFFER = 36160;
	private static final int GL_COLOR_ATTACHMENT0 = 36064;
	private static final int GL_COLOR_ATTACHMENT1 = 36065;
	private static final int GL_RGBA16F = 34842;
	private static final int GL_R32F = 33326;
	private static final int GL_RGBA = 6408;
	private static final int GL_RED = 6403;
	private static final int GL_FLOAT = 5126;
	private static final int GL_NEAREST = 9728;
	private static final int GL_LINEAR = 9729;
	private static final int GL_CLAMP_TO_EDGE = 33071;

	private int fbo = 0;
	private int color = 0;
	private int depth = 0;
	private int width = 0;
	private int height = 0;

	public void ensureSize(int width, int height) {
		if (this.fbo != 0 && this.width == width && this.height == height) {
			return;
		}
		this.delete();
		this.width = width;
		this.height = height;
		this.color = makeTexture(GL_RGBA16F, GL_RGBA, GL_LINEAR, width, height);
		this.depth = makeTexture(GL_R32F, GL_RED, GL_NEAREST, width, height);
		this.fbo = GL41.glGenFramebuffers();
		GL41.glBindFramebuffer(GL_FRAMEBUFFER, this.fbo);
		GL41.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.color, 0);
		GL41.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, this.depth, 0);
		GL41.glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1});
		GL41.glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	private static int makeTexture(int internalFormat, int format, int filter, int width, int height) {
		int tex = GL41.glGenTextures();
		GL41.glBindTexture(GL_TEXTURE_2D, tex);
		GL41.glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, GL_FLOAT, 0L);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_MIN_FILTER, filter);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_MAG_FILTER, filter);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		GL41.glBindTexture(GL_TEXTURE_2D, 0);
		return tex;
	}

	public void bindForWriting() {
		GL41.glBindFramebuffer(GL_FRAMEBUFFER, this.fbo);
		GL41.glViewport(0, 0, this.width, this.height);
	}

	public int getColor() {
		return this.color;
	}

	public int getDepth() {
		return this.depth;
	}

	public void delete() {
		if (this.fbo != 0) {
			GL41.glDeleteFramebuffers(this.fbo);
			this.fbo = 0;
		}
		if (this.color != 0) {
			GL41.glDeleteTextures(this.color);
			this.color = 0;
		}
		if (this.depth != 0) {
			GL41.glDeleteTextures(this.depth);
			this.depth = 0;
		}
		this.width = 0;
		this.height = 0;
	}
}
