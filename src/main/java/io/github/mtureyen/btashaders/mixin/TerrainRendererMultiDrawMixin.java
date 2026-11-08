package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import net.minecraft.client.render.terrain.TerrainRendererMultiDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * - Start of the main solid terrain draw: records this frame's world view/projection + camera position
 *   (TAA reprojection, god rays, SSAO, sky/fog).
 * - Start of the translucent terrain draw: copies the opaque scene (color + depth) so water can do
 *   refraction and screen-space reflections, and flags the water pass for the terrain shader.
 */
@Mixin(value = TerrainRendererMultiDraw.class, remap = false)
public abstract class TerrainRendererMultiDrawMixin {
	@Inject(method = "renderSolidTerrain", at = @At("HEAD"))
	private void btashaders$beforeSolid(float partialTicks, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null) {
			renderer.captureWorldMatrices(partialTicks);
		}
	}

	@Inject(method = "renderSolidTerrain", at = @At("RETURN"))
	private void btashaders$afterSolid(float partialTicks, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null) {
			renderer.endSolidTerrain();
		}
	}

	@Inject(method = "renderTranslucentTerrain", at = @At("HEAD"))
	private void btashaders$beforeTranslucent(float partialTicks, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null) {
			renderer.beforeTranslucentTerrain();
		}
	}

	@Inject(method = "renderTranslucentTerrain", at = @At("RETURN"))
	private void btashaders$afterTranslucent(float partialTicks, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null) {
			renderer.afterTranslucentTerrain();
		}
	}
}
