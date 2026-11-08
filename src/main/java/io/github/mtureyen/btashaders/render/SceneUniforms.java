package io.github.mtureyen.btashaders.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.option.GameSettings;
import net.minecraft.client.render.shader.Shader;
import net.minecraft.client.render.worldtype.WorldTypeFXDispatcher;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Sun/sky/cloud uniforms shared by the world shaders and the post passes. */
public final class SceneUniforms {
	private SceneUniforms() {
	}

	/** Cloud layer + camera position (clouds, cloud shadows, waving phase, puddles). */
	public static void clouds(Shader shader, Minecraft mc, ShadowRenderer shadows, boolean cloudsOn) {
		Vector3d cam = shadows.getCameraPos(); // this frame (updated by the shadow pass)
		shader.uniformVec3f("btsCameraMod",
			(float) (cam.x - Math.floor(cam.x / 1024.0) * 1024.0),
			(float) (cam.y - Math.floor(cam.y / 1024.0) * 1024.0),
			(float) (cam.z - Math.floor(cam.z / 1024.0) * 1024.0));
		shader.uniformFloat("btsCameraY", (float) cam.y);
		float cloudHeight = 128.0F;
		if (mc.currentWorld != null) {
			cloudHeight = WorldTypeFXDispatcher.getInstance().getDispatch(mc.currentWorld.getWorldType())
				.getCloudHeight(mc.currentWorld) + 0.33F;
		}
		shader.uniformFloat("btsCloudHeight", cloudHeight);
		shader.uniformInt("btsCloudsOn", cloudsOn ? 1 : 0);
	}

	/** Sun direction/state, view inverse and the light's screen position (post passes). */
	public static void atmosphere(Shader shader, FrameState frame, ShadowRenderer shadows) {
		boolean worldValid = shadows.isLightValid() && frame.matricesCaptured;
		float strength = 0.0F;
		float sunU = 0.5F;
		float sunV = 0.5F;
		if (worldValid) {
			Vector3f dir = shadows.getLightDirection();
			Vector4f clip = new Vector4f(dir.x, dir.y, dir.z, 0.0F);
			frame.view.transform(clip);
			frame.projection.transform(clip);
			if (clip.w > 0.0001F) {
				sunU = clip.x / clip.w * 0.5F + 0.5F;
				sunV = clip.y / clip.w * 0.5F + 0.5F;
				float elevation = dir.y;
				// Strongest around sunrise/sunset, subtle at noon, faint under the moon.
				float lowSun = 1.0F - smoothstep(0.15F, 0.7F, elevation);
				float horizonFade = smoothstep(0.0F, 0.08F, elevation);
				strength = (shadows.isLightSun() ? 0.35F + 0.65F * lowSun : 0.25F) * horizonFade;
				strength *= 1.0F - shadows.getRain();
				float off = Math.max(Math.abs(sunU - 0.5F), Math.abs(sunV - 0.5F));
				strength *= 1.0F - smoothstep(0.6F, 1.1F, off);
			}
			// Post passes run on the TAA-stable (un-jittered) image grid.
			shader.uniformMat4f("btsProjInv", frame.projectionInverse());
			shader.uniformMat4f("btsViewInv", frame.viewInverse());
			shader.uniformVec3f("btsLightDir", shadows.getLightDirection());
			shader.uniformInt("btsLightIsSun", shadows.isLightSun() ? 1 : 0);
			shader.uniformFloat("btsSunHeight", shadows.getSunHeight());
			shader.uniformFloat("btsRain", shadows.getRain());
		}
		// Loaded terrain ends in a square this far around the camera (border fog hides the edge).
		shader.uniformFloat("btsFarDistance", Math.max(GameSettings.RENDER_DISTANCE.value, 2) * 16.0F);
		shader.uniformInt("btsWorldValid", worldValid ? 1 : 0);
		shader.uniformVec2f("btsSunScreen", sunU, sunV);
		shader.uniformFloat("btsGodRayStrength", strength);
	}

	static float smoothstep(float edge0, float edge1, float x) {
		float t = Math.max(0.0F, Math.min(1.0F, (x - edge0) / (edge1 - edge0)));
		return t * t * (3.0F - 2.0F * t);
	}
}
