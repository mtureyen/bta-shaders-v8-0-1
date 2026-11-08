package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import io.github.mtureyen.btashaders.render.LightingState;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The first-person hand is drawn in its own view space, so world-space shadow lookups don't apply.
 * Switch the lit shaders to "hand mode" (lighting without shadows) around it.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererHandMixin {
	@Inject(method = "renderHand", at = @At("HEAD"))
	private void btashaders$beginHand(float partialTick, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null && renderer.getLighting().getMode() == LightingState.WORLD) {
			renderer.getLighting().setMode(LightingState.HAND);
		}
	}

	@Inject(method = "renderHand", at = @At("RETURN"))
	private void btashaders$endHand(float partialTick, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null && renderer.getLighting().getMode() == LightingState.HAND) {
			renderer.getLighting().setMode(LightingState.WORLD);
		}
	}
}
