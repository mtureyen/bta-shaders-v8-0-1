package io.github.mtureyen.btashaders.render;

import net.minecraft.client.render.block.model.BlockModel;
import net.minecraft.client.render.block.model.BlockModelCrossedSquares;
import net.minecraft.core.block.Block;
import net.minecraft.core.block.material.Material;
import net.minecraft.core.block.material.Materials;
import net.minecraft.core.block.material.MaterialLiquid;
import net.minecraft.core.world.pos.TilePos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
	public static final byte TRANSLUCENT_SOLID = 6; // glass, ice, other liquids
	public static final byte METAL = 7;             // iron / gold / steel blocks
	public static final byte POLISHED = 8;          // polished stone, gems, quartz, marble
	public static final byte SNOW = 9;              // never gets wet

	/** Per-block classification (block identity only; crossed plants are decided per model). */
	private static final Map<Block<?>, Byte> CLASSES = new ConcurrentHashMap<>();

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
		crossed = model instanceof BlockModelCrossedSquares;
		material = crossed ? PLANT : CLASSES.computeIfAbsent(block, MaterialContext::classify);
		emission = (byte) Math.max(0, Math.min(15, block.emission));
		tileY = pos.y;
		active = true;
	}

	private static byte classify(Block<?> block) {
		Material mat = block.getMaterial();
		if (mat == Materials.WATER) {
			return WATER;
		} else if (mat == Materials.LAVA) {
			return LAVA;
		} else if (mat == Materials.LEAVES) {
			return LEAVES;
		} else if (mat == Materials.PLANT || mat == Materials.VEGETABLE) {
			return PLANT;
		} else if (block.emission > 0) {
			return EMISSIVE;
		} else if (mat == Materials.GLASS || mat == Materials.ICE || mat instanceof MaterialLiquid) {
			return TRANSLUCENT_SOLID;
		} else if (mat == Materials.METAL || mat == Materials.IRON || mat == Materials.GOLD || mat == Materials.STEEL) {
			return METAL;
		} else if (mat == Materials.DIAMOND || mat == Materials.LAPIS || mat == Materials.OLIVINE
			|| mat == Materials.QUARTZ || isPolished(block)) {
			return POLISHED;
		} else if (mat == Materials.SNOW || mat == Materials.TOP_SNOW) {
			return SNOW;
		}
		return DEFAULT;
	}

	private static boolean isPolished(Block<?> block) {
		String key = block.getKey();
		return key != null && key.contains("polished");
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
