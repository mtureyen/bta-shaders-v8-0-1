package io.github.mtureyen.btashaders.mixin;

import net.minecraft.client.render.terrain.ChunkRenderer;
import net.minecraft.core.world.pos.TilePos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ChunkRenderer.class, remap = false)
public interface ChunkRendererAccessor {
	@Accessor("pos")
	TilePos btashaders$getPos();
}
