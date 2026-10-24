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

import org.lwjgl.opengl.GL11;

public class DisplayList extends GLObject {
    public DisplayList(int par1) {
        super(par1);
    }

    @Override
    protected void deleteResource() {
        if (this.value > 0) {
            GL11.glDeleteLists(this.value, 1);
        }
    }
}
