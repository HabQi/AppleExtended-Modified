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

package jp.ngt.rtm.render;

import jp.ngt.rtm.entity.train.EntityTrainBase;
import jp.ngt.rtm.entity.vehicle.EntityVehicleBase;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Matrix4f;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;

/** Supplies exact previous-frame reprojection for the train occupied by the local player. */
@SideOnly(Side.CLIENT)
public final class VehicleTemporalReprojection {
    private static final String ENTITY_MARKER_UNIFORM = "rtmTemporalEntity";
    private static final String REPROJECTION_UNIFORM = "rtmViewToPreviousView";
    private static final FloatBuffer MATRIX_BUFFER = BufferUtils.createFloatBuffer(16);

    private static EntityVehicleBase<?> previousVehicle;
    private static Matrix4f previousModelView;
    private static EntityVehicleBase<?> frameVehicle;
    private static Matrix4f frameModelView;
    private static Matrix4f frameReprojection;

    private static int markerProgram;
    private static int markerLocation = -1;

    private static final OptiFinePrograms OPTIFINE_PROGRAMS = new OptiFinePrograms();

    private VehicleTemporalReprojection() {
    }

    static void beginFrame() {
        previousVehicle = frameVehicle;
        previousModelView = frameModelView;
        frameVehicle = null;
        frameModelView = null;
        frameReprojection = null;
    }

    /**
     * Marks an occupied train for the current draw. The returned program must be passed to
     * {@link #endVehicle(int)} so the marker cannot leak into later entity draws.
     */
    public static int beginVehicle(EntityVehicleBase<?> vehicle) {
        if (!VehicleFrustumCulling.areShadersEnabled()
                || !(vehicle instanceof EntityTrainBase)
                || VehicleFrustumCulling.isShadowPassActive()
                || !VehicleFrustumCulling.isPlayerRidingVehicle(vehicle)) {
            return 0;
        }

        if (frameVehicle == null) {
            Matrix4f currentModelView = captureModelView();
            frameVehicle = vehicle;
            frameModelView = currentModelView;
            if (vehicle == previousVehicle && previousModelView != null) {
                Matrix4f currentInverse = Matrix4f.invert(currentModelView, null);
                if (currentInverse != null) {
                    frameReprojection = Matrix4f.mul(previousModelView, currentInverse, null);
                    if (!isFinite(frameReprojection)) {
                        frameReprojection = null;
                    }
                }
            }
        }

        if (frameVehicle != vehicle || frameReprojection == null) {
            return 0;
        }

        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int location = getMarkerLocation(program);
        if (program > 0 && location >= 0) {
            ARBShaderObjects.glUniform1iARB(location, 1);
            return program;
        }
        return 0;
    }

    public static void endVehicle(int program) {
        if (program <= 0) {
            return;
        }
        int currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            if (currentProgram != program) {
                ARBShaderObjects.glUseProgramObjectARB(program);
            }
            int location = getMarkerLocation(program);
            if (location >= 0) {
                ARBShaderObjects.glUniform1iARB(location, 0);
            }
        } finally {
            if (currentProgram != program) {
                ARBShaderObjects.glUseProgramObjectARB(currentProgram);
            }
        }
    }

    /** Uploads data before OptiFine starts the deferred passes. Uniform values persist per program. */
    static void uploadFrameData() {
        if (frameReprojection == null) {
            return;
        }

        int activeProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            uploadToProgram(OPTIFINE_PROGRAMS.getProgramId("deferred"), frameReprojection);
            uploadToProgram(OPTIFINE_PROGRAMS.getProgramId("composite7"), frameReprojection);
        } finally {
            ARBShaderObjects.glUseProgramObjectARB(activeProgram);
        }
    }

    private static Matrix4f captureModelView() {
        MATRIX_BUFFER.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX_BUFFER);
        MATRIX_BUFFER.rewind();
        Matrix4f matrix = new Matrix4f();
        matrix.load(MATRIX_BUFFER);
        return matrix;
    }

    private static void uploadToProgram(int program, Matrix4f matrix) {
        if (program <= 0) {
            return;
        }
        ARBShaderObjects.glUseProgramObjectARB(program);
        int location = ARBShaderObjects.glGetUniformLocationARB(program, REPROJECTION_UNIFORM);
        if (location < 0) {
            return;
        }
        MATRIX_BUFFER.clear();
        matrix.store(MATRIX_BUFFER);
        MATRIX_BUFFER.flip();
        ARBShaderObjects.glUniformMatrix4ARB(location, false, MATRIX_BUFFER);
    }

    private static int getMarkerLocation(int program) {
        if (program <= 0) {
            return -1;
        }
        if (markerProgram != program) {
            markerProgram = program;
            markerLocation = ARBShaderObjects.glGetUniformLocationARB(program, ENTITY_MARKER_UNIFORM);
        }
        return markerLocation;
    }

    private static boolean isFinite(Matrix4f matrix) {
        MATRIX_BUFFER.clear();
        matrix.store(MATRIX_BUFFER);
        MATRIX_BUFFER.flip();
        while (MATRIX_BUFFER.hasRemaining()) {
            if (!Float.isFinite(MATRIX_BUFFER.get())) {
                return false;
            }
        }
        return true;
    }

    private static final class OptiFinePrograms {
        private final Method getProgram;
        private final Method getId;

        private OptiFinePrograms() {
            Method programGetter = null;
            Method idGetter = null;
            try {
                Class<?> shadersClass = Class.forName("net.optifine.shaders.Shaders");
                Class<?> programClass = Class.forName("net.optifine.shaders.Program");
                programGetter = shadersClass.getMethod("getProgram", String.class);
                idGetter = programClass.getMethod("getId");
            } catch (Throwable ignored) {
                // OptiFine is optional.
            }
            this.getProgram = programGetter;
            this.getId = idGetter;
        }

        private int getProgramId(String name) {
            if (this.getProgram == null || this.getId == null) {
                return 0;
            }
            try {
                Object program = this.getProgram.invoke(null, name);
                return program == null ? 0 : ((Number) this.getId.invoke(program)).intValue();
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }
}
