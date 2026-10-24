/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

/*
 * AppleExtended
 *
 * Fixed-function VBO used by asynchronously prepared static rail meshes.
 */
package jp.ngt.rtm.render;

import jp.ngt.ngtlib.renderer.GLHelper;
import jp.ngt.ngtlib.renderer.GLObject;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;

final class RailVertexBuffer extends GLObject {
    private final int vertexCount;
    private final long generation;
    private final Object modelIdentity;
    private final int slot;

    private RailVertexBuffer(int bufferId, int vertexCount, long generation, Object modelIdentity, int slot) {
        super(bufferId);
        this.vertexCount = vertexCount;
        this.generation = generation;
        this.modelIdentity = modelIdentity;
        this.slot = slot;
    }

    static RailVertexBuffer upload(RailMeshData mesh, long generation, Object modelIdentity, int slot) {
        int bufferId = GL15.glGenBuffers();
        if (bufferId <= 0) {
            throw new IllegalStateException("Unable to allocate rail VBO");
        }
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, bufferId);
            mesh.vertices.position(0);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, mesh.vertices, GL15.GL_STATIC_DRAW);
            RailVertexBuffer result = new RailVertexBuffer(bufferId, mesh.vertexCount,
                    generation, modelIdentity, slot);
            return GLHelper.registerGLObject(result);
        } catch (RuntimeException | Error throwable) {
            GL15.glDeleteBuffers(bufferId);
            throw throwable;
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    }

    boolean matches(long generation, Object modelIdentity, int slot) {
        return !this.delFlag && this.generation == generation
                && this.modelIdentity == modelIdentity && this.slot == slot;
    }

    void render() {
        if (this.vertexCount <= 0 || this.value <= 0 || this.delFlag) {
            return;
        }
        int stride = RailMeshSnapshot.VERTEX_STRIDE_BYTES;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.value);
        try {
            GL11.glVertexPointer(3, GL11.GL_FLOAT, stride, 0L);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);

            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, stride, 12L);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, stride, 20L);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);

            GL11.glNormalPointer(GL11.GL_BYTE, stride, 24L);
            GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);

            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, stride, 28L);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);

            GL20.glVertexAttribPointer(11, 2, GL11.GL_FLOAT, false, stride, 32L);
            GL20.glEnableVertexAttribArray(11);
            GL20.glVertexAttribPointer(12, 4, GL11.GL_FLOAT, false, stride, 40L);
            GL20.glEnableVertexAttribArray(12);

            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, this.vertexCount);
        } finally {
            GL20.glDisableVertexAttribArray(12);
            GL20.glDisableVertexAttribArray(11);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        }
    }

    @Override
    protected void deleteResource() {
        if (this.value > 0) {
            GL15.glDeleteBuffers(this.value);
        }
    }

    @Override
    protected boolean isVertexBufferObject() {
        return true;
    }
}
