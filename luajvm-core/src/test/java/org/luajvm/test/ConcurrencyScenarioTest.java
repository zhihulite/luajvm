// java-only: 模拟 Android 使用场景的并发契约测试（JVM 可跑，无需真机）。
package org.luajvm.test;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaLib;
import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.spi.LuaJavaContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟两类 Android 真机场景，但完全在 JVM 内可跑（无 Android 依赖）：
 *
 * <ol>
 *   <li><b>回调场景</b>：{@code luajava.createProxy} 把 Lua 函数包成 Java 接口代理
 *       （对应 Android 的 OnClickListener / Runnable 等），再从多个 Java 线程并发调用。
 *       契约：同一 {@code Globals} 的执行被串行化，Lua 侧计数不丢失、状态不损坏。</li>
 *   <li><b>多 Activity 场景</b>：每个"Activity"一个独立 {@code Globals}，并发执行各自脚本。
 *       契约：真并行且状态互不串扰（全局变量、{@code package.loaded}、GC 记账）。</li>
 * </ol>
 *
 * <p>JVM 上 {@code createProxy} 需要 {@link LuaJavaContext} SPI（Android 端由
 * META-INF/services 注册）。此处注入一个等价的 JDK Proxy 实现，使回调路径与真机一致。
 */
public final class ConcurrencyScenarioTest {

    private static int failures;

    /** 包装每个场景，捕获未预期异常并记为失败，保证两个场景都会执行。 */
    static void runScenario(String name, Runnable scenario) {
        System.out.println("-- 场景：" + name + " --");
        try {
            scenario.run();
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            check(name + " 未抛出未预期异常", false);
        }
    }

    public static void main(String[] args) {
        runScenario("createProxy 多线程回调", ConcurrencyScenarioTest::testProxyCallbackFromManyThreads);
        runScenario("多 Globals 并行", ConcurrencyScenarioTest::testMultipleActivitiesInParallel);
        runScenario("多 Globals 并发分配与编译", ConcurrencyScenarioTest::testConcurrentAllocationAndCompile);
        if (failures > 0) {
            System.err.println("ConcurrencyScenarioTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("ConcurrencyScenarioTest: PASS");
    }

    // -- 场景 1：createProxy 回调，多线程并发调用 --------------------------

    /** 被代理的接口，形态对应 Android 的单方法回调（OnClickListener 等）。 */
    public interface Callback {
        int onEvent(int value);
    }

    static void testProxyCallbackFromManyThreads() {
        Globals g = Platform.standardGlobals();
        installJdkProxyContext(g);

        // Lua 侧：回调累加到全局计数器，返回累加后的值。
        // 若执行未被串行化，total 会因读改写竞争而丢更新。
        g.execute("total = 0\n"
                + "function handler(v)\n"
                + "  total = total + v\n"
                + "  return total\n"
                + "end\n"
                // createProxy 是变参：createProxy(iface1, ..., handler)；接口可用类名字符串
                + "cb = luajava.createProxy('" + Callback.class.getName() + "', handler)\n");

        Object raw = g.get("cb").touserdata();
        check("createProxy 返回 Callback 代理", raw instanceof Callback);
        if (!(raw instanceof Callback callback)) return;

        final int threads = 8;
        final int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            AtomicInteger errors = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) callback.onEvent(1);
                    } catch (Throwable e) {
                        errors.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);

            check("并发回调无异常", errors.get() == 0);
            int total = g.execute("return total").arg1().toint();
            // 串行化 => 每次调用都被计入，无丢失更新
            check("同一 Globals 的回调被串行化（total=" + total + "，期望 "
                    + (threads * perThread) + "）", total == threads * perThread);
            // 状态未损坏：Lua 侧仍可正常执行
            check("回调风暴后状态可用",
                    g.execute("return type(handler) == 'function' and #tostring(total) > 0")
                            .arg1().toboolean());
        } catch (Exception e) {
            check("并发回调场景未抛异常（" + e + "）", false);
        } finally {
            pool.shutdownNow();
        }
    }

    // -- 场景 2：多个 Globals 模拟多 Activity ----------------------------

    static void testMultipleActivitiesInParallel() {
        final int activities = 4;
        List<Globals> states = new ArrayList<>();
        for (int i = 0; i < activities; i++) states.add(Platform.standardGlobals());

        // 每个"Activity"写自己的全局与 package.loaded，并跑一轮 GC
        ExecutorService pool = Executors.newFixedThreadPool(activities);
        try {
            CountDownLatch entered = new CountDownLatch(activities);
            CountDownLatch release = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < activities; i++) {
                final int id = i;
                final Globals g = states.get(i);
                futures.add(pool.submit(() -> {
                    // 先各自进入执行区并等待，证明不同 Globals 能真并行
                    g.execute("activityId = " + id + "\n"
                            + "package.loaded['mod" + id + "'] = { id = " + id + " }\n");
                    entered.countDown();
                    release.await();
                    return g.execute(
                            "local acc = 0\n"
                            + "for k = 1, 5000 do acc = acc + k end\n"
                            + "collectgarbage()\n"
                            + "return activityId\n").arg1().toint();
                }));
            }
            check("多个 Globals 并发进入执行区", entered.await(10, TimeUnit.SECONDS));
            release.countDown();

            boolean idsOk = true;
            for (int i = 0; i < activities; i++) {
                if (futures.get(i).get(30, TimeUnit.SECONDS) != i) idsOk = false;
            }
            check("各 Activity 读回自己的 activityId", idsOk);

            // 状态隔离：全局变量与 package.loaded 互不可见
            boolean isolated = true;
            for (int i = 0; i < activities; i++) {
                Globals g = states.get(i);
                if (g.execute("return activityId").arg1().toint() != i) isolated = false;
                if (!g.execute("return package.loaded['mod" + i + "'] ~= nil").arg1().toboolean()) {
                    isolated = false;
                }
                int other = (i + 1) % activities;
                if (g.execute("return package.loaded['mod" + other + "'] ~= nil").arg1().toboolean()) {
                    isolated = false;
                }
            }
            check("多 Activity 的全局变量与 package.loaded 互不串扰", isolated);

            // 标准库表按状态独立，非共享同一对象
            check("各 Activity 的标准库表相互独立",
                    states.get(0).get("string") != states.get(1).get("string"));

            // 跨状态传入可变对象必须报错，而非静默共享
            LuaValue tableFromFirst = states.get(0).execute("return {}").arg1();
            boolean rejected = false;
            try {
                states.get(1).setEntry(LuaString.valueOf("leak"), tableFromFirst);
            } catch (LuaError expected) {
                rejected = true;
            }
            check("跨 Activity 传入 Lua 表被拒", rejected);
        } catch (Exception e) {
            check("多 Activity 场景未抛异常（" + e + "）", false);
        } finally {
            pool.shutdownNow();
        }
    }

    // -- 场景 3：多 Globals 并发分配 / 建表 / 短串驻留 / 编译 -------------

    /**
     * 覆盖场景 2 未触及的三条并发路径：
     *
     * <ol>
     *   <li><b>表分配</b>：循环建表并读回，压 GC 与每状态对象登记；</li>
     *   <li><b>短串驻留</b>：造大量互异短串，压进程级 {@code shortStrings} 驻留表
     *       （find->insert 是 check-then-act，无锁时会产生重复串或表损坏）；</li>
     *   <li><b>并发编译</b>：每轮 {@code load()} 都是新 chunk，强制真实解析 -
     *       编译器的静态出参（{@code fltToIntegerResult}）与常量折叠是并发敏感点，
     *       错乱的症状是循环变量被当成 global。脚本含浮点常量键 {@code [2.0]}，
     *       走 {@code isSCnumber} 的 float->int 折叠路径。</li>
     * </ol>
     *
     * <p>判据是差分预言机：先单线程跑一遍取参考值，再并发跑，要求每个状态的
     * 结果与参考值逐位相同。任一竞争都会让某个状态算出不同的值。
     */
    static void testConcurrentAllocationAndCompile() {
        final String script =
                "local acc = 0\n"
                + "local t = {}\n"
                + "for i = 1, 800 do t[i] = { i, ['key_' .. i] = i } end\n"
                + "for i = 1, 800 do acc = acc + t[i][1] + t[i]['key_' .. i] end\n"
                + "local strlen = 0\n"
                + "for i = 1, 800 do strlen = strlen + #('s_' .. i) end\n"
                + "local compiled = 0\n"
                + "for i = 1, 60 do\n"
                + "  local f = load('local a = {[2.0] = ' .. i .. '} return a[2] + ' .. i)\n"
                + "  compiled = compiled + f()\n"
                + "end\n"
                + "collectgarbage()\n"
                + "return acc + strlen + compiled\n";

        // 单线程参考值（同一份脚本、同一实现，唯一差别是无并发）
        final int reference = Platform.standardGlobals().execute(script).arg1().toint();

        final int activities = 4;
        List<Globals> states = new ArrayList<>();
        for (int i = 0; i < activities; i++) states.add(Platform.standardGlobals());

        ExecutorService pool = Executors.newFixedThreadPool(activities);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < activities; i++) {
                final Globals g = states.get(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return g.execute(script).arg1().toint();
                }));
            }
            start.countDown();

            boolean allMatch = true;
            StringBuilder got = new StringBuilder();
            for (int i = 0; i < activities; i++) {
                int v = futures.get(i).get(60, TimeUnit.SECONDS);
                if (i > 0) got.append(", ");
                got.append(v);
                if (v != reference) allMatch = false;
            }
            check("并发建表/短串/编译的结果与单线程参考值一致（参考 " + reference
                    + "，实测 " + got + "）", allMatch);

            // 短串驻留表未损坏：同内容短串必须仍是同一对象（raweq 对短串用身份比较）。
            //   驻留表若在并发插入下产生重复串，此断言最先失败。
            boolean internOk = true;
            for (Globals g : states) {
                if (!g.execute("local a = 'dup_' .. 1\nreturn a == 'dup_1'\n")
                        .arg1().toboolean()) {
                    internOk = false;
                }
            }
            check("并发后短串驻留身份一致", internOk);
        } catch (Exception e) {
            check("并发分配与编译场景未抛异常（" + e + "）", false);
        } finally {
            pool.shutdownNow();
        }
    }

    // -- 测试用的 LuaJavaContext（等价 Android 端 SPI 实现）--------------

    /**
     * 用 JDK Proxy 实现 {@link LuaJavaContext#createProxy}：所有接口方法分发到
     * 同一个 Lua 函数（handler 为函数时），或按方法名查表（handler 为表时）
     * - 与 Android 端实现的分发语义一致。
     */
    private static void installJdkProxyContext(Globals owner) {
        LuaJavaContext ctx = new LuaJavaContext() {
            @Override
            public ClassLoader getClassLoader() {
                return ConcurrencyScenarioTest.class.getClassLoader();
            }

            @Override
            public Object getApplication() {
                return null;
            }

            @Override
            public Object createProxy(Class<?>[] ifaces, LuaValue handler) {
                InvocationHandler ih = (proxy, method, callArgs) -> {
                    LuaValue target = handler.istable()
                            ? handler.get(method.getName())
                            : handler;
                    if (target == null || target.isnil()) return null;
                    LuaValue[] lua = new LuaValue[callArgs == null ? 0 : callArgs.length];
                    for (int i = 0; i < lua.length; i++) {
                        lua[i] = Coercion.toLua(callArgs[i]);
                    }
                    // 关键：从任意 Java 线程进入都必须经 Globals 的执行入口，
                    // 由它保证同一状态的串行化（对应 Android 主线程/后台线程回调）。
                    LuaValue result = owner.invoke((LuaFunction) target,
                            Varargs.of(lua)).arg1();
                    Class<?> ret = method.getReturnType();
                    if (ret == void.class || ret == Void.class) return null;
                    if (ret == int.class || ret == Integer.class) return result.toint();
                    if (ret == boolean.class || ret == Boolean.class) return result.toboolean();
                    if (ret == String.class) return result.isnil() ? null : result.toJavaString();
                    return null;
                };
                return Proxy.newProxyInstance(getClassLoader(), ifaces, ih);
            }

            @Override
            public Object override(Class<?> clazz, Varargs args) {
                return createProxy(new Class<?>[]{clazz}, args.arg1());
            }
        };
        // java-only: setLuaContext 是实例方法；经 JavaLib.forGlobals 绑到正确的 per-Globals 实例
        JavaLib.forGlobals(owner).setLuaContext(ctx);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  OK: " : "  FAIL: ") + name);
        if (!ok) failures++;
    }
}
