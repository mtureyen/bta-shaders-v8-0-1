package io.github.mtureyen.btashaders.render.pass;

import io.github.mtureyen.btashaders.BtaShaders;
import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.shader.framebuffer.FrameBufferAttachment;
import org.lwjgl.opengl.GL41;

/** Small helpers shared by the post passes. */
public final class PassUtil {
	public static final int GL_TEXTURE_2D = 3553;
	public static final int GL_TEXTURE0 = 33984;

	private PassUtil() {
	}

	/** (Re)compiles one of our shaders; a failure only disables that pass. */
	public static void compile(Shader shader, String name, String consequence) {
		shader.delete();
		shader.compile(BtaShadersRenderer.MOD_SHADERS, name);
		if (shader.isEnabled()) {
			BtaShaders.LOGGER.info("Shader '{}' compiled.", name);
		} else {
			BtaShaders.LOGGER.warn("Shader '{}' failed to compile; {}.", name, consequence);
		}
	}

	/** Binds texture {@code texture} (or the world color when < 0) on {@code unit} for sampler {@code name}. */
	public static void bindScene(Shader shader, String name, int unit, FrameBufferAttachment worldColor, int texture) {
		GL41.glActiveTexture(GL_TEXTURE0 + unit);
		if (texture >= 0) {
			GL41.glBindTexture(GL_TEXTURE_2D, texture);
		} else {
			worldColor.bindForOutput();
		}
		shader.uniformInt(name, unit);
		GL41.glActiveTexture(GL_TEXTURE0);
	}

	public static void bindAttachment(Shader shader, String name, int unit, FrameBufferAttachment attachment) {
		GL41.glActiveTexture(GL_TEXTURE0 + unit);
		attachment.bindForOutput();
		shader.uniformInt(name, unit);
		GL41.glActiveTexture(GL_TEXTURE0);
	}

	public static void bindTexture(Shader shader, String name, int unit, int texture) {
		GL41.glActiveTexture(GL_TEXTURE0 + unit);
		GL41.glBindTexture(GL_TEXTURE_2D, Math.max(texture, 0));
		shader.uniformInt(name, unit);
		GL41.glActiveTexture(GL_TEXTURE0);
	}
}
