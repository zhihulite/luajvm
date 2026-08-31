// core 字节码的 Android 兼容重写器（luajvm-core-android 的构建工具）：
//   超版本 API 调用改为 JavaApiCompat 静态调用 + Executable→Member 类型映射
package org.luajvm.rewriter;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.List;

/**
 * 重写规则：方法签名/字段/checkcast 经 ClassRemapper 全量映射；
 * 方法调用按 rewrites 表改写为 JavaApiCompat 静态调用。
 */
public final class CoreRewriter {

    private final List<Rewrite> rewrites;
    private final String compatOwner;

    private final Remapper typeRemapper = new Remapper() {
        @Override
        public String map(String internalName) {
            if (internalName.equals("java/lang/reflect/Executable")) return "java/lang/reflect/Member";
            // MatchException（API 35）→ IllegalStateException：构造器签名 (String,Throwable) API 1 即有
            if (internalName.equals("java/lang/MatchException")) return "java/lang/IllegalStateException";
            return internalName;
        }
    };

    public CoreRewriter(List<Rewrite> rewrites, String compatOwner) {
        this.rewrites = rewrites;
        this.compatOwner = compatOwner;
    }

    /** @return 重写后的字节码（恒为新数组：remap 可能生效于无表内调用的类） */
    public byte[] rewrite(byte[] bytes) {
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, new ClassRemapper(writer, typeRemapper)) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        // Redirect.INHERIT/PIPE 是 API 26 字段：改推 null（值由 noRedirect 丢弃）
                        if (opcode == Opcodes.GETSTATIC
                                && "java/lang/ProcessBuilder$Redirect".equals(owner)
                                && ("INHERIT".equals(name) || "PIPE".equals(name))) {
                            super.visitInsn(Opcodes.ACONST_NULL);
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, name, desc);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                        if ("java/lang/reflect/Executable".equals(owner)
                                && ("isAccessible".equals(mName) || "setAccessible".equals(mName))) {
                            // isAccessible/setAccessible 是 AccessibleObject 的类方法：remap 后 owner
                            // 变 Member（接口无此方法，IncompatibleClassChangeError）→ 改走
                            // trySetAccessible；isAccessible 恒返 0 保证 setAccessible 分支必达
                            if (mName.equals("isAccessible")) {
                                super.visitInsn(Opcodes.POP);
                                super.visitInsn(Opcodes.ICONST_0);
                            } else {
                                super.visitInsn(Opcodes.POP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, compatOwner, "trySetAccessible",
                                        "(Ljava/lang/reflect/Member;)V", false);
                            }
                            return;
                        }
                        // ProcessBuilder.redirectXxx（API 26）：改走 noRedirect（默认 PIPE 流向，
                        // 消费 [pb, redirect] 两个栈值并还回 pb，与原 invokevirtual 栈形一致）
                        if ("java/lang/ProcessBuilder".equals(owner)
                                && (mName.equals("redirectError") || mName.equals("redirectInput")
                                || mName.equals("redirectOutput"))) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, compatOwner, "noRedirect",
                                    "(Ljava/lang/ProcessBuilder;Ljava/lang/Object;)Ljava/lang/ProcessBuilder;", false);
                            return;
                        }
                        Rewrite r = findRewrite(owner, mName, mDesc);
                        if (r != null) {
                            // 兼容方法签名 = receiver 首参 + 原方法参数（静态调用保持原样）
                            String cDesc = r.staticCall() ? mDesc
                                    : "(L" + r.recv() + ";" + mDesc.substring(1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, compatOwner, r.compat(), cDesc, false);
                        } else {
                            super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                        }
                    }
                };
            }
        };
        new ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private Rewrite findRewrite(String owner, String name, String desc) {
        for (Rewrite r : rewrites) {
            if (r.matches(owner, name, desc)) return r;
        }
        return null;
    }
}
