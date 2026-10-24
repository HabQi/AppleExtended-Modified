/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

/*
 * AppleExtended
 *
 * Marks RTM's dedicated light-texture render pass for shader packs.
 */
package jp.ngt.rtm.render;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.Map;

/** Bridges RTM's fixed-pipeline LIGHT pass to the shader emissive channel. */
@SideOnly(Side.CLIENT)
public final class RtmEmissivePass {
    private static final String UNIFORM_NAME = "rtmEmissivePass";
    private static final Map<Integer, Integer> LOCATION_CACHE = new HashMap<>();

    private RtmEmissivePass() {
    }

    /** Enables the marker on the currently active shader program for an RTM light pass. */
    public static int begin(RenderPass pass) {
        if (!isLightPass(pass)) {
            return 0;
        }

        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (program <= 0) {
            return 0;
        }

        int location = getLocation(program);
        if (location >= 0) {
            ARBShaderObjects.glUniform1iARB(location, 1);
            return program;
        }
        return 0;
    }

    /** Clears the marker and returns the program to its normal per-draw state. */
    public static void end(int program) {
        if (program <= 0) {
            return;
        }

        int currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            if (currentProgram != program) {
                ARBShaderObjects.glUseProgramObjectARB(program);
            }
            int location = getLocation(program);
            if (location >= 0) {
                ARBShaderObjects.glUniform1iARB(location, 0);
            }
        } finally {
            if (currentProgram != program) {
                ARBShaderObjects.glUseProgramObjectARB(currentProgram);
            }
        }
    }

    private static boolean isLightPass(RenderPass pass) {
        return pass == RenderPass.LIGHT
                || pass == RenderPass.LIGHT_FRONT
                || pass == RenderPass.LIGHT_BACK;
    }

    private static int getLocation(int program) {
        Integer cached = LOCATION_CACHE.get(program);
        if (cached != null) {
            return cached;
        }

        int location = ARBShaderObjects.glGetUniformLocationARB(program, UNIFORM_NAME);
        LOCATION_CACHE.put(program, location);
        return location;
    }

}
