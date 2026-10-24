/*
 * AppleExtended
 *
 * Original code (c) 2020 anatawa12 and other contributors.
 * Modifications (c) 2026 Applepie.
 * Further modifications (2026): HabQi.
 *
 * This file is part of AppleExtended, which is a derivative work of fixRTM.
 * Both are licensed under the GNU Lesser General Public License version 3.
 * See LICENSE.md in the project root for license details.
 */

package jp.ngt.ngtlib.renderer;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

final class VertexArrayObject extends GLObject {
    private final List<Integer> bufferIds = new ArrayList<>();

    VertexArrayObject(int id) {
        super(id);
    }

    void addBuffer(int bufferId) {
        if (bufferId > 0 && !this.bufferIds.contains(bufferId)) {
            this.bufferIds.add(bufferId);
        }
    }

    @Override
    protected void deleteResource() {
        for (int bufferId : this.bufferIds) {
            GL15.glDeleteBuffers(bufferId);
        }
        this.bufferIds.clear();

        if (this.value > 0) {
            GL30.glDeleteVertexArrays(this.value);
        }
    }
}
