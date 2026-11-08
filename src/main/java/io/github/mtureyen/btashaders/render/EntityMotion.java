package io.github.mtureyen.btashaders.render;

import net.minecraft.client.render.renderer.Shaders;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.core.entity.Entity;
import org.lwjgl.opengl.GL41;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Motion vectors for entities, so TAA can follow a walking mob instead of smearing it: before each
 * entity is drawn in the main pass, its movement since last frame (interpolated position now minus
 * the one drawn last frame) goes into the {@code btsObjectMotion} uniform, which our shaders write to
 * the G-buffer's motion target. Covers movement of the whole entity (walking, falling, minecarts);
 * limb animation and turning are left to TAA's neighbourhood clamp.
 *
 * <p>Uses glProgramUniform, so the program binding BTA tracks for its draws is never touched.
 */
public final class EntityMotion {
	/** Moves further than this between two frames are teleports, not motion. */
	private static final double MAX_MOTION = 4.0;

	/** x, y, z, frame of last update, motion x, y, z (kept for repeated draws in one frame). */
	private static final Map<Entity, double[]> LAST = new WeakHashMap<>();
	private static boolean active = false;
	private static int frame = 0;

	private EntityMotion() {
	}

	/** Start of a frame's world pass; {@code on} = motion is wanted (our renderer and TAA active). */
	public static void beginFrame(int frameIndex, boolean on) {
		frame = frameIndex;
		active = on;
		if (!on && !LAST.isEmpty()) {
			LAST.clear();
		}
	}

	public static void before(Entity entity, float partialTick) {
		if (!active || ShadowRenderer.isEntityPass()) {
			return;
		}
		double x = entity.xo + (entity.x - entity.xo) * partialTick;
		double y = entity.yo + (entity.y - entity.yo) * partialTick;
		double z = entity.zo + (entity.z - entity.zo) * partialTick;
		double[] last = LAST.get(entity);
		float mx = 0.0F;
		float my = 0.0F;
		float mz = 0.0F;
		if (last == null) {
			LAST.put(entity, new double[]{x, y, z, frame, 0, 0, 0});
		} else if ((int) last[3] == frame) {
			mx = (float) last[4];
			my = (float) last[5];
			mz = (float) last[6];
		} else {
			if ((int) last[3] == frame - 1) {
				double dx = x - last[0];
				double dy = y - last[1];
				double dz = z - last[2];
				if (dx * dx + dy * dy + dz * dz < MAX_MOTION * MAX_MOTION) {
					mx = (float) dx;
					my = (float) dy;
					mz = (float) dz;
				}
			}
			last[0] = x;
			last[1] = y;
			last[2] = z;
			last[3] = frame;
			last[4] = mx;
			last[5] = my;
			last[6] = mz;
		}
		set(mx, my, mz);
	}

	public static void after() {
		if (!active || ShadowRenderer.isEntityPass()) {
			return;
		}
		set(0.0F, 0.0F, 0.0F);
	}

	private static void set(float x, float y, float z) {
		for (Shader shader : new Shader[]{Shaders.ENTITY, Shaders.WORLD, Shaders.ITEM, Shaders.TERRAIN}) {
			if (shader.isEnabled()) {
				int location = shader.getUniform("btsObjectMotion");
				if (location >= 0) {
					GL41.glProgramUniform3f(shader.id(), location, x, y, z);
				}
			}
		}
	}
}
