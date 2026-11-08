package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.ShadowRenderer;
import net.minecraft.client.render.renderer.GLRenderer;
import net.minecraft.client.render.shader.Shader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * During the entity shadow pass, BTA's stock shaders (name-tag font, colored lines, ...) are swapped
 * for a shader that draws nothing; they don't know the shadow-map distortion. See ShadowRenderer.
 */
@Mixin(value = GLRenderer.class, remap = false)
public abstract class GLRendererMixin {
	@ModifyVariable(method = "setShader", at = @At("HEAD"), argsOnly = true)
	private static Shader btashaders$substituteSetShader(Shader shader) {
		return ShadowRenderer.substituteShader(shader);
	}

	@ModifyVariable(method = "globalSetUniforms", at = @At("HEAD"), argsOnly = true)
	private static Shader btashaders$substituteGlobalSetUniforms(Shader shader) {
		return ShadowRenderer.substituteShader(shader);
	}
}
