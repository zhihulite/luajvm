package org.luajvm.test;

import org.luajvm.bind.Platform;
import org.luajvm.core.CallInfo;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaGC;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;

/**
 * java-only 门禁：GC 的 atomic 相位必须清死栈片段并收缩非运行线程的栈。
 *
 * <p>守两条泄漏缺陷：
 * <ol>
 *   <li><b>死栈片段未清</b>：C 在 {@code lgc.c:atomic} 显式把 top 之上置 nil；
 *       Java 的 markThreadFrames 仅标到 top，其上的槽仍是 Java 强引用 => JVM 不回收，
 *       挂 WeakReference 连做多轮 {@code System.gc()} 仍被钉住。</li>
 *   <li><b>栈容量不回落</b>：C 在 atomic 相位调 {@code luaD_shrinkstack}（含
 *       {@code luaE_shrinkCI}）；若无 GC 侧调用（shrinkStack 仅在 pcall 溢出恢复处
 *       被调），栈与 CI 链经多轮 fullGC 均不回落。</li>
 * </ol>
 *
 * <p><b>前置自检不可删</b>：每项都先断言"确实造出了滞留条件"（栈真的长了、
 * top 之上真的有表）。否则被测路径一旦没走到，断言就退化为恒真空转。
 */
public final class StackRetentionTest {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        deadSliceCleared();
        capacityShrinks();
        mainThreadShrinks();
        openUpvalListDrains();
        hookReleasable();

        if (failures > 0) {
            System.out.println("StackRetentionTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("StackRetentionTest: PASS");
    }

    /** 死栈片段：深递归返回后，top 之上不得残留表；且残留者必须可被 JVM 回收。 */
    private static void deadSliceCleared() throws Exception {
        Globals g = Platform.standardGlobals();
        LuaThread mt = mainThreadOf(g);

        // 非尾调用（+1 阻止尾优化），每帧在栈上留一个表
        g.execute("local function d(n)\n"
                + "  local pad = {tag='MARK', depth=n}\n"
                + "  if n > 0 then return d(n-1) + 1 end\n"
                + "  return 0\n"
                + "end\n"
                + "return d(60)\n");

        // 前置自检 1：栈必须真的长过初始容量，否则本用例没造出滞留条件
        int len = mt.stack == null ? 0 : mt.stack.length;
        check(len > 100, "前置：深递归后栈应长过 100 槽（实测 " + len + "）");

        // 收集 GC 前 top 之上的表数量 - 前置自检 2
        int tablesBefore = 0;
        for (int i = mt.top; i < len; i++) {
            LuaValue v = mt.stack[i];
            if (v != null && v.istable()) tablesBefore++;
        }
        check(tablesBefore > 0,
                "前置：GC 前 top 之上应有死表（实测 " + tablesBefore + "）；"
                        + "若为 0 则本用例无判别力");

        // 取最高索引的表作样本：后续若压新帧仅覆盖低位槽，
        //   在低位取样会把"被覆盖"误读成"已回收"。
        LuaValue sample = null;
        int sampleIdx = -1;
        for (int i = mt.top; i < len; i++) {
            LuaValue v = mt.stack[i];
            if (v != null && v.istable()) {
                sample = v;
                sampleIdx = i;
            }
        }
        WeakReference<LuaValue> ref = sample == null ? null : new WeakReference<>(sample);
        sample = null;

        // 从 Java 侧驱动 GC：不执行 Lua，避免新帧覆盖被测槽
        LuaGC.fullGC(g, false);

        int tablesAfter = 0;
        for (int i = mt.top; i < mt.stack.length; i++) {
            LuaValue v = mt.stack[i];
            if (v != null && v.istable()) tablesAfter++;
        }
        check(tablesAfter == 0,
                "atomic 相位后 top 之上不应残留表（实测 " + tablesAfter
                        + "，GC 前 " + tablesBefore + "）");

        if (ref != null) {
            for (int i = 0; i < 8 && ref.get() != null; i++) {
                System.gc();
                try {
                    Thread.sleep(40);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            check(ref.get() == null,
                    "死槽（原槽 " + sampleIdx + "）里的表应可被 JVM 回收");
        }
    }

    /** 栈容量：深递归已返回、在浅层挂起的协程，栈与 CI 链必须回落。 */
    private static void capacityShrinks() throws Exception {
        Globals g = Platform.standardGlobals();

        g.execute("co = coroutine.create(function()\n"
                + "  local function d(n)\n"
                + "    local pad = {n}\n"
                + "    if n > 0 then return d(n-1) + 1 end\n"
                + "    return 0\n"
                + "  end\n"
                + "  local r = d(400)\n"      // 递归已返回 => 容量成死容量
                + "  coroutine.yield(r)\n"    // 浅层挂起
                + "  return r\n"
                + "end)\n"
                + "coroutine.resume(co)\n");

        LuaThread co = null;
        List<LuaThread> all = allThreadsOf(g);
        for (int i = 0; i < all.size(); i++) {
            LuaThread t = all.get(i);
            if (t != null && !isMainOf(t) && t.stack != null && t.stack.length > 200) {
                co = t;
                break;
            }
        }
        // 前置自检 3：必须真找到峰值状态的挂起协程
        check(co != null, "前置：应找到栈 >200 槽的挂起协程；未找到则本用例无判别力");
        if (co == null) return;

        int peakLen = co.stack.length;
        int peakCi = chainLen(co);
        check(peakLen > 1000, "前置：协程栈峰值应 >1000 槽（实测 " + peakLen + "）");
        check(peakCi > 300, "前置：CI 链峰值应 >300 节点（实测 " + peakCi + "）");

        for (int i = 0; i < 4; i++) {
            LuaGC.fullGC(g, false);
        }

        int nowLen = co.stack.length;
        int nowCi = chainLen(co);
        // 判据用相对上界（peak/4），不锁死具体数值：帧布局微调会让绝对值漂移，
        //   锁死则造出与泄漏无关的假失败。
        check(nowLen < peakLen / 4,
                "协程栈应收缩到峰值 1/4 以下（峰值 " + peakLen + " -> 实测 " + nowLen + "）");
        check(nowCi < peakCi / 4,
                "CI 链应收缩到峰值 1/4 以下（峰值 " + peakCi + " -> 实测 " + nowCi + "）");
    }

    /**
     * 主线程栈容量须随 GC 回落（延迟收缩）。
     *
     * <p>主线程在 GC 时恒为 {@code g.running}，而运行线程的栈数组不能在 atomic 相位
     * 直接换掉：{@code LuaVM.execute} 把它缓存在方法局部量，三处 {@code checkGC}
     * 之后都不刷新，换掉会让解释器继续写旧数组、读到错乱值。故 GC 仅置
     * {@code pendingStackShrink}，由 {@code execute} 在 {@code startfunc} 消费。
     *
     * <p>因此 <b>fullGC 之后必须再执行一段 Lua</b> 才看得到收缩；仅调 fullGC 就断言
     * 会得到假失败。
     */
    private static void mainThreadShrinks() throws Exception {
        Globals g = Platform.standardGlobals();
        LuaThread mt = mainThreadOf(g);

        g.execute("local function d(n)\n"
                + "  local pad = {n}\n"
                + "  if n > 0 then return d(n-1) + 1 end\n"
                + "  return 0\n"
                + "end\n"
                + "return d(400)\n");

        int peakLen = mt.stack.length;
        int peakCi = chainLen(mt);
        check(peakLen > 1000, "前置：主线程栈峰值应 >1000 槽（实测 " + peakLen + "）");
        check(peakCi > 300, "前置：主线程 CI 链峰值应 >300 节点（实测 " + peakCi + "）");

        LuaGC.fullGC(g, false);
        // 延迟收缩到 startfunc 才实收：跑一段最简 Lua 触发
        g.execute("return 1");

        int nowLen = mt.stack.length;
        check(nowLen < peakLen / 4,
                "主线程栈应收缩到峰值 1/4 以下（峰值 " + peakLen + " -> 实测 " + nowLen + "）");

        // CI 链每轮 atomic 减半，多跑几轮
        for (int i = 0; i < 6; i++) {
            LuaGC.fullGC(g, false);
            g.execute("return 1");
        }
        int nowCi = chainLen(mt);
        check(nowCi < peakCi / 4,
                "主线程 CI 链应收缩到峰值 1/4 以下（峰值 " + peakCi
                        + " -> 实测 " + nowCi + "）");
    }

    /**
     * open upvalue 列表须随作用域退出摘链（C: luaF_close）。
     *
     * <p>取样必须在**活跃作用域内**：闭包离开作用域即已摘链，从 Java 侧在
     * {@code execute} 返回后取样恒为 0 => 恒真空转。故注册 Java 回调，由 Lua 在
     * upvalue 仍打开时回调进来读列表长度。
     */
    private static void openUpvalListDrains() throws Exception {
        final Globals g = Platform.standardGlobals();
        final LuaThread mt = mainThreadOf(g);

        int base = mt.openupval == null ? 0 : mt.openupval.size();
        final int[] peakBox = {0};
        g.set("peekUpval", new LuaFunction() {
            @Override
            public Varargs call(Varargs a) {
                LuaThread cur = g.running != null ? g.running : mt;
                int n = cur.openupval == null ? 0 : cur.openupval.size();
                if (n > peakBox[0]) peakBox[0] = n;
                return LuaValue.NIL;
            }
        });

        g.execute("local sink = {}\n"
                + "local function nest(n)\n"
                + "  local captured = {n}\n"
                + "  sink[#sink+1] = function() return captured end\n"
                + "  if n > 0 then return nest(n-1) end\n"
                + "  peekUpval()\n"
                + "  return 0\n"
                + "end\n"
                + "nest(150)\n"
                + "_G.peekHold = sink\n");

        int peak = peakBox[0];
        check(peak > base, "前置：活跃作用域内应有 open upvalue（峰值 " + peak
                + "，基线 " + base + "）；若不大于基线则本用例无判别力");

        g.execute("_G.peekHold = nil");
        LuaGC.fullGC(g, false);
        int after = mt.openupval == null ? 0 : mt.openupval.size();
        check(after <= base, "作用域退出后 openupval 应回落到基线（基线 " + base
                + " -> 实测 " + after + "）");
    }

    /**
     * {@code debug.sethook()} 清除后，原 hook 函数须可被 JVM 回收。
     *
     * <p>必须 watch {@code _HOOKKEY} 表里的**真实函数**，不能 watch
     * {@code thread.hook}：后者仅放静态哨兵 {@code HOOKF}，恒被钉住，
     * 对它挂弱引用会恒报"滞留"。
     */
    private static void hookReleasable() throws Exception {
        Globals g = Platform.standardGlobals();
        LuaThread mt = mainThreadOf(g);

        g.execute("_G.hookFn = function() end\ndebug.sethook(_G.hookFn, 'l')\n");
        check(mt.hook != null && !mt.hook.isnil(),
                "前置：hook 应已安装（hookmask=" + mt.hookmask + "）");

        LuaTable hookTable = (LuaTable) g.registry.hashGet(LuaString.newStr("_HOOKKEY"));
        LuaValue realHook = hookTable == null ? null : hookTable.get(mt);
        check(realHook != null && !realHook.isnil(),
                "前置：应能在 _HOOKKEY 表取到真实 hook 函数");
        if (realHook == null || realHook.isnil()) return;

        WeakReference<Object> ref = new WeakReference<>(realHook);
        realHook = null;

        g.execute("debug.sethook()\n_G.hookFn = nil\n");
        LuaGC.fullGC(g, false);

        LuaValue left = hookTable.get(mt);
        check(left == null || left.isnil(), "sethook() 后 _HOOKKEY 表项应已清除");

        for (int i = 0; i < 8 && ref.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(40);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        check(ref.get() == null, "清除后原 hook 函数应可被 JVM 回收");
    }

    private static int chainLen(LuaThread t) {
        int n = 0;
        for (CallInfo c = t.base_ci; c != null && n < 200000; c = c.next) n++;
        return n;
    }

    // -- 反射辅助：这些字段是 core 包私有的，测试在 org.luajvm.test
    //    （沿用 KCacheRetentionTest 的做法，不为测试放宽生产可见性）

    private static LuaThread mainThreadOf(Globals g) throws Exception {
        Field f = Globals.class.getDeclaredField("mainThread");
        f.setAccessible(true);
        return (LuaThread) f.get(g);
    }

    @SuppressWarnings("unchecked")
    private static List<LuaThread> allThreadsOf(Globals g) throws Exception {
        Field gcf = Globals.class.getDeclaredField("gc");
        gcf.setAccessible(true);
        Object gcState = gcf.get(g);
        Field atf = gcState.getClass().getDeclaredField("allThreads");
        atf.setAccessible(true);
        return (List<LuaThread>) atf.get(gcState);
    }

    private static boolean isMainOf(LuaThread t) throws Exception {
        Field f = LuaThread.class.getDeclaredField("isMain");
        f.setAccessible(true);
        return (Boolean) f.get(t);
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("  OK: " + what);
        } else {
            System.out.println("  FAIL: " + what);
            failures++;
        }
    }
}
