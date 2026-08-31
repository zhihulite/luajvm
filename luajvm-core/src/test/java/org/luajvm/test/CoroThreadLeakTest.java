package org.luajvm.test;

import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaGC;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.lang.ref.WeakReference;
import org.luajvm.core.ContSupport;

/**
 * java-only 门禁：GC 回收 yielded 协程时必须终止其平台线程。
 *
 * <p>守护的泄漏缺陷：{@code closeFromCollector} 仅清理 Lua 状态
 * （stack、upval、errorValue）但未设置 {@code forceClose}，导致 yielded 协程
 * 在 {@code runCoroutine} 的 {@code while (status == LUA_YIELD) coroCond.await()}
 * 处被 {@code signalAll()} 唤醒后重新进入等待，平台线程永不退出。
 *
 * <p><b>平台差异</b>：
 * <ul>
 *   <li><b>虚拟线程模式（JVM 21+）</b>：泄漏的是虚拟线程，JVM 可回收载体线程
 *       但仍违背 C 语义（{@code luaE_freethread} 不留执行残留）。</li>
 *   <li><b>平台线程模式（Android）</b>：泄漏的是真实 OS 线程及平台默认原生栈，
 *       仅进程结束才释放 - 典型的 Android 资源泄漏。</li>
 * </ul>
 *
 * <p>本测验证协程对象不可达时 Java GC 调 {@code closeFromCollector} 能终止线程：
 * 创建 yielded 协程、丢弃强引用、fullGC、断言弱引用已清空（协程被回收）、
 * 等待足够时间让线程退出、断言活跃线程数未增长。
 */
public final class CoroThreadLeakTest {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // 这一条与线程模式无关（三种模式都要求 close 不得恢复协程体），故放在
        //   cont SKIP 之前：它守 close() 路径的 forceClose，而下面的用例守 GC 路径。
        closedCoroDoesNotResumeBody();

        // Continuation 模式（-Dluajvm.cont=true 且 API 可用）下协程不创建任何线程，
        //   本门禁守护的"挂起协程泄漏线程"在结构上不可能发生 => 前置自检
        //   （应有 >=20 个 LuaCoroutine 线程 park 着）必然为 0 而失去判别力。
        //   明确跳过并说明，而非放宽断言  -  放宽会让线程模式下的真实泄漏也漏过。
        if (ContSupport.SUPPORTED) {
            System.out.println("  SKIP: Continuation 模式不创建协程线程，本门禁无判别力");
            System.out.println("        （该模式下\"挂起协程泄漏线程\"结构上不可能发生；"
                    + "线程模式的守护由不带 -Dluajvm.cont 的同名任务承担）");
            if (failures > 0) {
                System.out.println("CoroThreadLeakTest: " + failures + " FAILED");
                System.exit(1);
            }
            System.out.println("CoroThreadLeakTest: PASS (thread-leak part skipped for cont mode)");
            return;
        }
        boolean vthread = !"false".equals(System.getProperty("luajvm.vthread"));
        System.out.println("  线程模式: " + (vthread ? "虚拟线程" : "平台线程"));
        if (vthread) {
            yieldedCoroThreadTerminates();
        } else {
            platformThreadModeTerminates();
        }
        // 与线程模式无关：守 closeFromCollector 的 forceClose（GC 路径干净展开）。
        //   两种模式都要跑 —— 虚拟线程模式是 runCoreTests 的默认配置，只放平台分支等于
        //   一键全套件下这半修复无人守护。
        collectedCoroDoesNotResumeBody();

        if (failures > 0) {
            System.out.println("CoroThreadLeakTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("CoroThreadLeakTest: PASS");
    }

    /**
     * 显式 {@code coroutine.close(co)} 关闭挂起协程时，不得恢复执行协程体。
     *
     * <p>守 {@code close()} 里 {@code if (wasSuspended) forceClose = true;} 这一半修复。
     * 与 {@link #collectedCoroDoesNotResumeBody()}（守 GC 路径）分工：
     * <ul>
     *   <li><b>GC 路径</b>（{@code closeFromCollector}）：字段已被清空（{@code stack=null}
     *       等），协程体一恢复就撞上 {@code luaD_growstack} 的
     *       "attempt to realloc stack of freed thread"，故只设 {@code status} 不设
     *       {@code forceClose} 时副作用<b>仍为 0</b> —— 那条断言对 {@code forceClose}
     *       恒真，只有防御价值。</li>
     *   <li><b>close 路径</b>：{@code resetThread} 后栈仍可用，协程体会<b>真的跑起来</b>
     *       （注掉 {@code forceClose} 即复现）⇒ 这里才是那半修复的可观测点。</li>
     * </ul>
     *
     * <p>对齐 C：{@code lua_closethread} 用 {@code luaD_throwbaselevel} 同步展开协程栈，
     * yield 点之后一行都不跑。
     */
    private static void closedCoroDoesNotResumeBody() throws Exception {
        Globals g = Platform.standardGlobals();
        final int[] sideEffects = {0};
        g.set("record_close_effect", new LuaFunction() {
            @Override
            public Varargs call(Varargs a) {
                sideEffects[0]++;
                return LuaValue.NONE;
            }
        });

        g.execute("co_close = coroutine.create(function()\n"
                + "  coroutine.yield('suspended')\n"
                + "  record_close_effect()\n"   // yield 后第一条
                + "  record_close_effect()\n"
                + "  return 'never'\n"
                + "end)\n"
                + "coroutine.resume(co_close)\n");
        check(sideEffects[0] == 0,
                "前置：close 前 yield 点之后不应执行（实测 " + sideEffects[0] + "）");
        check("suspended".equals(g.execute("return coroutine.status(co_close)")
                        .arg1().toJavaString()),
                "前置：协程应处于 suspended（否则 close 走的不是 wasSuspended 分支，本用例无判别力）");

        g.execute("CLOSE_OK, CLOSE_ERR = coroutine.close(co_close)");
        // 协程线程展开需调度时间；轮询到稳定再断言
        for (int round = 0; round < 20 && sideEffects[0] == 0; round++) {
            Thread.sleep(25);
        }
        Thread.sleep(100);

        check(sideEffects[0] == 0,
                "close 挂起协程不得恢复执行协程体（实测副作用 " + sideEffects[0]
                        + " 次）；对齐 C 的 luaD_throwbaselevel");
        check(g.get("CLOSE_OK").toboolean(),
                "close 应返回 true（实测 " + g.get("CLOSE_OK") + "，err="
                        + g.get("CLOSE_ERR") + "）");
        check("dead".equals(g.execute("return coroutine.status(co_close)")
                        .arg1().toJavaString()),
                "close 后 status 应为 dead（实测 "
                        + g.execute("return coroutine.status(co_close)").arg1() + "）");
    }

    /**
     * yielded 协程被 GC 回收时，其平台/虚拟线程必须终止而非永久 park。
     *
     * <p>关键点：
     * <ol>
     *   <li>基线活跃线程数：多轮创建销毁协程后的稳态（排除 JVM 后台线程抖动）。</li>
     *   <li>批量创建 yielded 协程：每个都在 {@code await()} 处 park。</li>
     *   <li>丢弃强引用 + fullGC：协程对象被回收，触发 {@code closeFromCollector}。</li>
     *   <li>给足时间让线程退出：虚拟线程几乎立即退出，平台线程需等调度。</li>
     *   <li>断言活跃线程数未增长：泄漏形态是每个 yielded 协程钉住一个线程，
     *       回收后必须回到基线。</li>
     * </ol>
     */
    private static void yieldedCoroThreadTerminates() throws Exception {
        Globals g = Platform.standardGlobals();

        // 预热：让 JVM 后台线程（GC、JIT）稳定
        for (int warmup = 0; warmup < 3; warmup++) {
            g.execute("local co = coroutine.create(function() coroutine.yield(42) end)\n"
                    + "coroutine.resume(co)\n");
            LuaGC.fullGC(g, false);
        }

        int baselineThreads = Thread.activeCount();
        System.out.println("  基线活跃线程数: " + baselineThreads);

        // 批量创建 yielded 协程并立即丢弃
        WeakReference<?>[] refs = new WeakReference[20];
        for (int i = 0; i < refs.length; i++) {
            g.execute("_coro_temp = coroutine.create(function()\n"
                    + "  coroutine.yield('suspended')\n"
                    + "  return 'never'\n"
                    + "end)\n"
                    + "coroutine.resume(_coro_temp)");
            Object coro = g.get("_coro_temp");
            g.execute("_coro_temp = nil");
            refs[i] = new WeakReference<>(coro);
        }

        // 前置自检：协程对象真的被创建了
        int aliveBefore = 0;
        for (WeakReference<?> ref : refs) {
            if (ref.get() != null) aliveBefore++;
        }
        check(aliveBefore == refs.length,
                "前置：创建 " + refs.length + " 个 yielded 协程（实测 " + aliveBefore + " 存活）");

        // 关键：先驱动 Lua GC 标记不可达协程为白色（待回收），否则 allThreads 强引用钉住
        LuaGC.fullGC(g, false);
        // 再驱动 Java GC 回收被 Lua 标记为死的协程对象
        for (int round = 0; round < 8 && anyAlive(refs); round++) {
            System.gc();
            Thread.sleep(50);
        }

        int aliveAfter = 0;
        for (WeakReference<?> ref : refs) {
            if (ref.get() != null) aliveAfter++;
        }
        check(aliveAfter == 0,
                "GC 后协程对象应被回收（实测残留 " + aliveAfter + "/" + refs.length + "）");

        // 给足时间让线程退出：虚拟线程几乎立即退出，平台线程需等系统调度
        Thread.sleep(200);

        int nowThreads = Thread.activeCount();
        // 容忍 +/-2 抖动（JVM 后台线程可能临时启动），但不容忍整批协程线程滞留
        int delta = nowThreads - baselineThreads;
        check(Math.abs(delta) <= 2,
                "活跃线程数应回到基线（基线 " + baselineThreads
                        + " -> 实测 " + nowThreads + "，delta " + delta + "）；"
                        + "修复前每轮泄漏 " + refs.length + " 线程");
    }

    /**
     * 同一场景在平台线程模式下复测（Android 的真实配置）。
     *
     * <p>虚拟线程下 {@code Thread.activeCount()} 不计虚拟线程，只有弱引用断言有效；
     * 平台线程模式下两条断言都有效，且泄漏的是真实 OS 线程及平台默认原生栈，
     * 是 Android 上的实际故障形态。用 {@code LuaCoroutine} 线程名精确计数。
     */
    private static void platformThreadModeTerminates() throws Exception {
        Globals g = Platform.standardGlobals();

        WeakReference<?>[] refs = new WeakReference[20];
        for (int i = 0; i < refs.length; i++) {
            g.execute("_coro_temp = coroutine.create(function()\n"
                    + "  coroutine.yield('suspended')\n"
                    + "  return 'never'\n"
                    + "end)\n"
                    + "coroutine.resume(_coro_temp)");
            Object coro = g.get("_coro_temp");
            g.execute("_coro_temp = nil");
            refs[i] = new WeakReference<>(coro);
        }

        int coroThreadsBefore = countCoroutineThreads();
        check(coroThreadsBefore >= refs.length,
                "前置：应有 >=" + refs.length + " 个 LuaCoroutine 线程 park 着（实测 "
                        + coroThreadsBefore + "）");

        LuaGC.fullGC(g, false);
        for (int round = 0; round < 8 && anyAlive(refs); round++) {
            System.gc();
            Thread.sleep(50);
        }

        int aliveAfter = 0;
        for (WeakReference<?> ref : refs) {
            if (ref.get() != null) aliveAfter++;
        }
        check(aliveAfter == 0,
                "平台线程模式：GC 后协程对象应被回收（实测残留 " + aliveAfter + "/"
                        + refs.length + "）");

        // 等线程真正退出（平台线程需系统调度，比虚拟线程慢）
        for (int round = 0; round < 20 && countCoroutineThreads() > 0; round++) {
            Thread.sleep(50);
        }
        int coroThreadsAfter = countCoroutineThreads();
        check(coroThreadsAfter == 0,
                "平台线程模式：LuaCoroutine 线程应全部退出（实测残留 " + coroThreadsAfter
                        + "/" + coroThreadsBefore + "）；缺陷态下全部永久 park");
    }

    /**
     * 被回收的 yielded 协程必须干净展开，不得恢复执行协程体。
     *
     * <p>对齐 C：{@code luaE_freethread} 仅释放栈内存，协程体余下部分一行都不跑。
     * Java 若只让线程跳出 await（设 status）而不设 forceClose，协程体会真的恢复执行，
     * 直到 {@code luaD_growstack} 的 "attempt to realloc stack of freed thread" 兜住  -
     * 线程虽也退出，但走 {@code catch (Throwable)} 错误路径。本测用 yield 之后第一条语句
     * 调 Java 计数函数来钉住"不得恢复执行"。
     *
     * <p>注：当前实现下计数恒为 0（growstack 守卫在第一条 Lua 指令前就抛），故本测的价值
     * 在于回归防线 - 若将来清字段顺序变化让协程体真跑起来，此断言会立刻失败。
     */
    private static void collectedCoroDoesNotResumeBody() throws Exception {
        Globals g = Platform.standardGlobals();
        final int[] sideEffects = {0};
        g.set("record_side_effect", new LuaFunction() {
            @Override
            public Varargs call(Varargs a) {
                sideEffects[0]++;
                return LuaValue.NONE;
            }
        });

        WeakReference<?>[] refs = new WeakReference[20];
        for (int i = 0; i < refs.length; i++) {
            g.execute("_coro_temp = coroutine.create(function()\n"
                    + "  coroutine.yield('suspended')\n"
                    + "  record_side_effect()\n"   // yield 后第一条语句
                    + "  return 'never'\n"
                    + "end)\n"
                    + "coroutine.resume(_coro_temp)");
            Object coro = g.get("_coro_temp");
            g.execute("_coro_temp = nil");
            refs[i] = new WeakReference<>(coro);
        }
        check(sideEffects[0] == 0, "前置：yield 点之后不应执行（实测 " + sideEffects[0] + "）");

        LuaGC.fullGC(g, false);
        for (int round = 0; round < 8 && anyAlive(refs); round++) {
            System.gc();
            Thread.sleep(50);
        }
        // 等展开完成
        for (int round = 0; round < 20 && countCoroutineThreads() > 0; round++) {
            Thread.sleep(50);
        }
        Thread.sleep(100);

        check(sideEffects[0] == 0,
                "回收的协程不得恢复执行协程体（实测副作用 " + sideEffects[0] + " 次）；"
                        + "对齐 C 的 luaE_freethread");

        collectedCoroUnwindsCleanly();
    }

    /**
     * GC 回收挂起协程时，其线程必须走 {@code CloseSelf} 干净展开，而非 growstack 兜底。
     *
     * <p>这条守 {@code closeFromCollector} 里 {@code forceClose = true} 那一半修复。
     * 上面那条"副作用为 0"对它<b>恒真</b>：GC 路径下 stack 等字段已被置空，协程体一恢复
     * 就撞上 {@code luaD_growstack} 的 "attempt to realloc stack of freed thread"，
     * 无论有无 {@code forceClose}，副作用都是 0。故须改用<b>退出路径</b>取证。
     *
     * <p>两条路径在 {@code runCoroutine} 里终态可区分：
     * <ul>
     *   <li>有 {@code forceClose}：{@code lua_yieldk} 抛 {@code CloseSelf}（errorValue
     *       为 null）→ {@code catch (CloseSelf)} 正常关闭分支 → {@code status = LUA_OK}。</li>
     *   <li>缺 {@code forceClose}：协程体真恢复执行 → growstack 抛错 →
     *       {@code catch (LuaError)} → {@code status = LUA_ERRRUN}。</li>
     * </ul>
     *
     * <p>取证手法：{@code closeFromCollector} 由 Lua GC 的 sweep 按 <b>Lua 侧</b>可达性
     * 触发（{@code sweepByColor}），与 Java 引用无关。故测试可持 Java 强引用用于读
     * {@code status}，同时把 Lua 侧变量置 nil 让 sweep 照常回收 —— 两者不冲突。
     */
    private static void collectedCoroUnwindsCleanly() throws Exception {
        Globals g = Platform.standardGlobals();
        g.execute("_unwind_coro = coroutine.create(function()\n"
                + "  coroutine.yield('suspended')\n"
                + "  return 'never'\n"
                + "end)\n"
                + "coroutine.resume(_unwind_coro)");

        LuaThread co = (LuaThread) g.get("_unwind_coro");
        check(co.status == LuaThread.LUA_YIELD,
                "前置：协程应处于 LUA_YIELD（否则不走 wasSuspended 分支，本断言无判别力）");

        g.execute("_unwind_coro = nil");
        LuaGC.fullGC(g, false);

        // 等线程跑完 runCoroutine 的 catch 分支并写入终态
        for (int round = 0; round < 40 && co.status == LuaThread.LUA_YIELD; round++) {
            Thread.sleep(25);
        }
        Thread.sleep(100);

        check(co.status != LuaThread.LUA_YIELD,
                "前置：sweep 应已调 closeFromCollector 令线程退出 await（实测 status "
                        + co.status + "）");
        check(co.status == LuaThread.LUA_OK,
                "回收的协程须经 CloseSelf 干净展开（status 应为 LUA_OK="
                        + LuaThread.LUA_OK + "，实测 " + co.status + "；LUA_ERRRUN="
                        + LuaThread.LUA_ERRRUN + " 表示走了 growstack 兜底 ⇒ forceClose 未设）");
    }

    /** 按线程名统计存活的协程执行线程；虚拟线程不在线程组里，故仅在平台线程模式有意义。 */
    private static int countCoroutineThreads() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        Thread[] threads = new Thread[root.activeCount() + 64];
        int n = root.enumerate(threads, true);
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (threads[i] != null && "LuaCoroutine".equals(threads[i].getName())
                    && threads[i].isAlive()) {
                count++;
            }
        }
        return count;
    }

    private static boolean anyAlive(WeakReference<?>[] refs) {
        for (WeakReference<?> ref : refs) {
            if (ref.get() != null) return true;
        }
        return false;
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
