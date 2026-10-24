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

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Adds the missing-vehicle submission hook to RenderGlobal's entity stage. */
public final class VehicleRenderTransformer implements IClassTransformer {
    private static final String RENDER_GLOBAL = "net.minecraft.client.renderer.RenderGlobal";
    private static final String RENDER_GLOBAL_OBF = "buy";
    private static final String HOOK_OWNER = "jp/ngt/rtm/render/VehicleRenderHooks";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !matchesClass(name, transformedName)) {
            return basicClass;
        }

        ClassReader reader = new ClassReader(basicClass);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        HookClassVisitor visitor = new HookClassVisitor(writer);
        reader.accept(visitor, 0);
        if (visitor.matchedMethods != 1 || visitor.injectedAnchors != 1) {
            throw new IllegalStateException("AppleExtended could not install the vehicle render hook: methods="
                    + visitor.matchedMethods + ", anchors=" + visitor.injectedAnchors);
        }
        return writer.toByteArray();
    }

    private static boolean matchesClass(String name, String transformedName) {
        return RENDER_GLOBAL.equals(name) || RENDER_GLOBAL.equals(transformedName)
                || RENDER_GLOBAL_OBF.equals(name);
    }

    private static boolean matchesMethod(String name, String descriptor) {
        boolean nameMatches = "renderEntities".equals(name)
                || "func_180446_a".equals(name) || "a".equals(name);
        return nameMatches && ("(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V"
                .equals(descriptor) || "(Lvg;Lbxy;F)V".equals(descriptor));
    }

    private static final class HookClassVisitor extends ClassVisitor {
        private int matchedMethods;
        private int injectedAnchors;

        private HookClassVisitor(ClassVisitor visitor) {
            super(Opcodes.ASM5, visitor);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!matchesMethod(name, descriptor)) {
                return visitor;
            }
            ++this.matchedMethods;
            return new MethodVisitor(Opcodes.ASM5, visitor) {
                @Override
                public void visitLdcInsn(Object value) {
                    if ("blockentities".equals(value)) {
                        ++injectedAnchors;
                        super.visitVarInsn(Opcodes.FLOAD, 3);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER,
                                "renderMissingVehicles", "(F)V", false);
                    }
                    super.visitLdcInsn(value);
                }
            };
        }
    }
}
