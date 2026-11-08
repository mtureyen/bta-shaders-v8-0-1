package io.github.mtureyen.btashaders.render;

import net.minecraft.client.render.shader.framebuffer.FrameBufferTexture;
import net.minecraft.client.render.shader.framebuffer.FrameBufferTextureSingleSample;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2ic;
import org.lwjgl.opengl.GL41;

import java.util.function.Consumer;

/**
 * RGBA16F color attachment for BTA's world framebuffer, so our lighting can go above 1.0 (bright
 * sunlight, glints) and gets tone mapped later instead of clipping. BTA's helper only allocates RGBA8.
 */
public class HdrColorTexture extends FrameBufferTextureSingleSample {
	private static final int GL_TEXTURE_2D = 3553;
	private static final int GL_RGBA16F = 34842;
	private static final int GL_RGBA = 6408;
	private static final int GL_FLOAT = 5126;
	private static final int GL_COLOR_ATTACHMENT0 = 36064;

	public HdrColorTexture(@NotNull Vector2ic size, @Nullable Consumer<FrameBufferTexture.Parameters> parameters) {
		super(GL_RGBA16F, GL_COLOR_ATTACHMENT0, size, parameters);
	}

	@Override
	protected void init(int format, @NotNull Vector2ic size, int samples) {
		GL41.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, size.x(), size.y(), 0, GL_RGBA, GL_FLOAT, 0L);
	}
}
