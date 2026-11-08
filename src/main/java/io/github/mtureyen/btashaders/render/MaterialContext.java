package io.github.mtureyen.btashaders.render;

import net.minecraft.client.render.block.model.BlockModel;
import net.minecraft.client.render.block.model.BlockModelCrossedSquares;
import net.minecraft.core.block.Block;
import net.minecraft.core.block.material.Material;
import net.minecraft.core.block.material.Materials;
import net.minecraft.core.world.pos.TilePos;

/**
 * Per-vertex material IDs for terrain (what Iris gets from block.properties / mc_Entity).
 *
 * <p>While a chunk mesh is built, {@code ChunkRendererMultiDraw.renderBlock} tells us which block is
 * being drawn; the terrain tessellator then writes the material ID into the vertex's normal bytes
 * instead of the normal (our shaders compute normals from the geometry, and plants had garbage
 * normals anyway). Layout of the 3 signed normal bytes:
 * <pre>
 *   x: material ID (see constants, must match lib/materials.glsl)
 *   y: 127 if this is a "top" vertex of a crossed plant (waves), else 0
 *   z: block light emission 0..15 (for glowing blocks)
 * </pre>
 * Chunk building runs on a single thread with one static tessellator, so static state is enough.
 */
public final class MaterialContext {
	public static final byte DEFAULT = 0;
	public static final byte PLANT = 1;
	public static final byte LEAVES = 2;
	public static final byte WATER = 3;
	public static final byte LAVA = 4;
	public static final byte EMISSIVE = 5;
	public static final byte TRANSLUCENT_SOLID = 6; // glass, ice

	/** Off if our terrain shader isn't in use (stock BTA shaders need real normals). */
	public static volatile boolean enabled = true;

	private static boolean active = false;
	private static byte material = DEFAULT;
	private static byte emission = 0;
	private static int tileY = 0;
	private static boolean crossed = false;

	private MaterialContext() {
	}

	public static void begin(BlockModel<?> model, TilePos pos) {
		if (!enabled) {
			active = false;
			return;
		}
		Block<?> block = model.block;
		Material mat = block.getMaterial();
		crossed = model instanceof BlockModelCrossedSquares;
		if (mat == Materials.WATER) {
			material = WATER;
		} else if (mat == Materials.LAVA) {
			material = LAVA;
		} else if (mat == Materials.LEAVES) {
			material = LEAVES;
		} else if (crossed || mat == Materials.PLANT || mat == Materials.VEGETABLE) {
			material = PLANT;
		} else if (block.emission > 0) {
			material = EMISSIVE;
		} else if (mat == Materials.GLASS || mat == Materials.ICE) {
			material = TRANSLUCENT_SOLID;
		} else {
			material = DEFAULT;
		}
		emission = (byte) Math.max(0, Math.min(15, block.emission));
		tileY = pos.y;
		active = true;
	}

	public static void end() {
		active = false;
	}

	public static boolean isActive() {
		return active;
	}

	public static byte material() {
		return material;
	}

	public static byte emission() {
		return emission;
	}

	/** Crossed plants: vertices at the top of the block sway, the bottom ones stay planted. */
	public static boolean isTopVertex(double y) {
		return material == PLANT && crossed && (y - tileY) > 0.5;
	}
}
