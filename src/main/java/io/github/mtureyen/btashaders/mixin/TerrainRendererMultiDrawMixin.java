package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import net.minecraft.client.render.terrain.TerrainRendererMultiDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Around the main solid terrain draw:
 * - records this frame's world view/projection + camera position (TAA reprojection, god rays),
 * - switches our lighting/shadow lookups on, and back off afterwards so other users of the terrain
 *   shader (GUI block icons, held blocks) are unaffected.
 */
@Mixin(value = TerrainRendererMultiDraw.class, remap = false)
public abstract class TerrainRendererMultiDrawMixin {
	@Inject(method = "renderSolidTerrain", at = @At("HEAD"))
	private void btashaders$beginShadowReceive(float partialTicks, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null) {
			renderer.captureWorldMatrices(partialTicks);
			renderer.getShadows().beginReceive(renderer.isEnabled());
		}
	}

	@Inject(method = "renderSolidTerrain", at = @At("RETURN"))
	private void btashaders$endShadowReceive(float partialTicks, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null) {
			renderer.getShadows().endReceive();
		}
	}
}
