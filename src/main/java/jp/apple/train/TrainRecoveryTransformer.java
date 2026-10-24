/*
 * AppleExtended-Modified
 *
 * Modifications (2026): HabQi.
 * See LICENSE.md in the project root for license details.
 */

package jp.apple.train;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Injects train recovery and visual passenger-pose hooks into vanilla code. */
public final class TrainRecoveryTransformer implements IClassTransformer {
    private static final String PLAYER_CLASS = "net.minecraft.entity.player.EntityPlayerMP";
    private static final String PLAYER_CLASS_OBF = "oq";
    private static final String PLAYER_LIST_CLASS = "net.minecraft.server.management.PlayerList";
    private static final String PLAYER_LIST_CLASS_OBF = "pl";
    private static final String RENDER_PLAYER_CLASS = "net.minecraft.client.renderer.entity.RenderPlayer";
    private static final String RENDER_PLAYER_CLASS_OBF = "cct";
    private static final String HOOK_OWNER = "jp/apple/train/TrainRecoveryManager";
    private static final String POSE_HOOK_OWNER = "jp/apple/train/PassengerPoseRenderHooks";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        if (matchesClass(name, transformedName, PLAYER_CLASS, PLAYER_CLASS_OBF)) {
            return transformTarget(basicClass, Target.PLAYER_SAVE);
        }
        if (matchesClass(name, transformedName, PLAYER_LIST_CLASS, PLAYER_LIST_CLASS_OBF)) {
            return transformTarget(basicClass, Target.PLAYER_READ);
        }
        if (matchesClass(name, transformedName, RENDER_PLAYER_CLASS, RENDER_PLAYER_CLASS_OBF)) {
            return transformTarget(basicClass, Target.PLAYER_RENDER);
        }
        return basicClass;
    }

    private static boolean matchesClass(String name, String transformedName, String deobfuscated, String obfuscated) {
        return deobfuscated.equals(name) || deobfuscated.equals(transformedName) || obfuscated.equals(name);
    }

    private static byte[] transformTarget(byte[] basicClass, Target target) {
        ClassReader reader = new ClassReader(basicClass);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        HookClassVisitor visitor = new HookClassVisitor(writer, target);
        reader.accept(visitor, 0);
        if (visitor.injectedMethods != 1) {
            throw new IllegalStateException("AppleExtended could not find required method "
                    + target.methodDescription + "; refusing to start with an incomplete train hook");
        }
        return writer.toByteArray();
    }

    private enum Target {
        PLAYER_SAVE("EntityPlayerMP.writeEntityToNBT", Opcodes.RETURN, "beforePlayerSave"),
        PLAYER_READ("PlayerList.readPlayerDataFromFile", Opcodes.ARETURN, "afterPlayerDataRead"),
        PLAYER_RENDER("RenderPlayer.applyRotations", -1, "applyLyingRotations");

        final String methodDescription;
        final int returnOpcode;
        final String hookName;

        Target(String methodDescription, int returnOpcode, String hookName) {
            this.methodDescription = methodDescription;
            this.returnOpcode = returnOpcode;
            this.hookName = hookName;
        }
    }

    private static final class HookClassVisitor extends ClassVisitor {
        private final Target target;
        private int injectedMethods;

        HookClassVisitor(ClassVisitor visitor, Target target) {
            super(Opcodes.ASM5, visitor);
            this.target = target;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!matchesMethod(this.target, name, descriptor)) {
                return visitor;
            }
            ++this.injectedMethods;
            if (this.target == Target.PLAYER_RENDER) {
                return createPlayerRenderVisitor(visitor);
            }
            return new MethodVisitor(Opcodes.ASM5, visitor) {
                @Override
                public void visitInsn(int opcode) {
                    if (opcode == target.returnOpcode) {
                        if (target == Target.PLAYER_READ) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitInsn(Opcodes.SWAP);
                        } else {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                        }
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, target.hookName,
                                "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                    }
                    super.visitInsn(opcode);
                }
            };
        }

        private static MethodVisitor createPlayerRenderVisitor(MethodVisitor visitor) {
            return new MethodVisitor(Opcodes.ASM5, visitor) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    Label continueVanilla = new Label();
                    super.visitVarInsn(Opcodes.ALOAD, 1);
                    super.visitVarInsn(Opcodes.FLOAD, 4);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, POSE_HOOK_OWNER,
                            "applyLyingRotations", "(Ljava/lang/Object;F)Z", false);
                    super.visitJumpInsn(Opcodes.IFEQ, continueVanilla);
                    super.visitInsn(Opcodes.RETURN);
                    super.visitLabel(continueVanilla);
                    super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                }
            };
        }
    }

    private static boolean matchesMethod(Target target, String name, String descriptor) {
        if (target == Target.PLAYER_RENDER) {
            boolean nameMatches = "applyRotations".equals(name)
                    || "func_77043_a".equals(name) || "a".equals(name);
            return nameMatches
                    && ("(Lnet/minecraft/client/entity/AbstractClientPlayer;FFF)V".equals(descriptor)
                    || "(Lbua;FFF)V".equals(descriptor));
        }
        if (target == Target.PLAYER_SAVE) {
            boolean nameMatches = "writeEntityToNBT".equals(name) || "func_70014_b".equals(name) || "b".equals(name);
            return nameMatches && ("(Lnet/minecraft/nbt/NBTTagCompound;)V".equals(descriptor)
                    || "(Lfy;)V".equals(descriptor));
        }
        boolean nameMatches = "readPlayerDataFromFile".equals(name) || "func_72380_a".equals(name) || "a".equals(name);
        return nameMatches && ("(Lnet/minecraft/entity/player/EntityPlayerMP;)Lnet/minecraft/nbt/NBTTagCompound;".equals(descriptor)
                || "(Loq;)Lfy;".equals(descriptor));
    }
}
