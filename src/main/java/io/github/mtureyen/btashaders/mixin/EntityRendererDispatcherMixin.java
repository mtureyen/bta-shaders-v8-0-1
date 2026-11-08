package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.EntityMotion;
import net.minecraft.client.render.EntityRendererDispatcher;
import net.minecraft.client.render.tessellator.TessellatorGeneral;
import net.minecraft.core.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands each entity's per-frame movement to our shaders (motion vectors for TAA, see EntityMotion). */
@Mixin(value = EntityRendererDispatcher.class, remap = false)
public abstract class EntityRendererDispatcherMixin {
	@Inject(method = "renderEntity", at = @At("HEAD"))
	private void btashaders$beforeEntity(TessellatorGeneral tessellator, Entity entity, float partialTick, CallbackInfo ci) {
		EntityMotion.before(entity, partialTick);
	}

	@Inject(method = "renderEntity", at = @At("RETURN"))
	private void btashaders$afterEntity(TessellatorGeneral tessellator, Entity entity, float partialTick, CallbackInfo ci) {
		EntityMotion.after();
	}
}
