package io.github.mtureyen.btashaders.render;

import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * Per-frame camera state shared by all passes: this frame's world matrices (captured at the main
 * terrain draw), the previous frame's (TAA reprojection), render size and frame counter.
 */
public final class FrameState {
	public final Matrix4f projection = new Matrix4f();         // un-jittered
	public final Matrix4f projectionJittered = new Matrix4f(); // as used for drawing
	public final Matrix4f view = new Matrix4f();
	public final Vector3d cameraPos = new Vector3d();
	public final Matrix4f prevProjection = new Matrix4f();
	public final Matrix4f prevView = new Matrix4f();
	public final Vector3d prevCameraPos = new Vector3d();

	public int width;
	public int height;
	public int frameIndex = 0;
	/** The world matrices of this frame are known (main terrain draw happened). */
	public boolean matricesCaptured = false;
	/** BTA's sky dome is suppressed this frame and our sky + clouds are drawn instead. */
	public boolean ownSky = false;

	public void setSize(int width, int height) {
		this.width = width;
		this.height = height;
	}

	/** End of frame: this frame's matrices become the previous ones. */
	public void advance() {
		if (this.matricesCaptured) {
			this.prevProjection.set(this.projection);
			this.prevView.set(this.view);
			this.prevCameraPos.set(this.cameraPos);
		}
		this.frameIndex++;
	}

	/** Frame counter for shader noise (kept small for float precision). */
	public float noiseFrame() {
		return (float) (this.frameIndex % 1024);
	}

	public Matrix4f projectionInverse() {
		return new Matrix4f(this.projection).invert();
	}

	public Matrix4f jitteredProjectionInverse() {
		return new Matrix4f(this.projectionJittered).invert();
	}

	public Matrix4f viewInverse() {
		return new Matrix4f(this.view).invert();
	}
}
