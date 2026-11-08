package io.github.mtureyen.btashaders.render;

import org.lwjgl.opengl.GL41;

/**
 * Our own fullscreen quad, for passes that run in the middle of BTA's world rendering (the opaque
 * copy). Unlike ShaderHelper.drawFullscreenRect it doesn't touch GLRenderer's tracked state; the
 * caller saves/restores raw GL state itself.
 */
public final class FullscreenQuad {
	private int vao = 0;
	private int vbo = 0;

	public void draw() {
		if (this.vao == 0) {
			this.vao = GL41.glGenVertexArrays();
			this.vbo = GL41.glGenBuffers();
			// x, y, u, v as a triangle strip (same layout as BTA's fullscreen rect: loc 0 = pos, 1 = uv)
			float[] vertices = {-1, 1, 0, 1, -1, -1, 0, 0, 1, 1, 1, 1, 1, -1, 1, 0};
			GL41.glBindVertexArray(this.vao);
			GL41.glBindBuffer(GL41.GL_ARRAY_BUFFER, this.vbo);
			GL41.glBufferData(GL41.GL_ARRAY_BUFFER, vertices, GL41.GL_STATIC_DRAW);
			GL41.glVertexAttribPointer(0, 2, GL41.GL_FLOAT, false, 16, 0L);
			GL41.glVertexAttribPointer(1, 2, GL41.GL_FLOAT, false, 16, 8L);
			GL41.glEnableVertexAttribArray(0);
			GL41.glEnableVertexAttribArray(1);
		}
		GL41.glBindVertexArray(this.vao);
		GL41.glDrawArrays(GL41.GL_TRIANGLE_STRIP, 0, 4);
	}

	public void delete() {
		if (this.vbo != 0) {
			GL41.glDeleteBuffers(this.vbo);
			this.vbo = 0;
		}
		if (this.vao != 0) {
			GL41.glDeleteVertexArrays(this.vao);
			this.vao = 0;
		}
	}
}
