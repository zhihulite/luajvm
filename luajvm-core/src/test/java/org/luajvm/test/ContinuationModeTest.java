package org.luajvm.test;

import org.luajvm.bind.Platform;
import org.luajvm.core.ContSupport;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaGC;
import org.luajvm.core.LuaValue;

import java.lang.ref.WeakReference;

/**
 * Continuation 模式专项测试（需 -Dluajvm.cont=true 且 --add-exports）。
 *
 * <p>验证：
 * <ul>
 *   <li>yield/resume 基本往返</li>
 *   <li>多层嵌套协程</li>
 *   <li>错误传播（协程体抛异常）</li>
 *   <li>GC 回收挂起态 Continuation（无线程泄漏）</li>
 *   <li>状态转换完整性</li>
 * </ul>
 */
public class ContinuationModeTest {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        if (!ContSupport.SUPPORTED) {
            System.out.println("  SKIP: Continuation 模式未启用");
            System.out.println("        需 -Dluajvm.cont=true 且 --add-exports java.base/jdk.internal.vm=ALL-UNNAMED");
            System.out.println("ContinuationModeTest: SKIP");
            return;
        }

        System.out.println("=== Continuation 模式专项测试 ===");
        System.out.println("  ContSupport.SUPPORTED = " + ContSupport.SUPPORTED);

        basicYieldResume();
        nestedCoroutines();
        errorPropagation();
        gcCollectsYieldedContinuation();
        stateTransitions();

        if (failures > 0) {
            System.out.println("ContinuationModeTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("ContinuationModeTest: PASS");
    }

    /**
     * 基本 yield/resume 往返（对齐 ContSupport 冒烟测试）。
     */
    private static void basicYieldResume() throws Exception {
        Globals g = Platform.standardGlobals();
        g.execute("_result = {}\n"
                + "local co = coroutine.create(function()\n"
                + "  table.insert(_result, 'before yield')\n"
                + "  local x = coroutine.yield(42)\n"
                + "  table.insert(_result, 'after yield: ' .. x)\n"
                + "  return 'done'\n"
                + "end)\n"
                + "local ok1, v1 = coroutine.resume(co)\n"
                + "table.insert(_result, 'first resume: ' .. tostring(v1))\n"
                + "local ok2, v2 = coroutine.resume(co, 100)\n"
                + "table.insert(_result, 'second resume: ' .. tostring(v2))\n");

        LuaValue result = g.get("_result");
        check(result.length() == 4, "基本往返：4 条记录");
        check(result.get(1).toJavaString().equals("before yield"), "记录1");
        check(result.get(2).toJavaString().equals("first resume: 42"), "记录2");
        check(result.get(3).toJavaString().equals("after yield: 100"), "记录3");
        check(result.get(4).toJavaString().equals("second resume: done"), "记录4");
    }

    /**
     * 多层嵌套：协程 A resume 协程 B，B yield，A 继续。
     */
    private static void nestedCoroutines() throws Exception {
        Globals g = Platform.standardGlobals();
        g.execute("_trace = {}\n"
                + "local inner = coroutine.create(function()\n"
                + "  table.insert(_trace, 'inner-1')\n"
                + "  coroutine.yield('inner-yield')\n"
                + "  table.insert(_trace, 'inner-2')\n"
                + "  return 'inner-done'\n"
                + "end)\n"
                + "local outer = coroutine.create(function()\n"
                + "  table.insert(_trace, 'outer-1')\n"
                + "  local ok, v = coroutine.resume(inner)\n"
                + "  table.insert(_trace, 'outer-got: ' .. v)\n"
                + "  coroutine.yield('outer-yield')\n"
                + "  table.insert(_trace, 'outer-2')\n"
                + "  local ok2, v2 = coroutine.resume(inner)\n"
                + "  table.insert(_trace, 'outer-got: ' .. v2)\n"
                + "  return 'outer-done'\n"
                + "end)\n"
                + "coroutine.resume(outer)\n"
                + "coroutine.resume(outer)\n");

        LuaValue trace = g.get("_trace");
        check(trace.length() == 6, "嵌套协程：6 条追踪");
        check(trace.get(1).toJavaString().equals("outer-1"), "trace[1]");
        check(trace.get(2).toJavaString().equals("inner-1"), "trace[2]");
        check(trace.get(3).toJavaString().equals("outer-got: inner-yield"), "trace[3]");
        check(trace.get(4).toJavaString().equals("outer-2"), "trace[4]");
        check(trace.get(5).toJavaString().equals("inner-2"), "trace[5]");
        check(trace.get(6).toJavaString().equals("outer-got: inner-done"), "trace[6]");
    }

    /**
     * 错误传播：协程体抛出 Lua 错误，resume 应返回 false + 错误对象。
     */
    private static void errorPropagation() throws Exception {
        Globals g = Platform.standardGlobals();
        g.execute("local co = coroutine.create(function()\n"
                + "  coroutine.yield(1)\n"
                + "  error('deliberate error')\n"
                + "end)\n"
                + "local ok1, v1 = coroutine.resume(co)\n"
                + "_ok1 = ok1\n"
                + "_v1 = v1\n"
                + "local ok2, v2 = coroutine.resume(co)\n"
                + "_ok2 = ok2\n"
                + "_err = tostring(v2)\n");

        check(g.get("_ok1").toboolean(), "首次 resume 成功");
        check(g.get("_v1").toint() == 1, "yield 返回 1");
        check(!g.get("_ok2").toboolean(), "第二次 resume 失败");
        check(g.get("_err").toJavaString().contains("deliberate error"), "错误消息包含 'deliberate error'");
    }

    /**
     * GC 回收挂起态 Continuation：不应泄漏任何资源（无线程、无内存滞留）。
     *
     * <p>Continuation 模式下无平台线程，泄漏形态是 cont 对象本身无法被 GC，
     * 或其引用的栈/upval 不清理。此处以弱引用是否清空判定对象已回收。
     */
    private static void gcCollectsYieldedContinuation() throws Exception {
        Globals g = Platform.standardGlobals();

        WeakReference<?>[] refs = new WeakReference[10];
        for (int i = 0; i < refs.length; i++) {
            g.execute("_coro_temp = coroutine.create(function()\n"
                    + "  local x = {}\n"
                    + "  for i = 1, 100 do x[i] = 'data' .. i end\n"
                    + "  coroutine.yield('suspended')\n"
                    + "  return 'never'\n"
                    + "end)\n"
                    + "coroutine.resume(_coro_temp)");
            Object coro = g.get("_coro_temp");
            g.execute("_coro_temp = nil");
            refs[i] = new WeakReference<>(coro);
        }

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
                "GC 应回收挂起态 Continuation（实测残留 " + aliveAfter + "/" + refs.length + "）");
    }

    /**
     * 状态转换完整性：OK -> YIELD -> OK -> dead。
     */
    private static void stateTransitions() throws Exception {
        Globals g = Platform.standardGlobals();
        g.execute("local co = coroutine.create(function()\n"
                + "  coroutine.yield()\n"
                + "  return 'finished'\n"
                + "end)\n"
                + "_status_init = coroutine.status(co)\n"
                + "coroutine.resume(co)\n"
                + "_status_yield = coroutine.status(co)\n"
                + "coroutine.resume(co)\n"
                + "_status_dead = coroutine.status(co)\n");

        check(g.get("_status_init").toJavaString().equals("suspended"), "初始：suspended");
        check(g.get("_status_yield").toJavaString().equals("suspended"), "yield 后：suspended");
        check(g.get("_status_dead").toJavaString().equals("dead"), "完成后：dead");
    }

    private static boolean anyAlive(WeakReference<?>[] refs) {
        for (WeakReference<?> ref : refs) {
            if (ref.get() != null) return true;
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            System.out.println("  FAIL FAILED: " + message);
            failures++;
        } else {
            System.out.println("  OK " + message);
        }
    }
}
