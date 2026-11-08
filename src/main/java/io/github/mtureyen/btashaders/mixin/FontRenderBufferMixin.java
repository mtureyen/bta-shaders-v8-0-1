package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.ShadowRenderer;
import net.minecraft.client.render.tessellator.TessellatorFont;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cached text (sign text) binds the font shader directly, bypassing the shadow pass's shader swap, and
 * would draw into the shadow map without the shadow projection's distortion (stray shadow specks).
 * Text casts no shadow: skip it during the shadow pass.
 */
@Mixin(value = TessellatorFont.FontRenderBuffer.class, remap = false)
public abstract class FontRenderBufferMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void btashaders$noTextInShadowPass(CallbackInfo ci) {
		if (ShadowRenderer.isEntityPass()) {
			ci.cancel();
		}
	}
}
