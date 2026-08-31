// java-only 门禁：宿主侧 callLua 入口必须退栈（成功清返回值槽、失败走 luaD_pcall 恢复段）。
package org.luajvm.test;

import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaGC;
import org.luajvm.core.LuaValue;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/**
 * {@code LuaCall} 的 8 个 Varargs／快路径桥接入口（{@code callLua} 三个重载、
 * {@code callNoYield} 三个、{@code call} 的 2/3 参快路径）把结果打包成 {@code Varargs}
 * 之后，必须把 {@code L.top} 退回 func 并把那些槽置 null。
 *
 * <p>C 的 {@code lua_pcall} 把结果留在栈上交调用方 pop；Java 侧已复制进 {@code Varargs}，
 * 槽再无用途。若只记 {@code func = L.top} 就直接 return，每次宿主调用都会在
 * 栈上永久留下 nres 个槽，后果有两层：
 * <ol>
 *   <li><b>栈无界增长</b>：{@code top} 单调上涨；而 {@code stackInUse} 随之变高，使
 *       {@code shrinkStackForGc} 的 {@code stack_last > inuse*3} 永不成立 ⇒ 栈永不收缩，
 *       {@code StackRetentionTest} 那套收缩机制被彻底旁路。</li>
 *   <li><b>返回值被钉死</b>：滞留槽是 Java 强引用，Lua 侧与 Java 侧都丢掉引用后仍不可回收。
 *       宿主每次 {@code execute} 的结果都永久滞留，是长驻进程最直接的泄漏形态。</li>
 * </ol>
 *
 * <p><b>判别力前置</b>：先断言基线 {@code top} 与栈长确有基准值、且被观测的返回表确实
 * 只经栈槽可达（脚本侧已置 nil）。否则一旦构造失手，「未增长」「可回收」都会恒真。
 * 收缩对照组的递归形态同理有两个坑（尾调用不扩容、扁平通道零栈帧），对策见对照段注释。
 *
 * <p><b>错误路径</b>：宿主 catch(LuaError) 后 {@code top}/{@code ci}/CallInfo 链同样必须
 * 有界（入口 catch 走 luaD_pcall 恢复段）。注意判定错误对象可回收性时不能用
 * {@code e.luaError}——表错误在该字段是字符串化形式（短串被驻留表软引用持有，
 * {@code System.gc()} 不清软引用，会误报泄漏；实际的表对象回收正常）。
 */
public final class HostCallStackBoundTest {
    private static final int CALLS = 20000;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Globals g = Platform.standardGlobals();

        Object th = field(g, "mainThread");
        if (th == null) th = field(g, "running");
        check(th != null, "前置：应能取到主线程对象");
        if (th == null) fail();

        int baseTop = (Integer) field(th, "top");
        int baseLen = arrayLen(field(th, "stack"));
        check(baseLen > 0, "前置：基线栈数组应非空（实测 " + baseLen + " 槽）");

        // 每次调用返回 1 个值 —— 缺陷态下每轮净留 1 槽。
        for (int i = 0; i < CALLS; i++) {
            g.execute("return 1");
        }

        int afterTop = (Integer) field(th, "top");
        int afterLen = arrayLen(field(th, "stack"));

        // 核心断言 1：top 不得随调用次数上涨。允许个位数波动（基线本身含若干常驻槽）。
        check(afterTop - baseTop <= 8,
                CALLS + " 次 execute 后 top 不得上涨（基线 " + baseTop
                        + " -> 实测 " + afterTop + "）");

        // 核心断言 2：栈数组不得随调用次数扩容。
        check(afterLen <= baseLen * 2,
                CALLS + " 次 execute 后栈数组不得扩容（基线 " + baseLen
                        + " -> 实测 " + afterLen + "）");

        // 核心断言 3：返回值必须可回收。
        //   取一个新建表作返回值，只经「宿主调用的返回槽」这一条路径可达。
        LuaValue returned = g.execute("RET = {tag='probe'} return RET").arg1();
        check(returned != null && !returned.isnil(), "前置：应拿到返回的表");
        WeakReference<Object> ref = new WeakReference<>(returned);
        // 断开脚本侧与本地强引用，只剩「若存在缺陷则仍在栈槽里」那一条
        g.execute("RET = nil");
        returned = null;

        boolean collected = false;
        for (int attempt = 0; attempt < 6 && !collected; attempt++) {
            LuaGC.fullGC(g, false);
            g.execute("return 1");
            System.gc();
            Thread.sleep(30);
            collected = ref.get() == null;
        }
        check(collected,
                "宿主调用的返回值在双侧引用断开后必须可回收（缺陷态下被栈槽永久钉住）");

        // 对照：栈收缩机制本身仍工作（证明上面若失败是「槽没退」而非「收缩坏了」）。
        //   两个坑都要避开：return d(...) 是尾调用（帧复用，不扩容）；纯整数体
        //   可能命中扁平通道（零 CallInfo 零 Lua 栈）。体内放表构造器让 analyzeRec
        //   必然拒收，走装箱帧。
        g.execute("local function d(n) if n > 0 then local t = {d(n - 1)} return 1 + t[1] end"
                + " return 0 end return d(150)");
        int deepLen = arrayLen(field(th, "stack"));
        check(deepLen > baseLen, "前置：深递归应让栈真的扩容（实测 " + deepLen + " 槽）");
        for (int i = 0; i < 4; i++) {
            LuaGC.fullGC(g, false);
            g.execute("return 1");
        }
        int shrunkLen = arrayLen(field(th, "stack"));
        check(shrunkLen < deepLen,
                "对照：深递归后的栈应能收缩（峰值 " + deepLen + " -> 实测 " + shrunkLen + "）");

        // 错误路径：宿主 catch(LuaError) 后 top/栈/CallInfo 链必须有界。
        //   C 宿主只能经 lua_pcall 调 Lua（luaD_pcall 恢复 ci/top）；Java 入口 catch
        //   若只恢复 nCcalls/nny 就重抛，失败调用会在栈与 CallInfo 链上无限滞留，
        //   故入口 catch 走 luaD_pcall 恢复段。
        int errBaseTop = (Integer) field(th, "top");
        int errBaseLen = arrayLen(field(th, "stack"));
        int errBaseNci = (Integer) field(th, "nci");
        int caught = 0;
        for (int i = 0; i < 5000; i++) {
            try {
                g.execute("error('boom" + i + "')");
            } catch (LuaError e) {
                caught++;
            }
        }
        check(caught == 5000, "前置：5000 次失败调用应全部抛 LuaError（实测 " + caught + "）");
        int errTop = (Integer) field(th, "top");
        int errLen = arrayLen(field(th, "stack"));
        int errNci = (Integer) field(th, "nci");
        check(errTop - errBaseTop <= 8,
                "5000 次失败调用后 top 不得上涨（基线 " + errBaseTop + " -> 实测 " + errTop + "）");
        check(errLen <= errBaseLen * 2,
                "5000 次失败调用后栈数组不得扩容（基线 " + errBaseLen + " -> 实测 " + errLen + "）");
        check(errNci - errBaseNci <= 8,
                "5000 次失败调用后 CallInfo 链不得增长（基线 " + errBaseNci + " -> 实测 " + errNci + "）");

        if (failures > 0) fail();
        System.out.println("HostCallStackBoundTest: PASS");
    }

    private static void fail() {
        System.out.println("HostCallStackBoundTest FAILED: " + failures + " 处");
        System.exit(1);
    }

    private static int arrayLen(Object arr) {
        return arr == null ? 0 : java.lang.reflect.Array.getLength(arr);
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    private static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // 继续找父类
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }
}
