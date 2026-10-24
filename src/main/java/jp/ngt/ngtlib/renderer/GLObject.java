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

public class GLObject {
    public int value;
    public boolean delFlag;

    private boolean released;

    protected GLObject(int par1) {
        this.value = par1;
    }

    public void setDelFlag(boolean par1) {
        this.delFlag = par1;
    }

    final void releaseResource() {
        if (this.released) {
            return;
        }

        this.released = true;
        try {
            this.deleteResource();
        } finally {
            this.value = 0;
        }
    }

    protected void deleteResource() {
    }

    /** Allows specialized GL resources to participate in the common resource statistics. */
    protected boolean isVertexBufferObject() {
        return false;
    }
}
