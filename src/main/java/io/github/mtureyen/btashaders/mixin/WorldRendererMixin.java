package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TAA: offsets the world projection by a sub-pixel amount each frame (only called once per frame,
 * from renderWorld). The renderer records the un-jittered matrix for reprojection.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererMixin {
	@Inject(method = "getProjectionMatrix", at = @At("RETURN"))
	private void btashaders$jitterProjection(Matrix4f matrix, float partialTick, CallbackInfoReturnable<Matrix4f> cir) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null) {
			renderer.onWorldProjection(matrix);
		}
	}
}
