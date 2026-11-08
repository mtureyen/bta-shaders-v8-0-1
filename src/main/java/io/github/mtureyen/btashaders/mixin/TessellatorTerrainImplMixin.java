package io.github.mtureyen.btashaders.mixin;

import io.github.mtureyen.btashaders.render.MaterialContext;
import net.minecraft.client.render.tessellator.TessellatorTerrainImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a chunk block is being meshed, writes the material ID / top-vertex flag / emission into the
 * vertex normal bytes (see {@link MaterialContext} for the layout).
 */
@Mixin(value = TessellatorTerrainImpl.class, remap = false)
public abstract class TessellatorTerrainImplMixin {
	@Shadow
	private byte normalX;
	@Shadow
	private byte normalY;
	@Shadow
	private byte normalZ;

	@Inject(method = "addVertex", at = @At("HEAD"))
	private void btashaders$writeMaterial(double x, double y, double z, CallbackInfo ci) {
		if (!MaterialContext.isActive()) {
			return;
		}
		this.normalX = MaterialContext.material();
		this.normalY = MaterialContext.isTopVertex(y) ? (byte) 127 : (byte) 0;
		this.normalZ = MaterialContext.emission();
	}
}
