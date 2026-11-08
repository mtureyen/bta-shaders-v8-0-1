package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.BtaShadersRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.tessellator.TessellatorGeneral;
import net.minecraft.core.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BTA draws a round "blob" shadow texture under every mob. With our real entity shadows active it's
 * redundant and makes the shadow look round and smudgy, so skip it (it returns with F8 off or
 * ENTITY_SHADOWS 0).
 */
@Mixin(value = EntityRenderer.class, remap = false)
public abstract class EntityRendererMixin {
	@Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
	private void btashaders$skipBlobShadow(TessellatorGeneral tessellator, Entity entity, double posX, double posY,
										   double posZ, float opacity, float partialTick, CallbackInfo ci) {
		BtaShadersRenderer renderer = BtaShadersRenderer.active();
		if (renderer != null && renderer.isEnabled() && renderer.getShadows().entityShadowsActive()) {
			ci.cancel();
		}
	}
}
