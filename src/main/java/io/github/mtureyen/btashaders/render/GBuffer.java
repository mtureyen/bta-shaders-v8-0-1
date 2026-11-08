package io.github.mtureyen.btashaders.render;

import org.lwjgl.opengl.GL41;

/**
 * Extra render targets attached to BTA's world framebuffer (attachments 1 and 2; BTA's own class only
 * allows one color attachment, so we attach these to its FBO directly):
 * <pre>
 *   gNormal (RGBA16F): xyz = world normal * 0.5 + 0.5, w = material code / 16 (lib/gbuffer.glsl)
 *   gData   (RGBA8):   r = sky light, g = block light, b = direct sun visibility, a = 1 if written
 *   gMotion (RGBA16F): xyz = how far the object moved since last frame (blocks; entities only), w = 1
 * </pre>
 * Written by our world shaders only: multiple render targets are switched on just around our own
 * draws (solid terrain, entities), so BTA's stock draws (particles, outlines, name tags, weather)
 * never leave undefined values in them.
 */
public final class GBuffer {
	private static final int GL_TEXTURE_2D = 3553;
	private static final int GL_FRAMEBUFFER = 36160;
	private static final int GL_DRAW_FRAMEBUFFER_BINDING = 36006;
	private static final int GL_COLOR_ATTACHMENT0 = 36064;
	private static final int GL_COLOR_ATTACHMENT1 = 36065;
	private static final int GL_COLOR_ATTACHMENT2 = 36066;
	private static final int GL_COLOR_ATTACHMENT3 = 36067;
	private static final int[] ALL_TARGETS = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3};
	private static final int GL_RGBA16F = 34842;
	private static final int GL_RGBA8 = 32856;
	private static final int GL_RGBA = 6408;
	private static final int GL_FLOAT = 5126;
	private static final int GL_NEAREST = 9728;
	private static final int GL_CLAMP_TO_EDGE = 33071;
	private static final int GL_COLOR = 6144;

	private int normalTex = 0;
	private int dataTex = 0;
	private int motionTex = 0;
	private int fbo = 0;
	private int width = 0;
	private int height = 0;
	private boolean mrtActive = false;

	/** (Re)creates the textures if needed and attaches them to the given world framebuffer. */
	public void attach(int worldFbo, int width, int height) {
		if (this.fbo == worldFbo && this.width == width && this.height == height && this.normalTex != 0) {
			return;
		}
		this.deleteTextures();
		this.width = width;
		this.height = height;
		this.normalTex = makeTexture(GL_RGBA16F, width, height);
		this.dataTex = makeTexture(GL_RGBA8, width, height);
		this.motionTex = makeTexture(GL_RGBA16F, width, height);
		this.fbo = worldFbo;
		int previous = GL41.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		GL41.glBindFramebuffer(GL_FRAMEBUFFER, worldFbo);
		GL41.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, this.normalTex, 0);
		GL41.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, this.dataTex, 0);
		GL41.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT3, GL_TEXTURE_2D, this.motionTex, 0);
		GL41.glDrawBuffers(GL_COLOR_ATTACHMENT0);
		GL41.glBindFramebuffer(GL_FRAMEBUFFER, previous);
		this.mrtActive = false;
	}

	private static int makeTexture(int internalFormat, int width, int height) {
		int tex = GL41.glGenTextures();
		GL41.glBindTexture(GL_TEXTURE_2D, tex);
		GL41.glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, GL_RGBA, GL_FLOAT, 0L);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		GL41.glTexParameteri(GL_TEXTURE_2D, GL41.GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		GL41.glBindTexture(GL_TEXTURE_2D, 0);
		return tex;
	}

	/** Start of the world pass (world framebuffer bound): reset our targets to "nothing written". */
	public void clear() {
		if (!this.isBoundToWorld()) {
			return;
		}
		GL41.glDrawBuffers(ALL_TARGETS);
		float[] zero = {0.0F, 0.0F, 0.0F, 0.0F};
		GL41.glClearBufferfv(GL_COLOR, 1, zero);
		GL41.glClearBufferfv(GL_COLOR, 2, zero);
		GL41.glClearBufferfv(GL_COLOR, 3, zero);
		GL41.glDrawBuffers(GL_COLOR_ATTACHMENT0);
		this.mrtActive = false;
	}

	/** Around our own draws: write all four targets, else only BTA's color target. */
	public void setMrt(boolean on) {
		if (on == this.mrtActive || !this.isBoundToWorld()) {
			return;
		}
		if (on) {
			GL41.glDrawBuffers(ALL_TARGETS);
		} else {
			GL41.glDrawBuffers(GL_COLOR_ATTACHMENT0);
		}
		this.mrtActive = on;
	}

	private boolean isBoundToWorld() {
		return this.fbo != 0 && GL41.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == this.fbo;
	}

	/** The world framebuffer was recreated: re-attach on the next {@link #attach}. */
	public void invalidate() {
		this.fbo = 0;
	}

	public boolean isValid() {
		return this.normalTex != 0;
	}

	public int getNormalTexture() {
		return this.normalTex;
	}

	public int getDataTexture() {
		return this.dataTex;
	}

	public int getMotionTexture() {
		return this.motionTex;
	}

	/** Detaches nothing (the world framebuffer owns its lifetime); just drops our textures. */
	public void delete() {
		this.deleteTextures();
		this.fbo = 0;
	}

	private void deleteTextures() {
		if (this.normalTex != 0) {
			GL41.glDeleteTextures(this.normalTex);
			this.normalTex = 0;
		}
		if (this.dataTex != 0) {
			GL41.glDeleteTextures(this.dataTex);
			this.dataTex = 0;
		}
		if (this.motionTex != 0) {
			GL41.glDeleteTextures(this.motionTex);
			this.motionTex = 0;
		}
		this.width = 0;
		this.height = 0;
		this.mrtActive = false;
	}
}
