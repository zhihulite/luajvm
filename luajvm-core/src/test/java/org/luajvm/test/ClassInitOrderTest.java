package org.luajvm.test;

import org.luajvm.core.LuaString;
// 本用例要求 LuaString 是进程首个被**触发初始化**的 luajvm 类。import 纯属编译期，
//   不触发类加载（JLS 12.4.1），故为 LuaValue 加 import 不影响本用例的判别力。
import org.luajvm.core.LuaValue;

/**
 * java-only：任意公共入口都必须能作为进程内第一次 luajvm 调用。
 *
 * <p>本测试守的是**类初始化顺序**，不是字符串语义。缺陷形态：
 * 若进程第一次触及的是 {@code LuaString}（而非 {@code LuaValue}），则
 *
 * <pre>
 *   LuaString.&lt;clinit&gt;
 *     -> 先初始化父类 LuaValue.&lt;clinit&gt;（JVM 规定父类先于子类）
 *       -> LuaValue 的字段初始化器读 Metamethod.INDEX.tag
 *         -> Metamethod.&lt;clinit&gt; -> LuaString.newStr("__index")
 *           -> 重入 LuaString（同线程，JVM 不阻塞）
 *             -> 读驻留表字段  -  但 LuaString 自己的字段初始化器还没跑 -> null -> NPE
 * </pre>
 *
 * <p>该缺陷仅在"先碰 LuaString"的类加载链下暴露，主路径（RunLuaFile 等先碰
 * LuaValue）永远测不到，故类静态初始化改动必须冒烟多个入口。
 *
 * <p>因此本测试的第一条语句必须是 {@code LuaString} 的调用，且**不得**先触碰
 * {@code LuaValue}/{@code Globals}/{@code LuaPlatform} 中的任何一个。
 */
public final class ClassInitOrderTest {
    public static void main(String[] args) {
        // 进程内第一次 luajvm 调用，且刻意从 LuaString 而非 LuaValue 进入。
        LuaString first = LuaString.newStr("clinit_probe");
        boolean ok = first != null && "clinit_probe".equals(first.toJavaString());
        System.out.println((ok ? "  OK: " : "  FAIL: ")
                + "LuaString.newStr works as the first luajvm call (got "
                + (first == null ? "null" : "\"" + first.toJavaString() + "\"") + ")");

        // intern 恒等必须照常成立（重入期间建的串也要进同一张表）
        LuaString again = LuaString.newStr("clinit_probe");
        boolean identity = first == again;
        System.out.println((identity ? "  OK: " : "  FAIL: ")
                + "intern identity holds for strings created during clinit reentry");

        // 元方法名在上述重入链里建；也必须与事后 intern 的同字节串同一对象
        LuaString index = LuaString.newStr("__index");
        boolean mmIdentity = index == LuaValue.INDEX;
        System.out.println((mmIdentity ? "  OK: " : "  FAIL: ")
                + "metamethod tag interned during clinit is identical to a later intern");

        if (!(ok && identity && mmIdentity)) {
            System.err.println("ClassInitOrderTest: FAILED");
            System.exit(1);
        }
        System.out.println("ClassInitOrderTest: PASS");
    }
}
