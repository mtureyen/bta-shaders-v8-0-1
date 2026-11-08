package io.github.mtureyen.btashaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.mtureyen.btashaders.render.ShadowRenderer;
import net.minecraft.client.render.font.FontRendererDefault;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Name tags (other players on servers) and sign text get drawn while entities render into the shadow
 * map. There every non-shadow shader is swapped for the empty one (ShadowRenderer.substituteShader),
 * so the font's own uniform location is invalid for the bound program: with GL debug logging on that
 * was an error + stack trace per name tag per frame. Skip the uniform during the shadow pass (the
 * text itself draws nothing there anyway).
 */
@Mixin(value = FontRendererDefault.class, remap = false)
public abstract class FontRendererDefaultMixin {
	@WrapOperation(method = "draw", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL41;glUniform1f(IF)V"))
	private void btashaders$skipUniformInShadowPass(int location, float value, Operation<Void> original) {
		if (!ShadowRenderer.isEntityPass()) {
			original.call(location, value);
		}
	}
}
