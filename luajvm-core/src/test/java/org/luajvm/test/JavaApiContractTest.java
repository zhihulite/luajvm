// java-only: Java 公共 API 合约测试（luajava 特有 Java 侧行为）
package org.luajvm.test;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.bind.JavaLib;
import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaUserdata;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.spi.LuaJavaContext;
import org.luajvm.vm.LuaPlatform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JavaApiContractTest - Java 公共 API 合约测试（luajava 特有的 Java 侧行为，
 * 无法用 Lua 文件覆盖的部分）：
 * <ul>
 *   <li>LuaUserdata.raweq：同一 Java 对象多次包装应相等（对齐 vm2 语义）</li>
 *   <li>Coercion.toLua(Map/List) -> JavaCollection：可 Lua 式索引（__index 转发 get）</li>
 *   <li>JavaCollection 写入：__newindex 转发 set（数组/List/Map 写入）</li>
 *   <li>Coercion.toLua(Java 基本类型) -> LuaValue</li>
 * </ul>
 */
public class JavaApiContractTest {

    static int failures = 0;

    public static void main(String[] args) {
        testUserdataRaweq();
        testUserdataClassParam();
        testCoercionMapIndex();
        testJavaCollectionWrite();
        testJavaMetatableGuard();
        testJavaListenerReentry();
        testJavaGetterReentry();
        testCoercionPrimitives();
        testGlobalsConcurrency();
        testGlobalsStateIsolation();
        testJavaClassCachePerState();
        testJavaClassInjectionPerState();
        testJavaPackageSurvivesGC();
        testOverrideConstructorArgsReachContext();
        // 短串驻留的可回收性由 :luajvm-core:internPressureTests 独立验证：
        //   soft 引用仅在堆压力下清除，须以小堆跑到 OOM 才能判定，
        //   在本进程内制造那种压力会污染其余断言。
        if (failures > 0) {
            System.err.println("JavaApiContractTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("JavaApiContractTest: PASS");
    }

    // LuaUserdata.raweq 按 Java 对象比较（同一对象多包装相等，对齐 vm2 LuaUserdata.raweq）
    static void testUserdataRaweq() {
        Object o = new Object();
        LuaUserdata u1 = new LuaUserdata(o);
        LuaUserdata u2 = new LuaUserdata(o);
        check("raweq same java object", u1.raweq(u2));
        check("raweq same instance", u1.raweq(u1));
        LuaUserdata u3 = new LuaUserdata(new Object());
        check("raweq different object", !u1.raweq(u3));
    }

    // isuserdata(Class)/touserdata(Class)/optuserdata(Class,d) 三件套同判据：
    //   桩实现恒 false 会让 isuserdata(Class) 与另两个互相矛盾
    //   （loadlayout 构造器缓存、src 的 Drawable 分支、typeface、getter 的
    //   CharSequence 转字符串全部依赖它）。
    static void testUserdataClassParam() {
        StringBuilder sb = new StringBuilder("abc");
        LuaUserdata u = new LuaUserdata(sb);
        check("isuserdata(Class) positive", u.isuserdata(CharSequence.class));
        check("isuserdata(Class) negative", !u.isuserdata(Number.class));
        check("touserdata(Class) positive", u.touserdata(CharSequence.class) == sb);
        check("touserdata(Class) negative", u.touserdata(Number.class) == null);
        check("optuserdata(Class,d) positive", u.optuserdata(CharSequence.class, "d") == sb);
        check("optuserdata(Class,d) negative", "d".equals(u.optuserdata(Number.class, "d")));
        // 非 userdata 值走基类桩，恒 false
        check("non-userdata isuserdata(Class)",
                !LuaValue.valueOf("s").isuserdata(CharSequence.class)
                        && !LuaValue.NIL.isuserdata(CharSequence.class));
    }

    // Coercion.toLua(Map) -> JavaCollection，Lua 式索引经 get()
    static void testCoercionMapIndex() {
        Map<String, String> map = new HashMap<>();
        map.put("key", "value");
        LuaValue lv = Coercion.toLua(map);
        check("map toLua is userdata", lv instanceof LuaUserdata);
        check("map index via get", "value".equals(lv.get("key").toJavaString()));
    }

    // JavaCollection __newindex：Lua 式写入到达 set()（数组/List/Map）
    static void testJavaCollectionWrite() {
        // Map 写入
        Map<String, String> map = new HashMap<>();
        LuaValue lv = Coercion.toLua(map);
        lv.set("k", LuaValue.valueOf("v"));
        check("map set via __newindex path", "v".equals(map.get("k")));
        // List 写入
        List<String> list = new ArrayList<>();
        list.add("a");
        LuaValue lv2 = Coercion.toLua(list);
        lv2.set(LuaValue.valueOf(1), LuaValue.valueOf("b"));
        check("list set via __newindex path", "b".equals(list.get(0)));
    }

    // Coercion.toLua 基本类型
    static void testCoercionPrimitives() {
        check("int toLua", Coercion.toLua(42).toint() == 42);
        check("boolean toLua", Coercion.toLua(true).toboolean());
        check("string toLua", "x".equals(Coercion.toLua("x").toJavaString()));
        check("null toLua", Coercion.toLua((Object) null).isnil());
    }

    // 同一 Globals 的外部调用必须串行，不同 Globals 必须保持状态隔离。
    static void testGlobalsConcurrency() {
        Globals first = LuaPlatform.standardGlobals();
        Globals second = LuaPlatform.standardGlobals();
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            ArrayList<Future<VarargsValue>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(callers.submit(() -> new VarargsValue(
                        first.execute("counter = (counter or 0) + 1; return counter").arg1().toint())));
            }
            int last = 0;
            for (Future<VarargsValue> future : futures) last = Math.max(last, future.get().value);
            check("same Globals calls are serialized", last == 16);
            check("different Globals are isolated",
                    first.execute("return counter").arg1().toint() == 16
                            && second.execute("return counter or 0").arg1().toint() == 0);

            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            LuaFunction blockFirst = blockingFunction(entered, release);
            LuaFunction blockSecond = blockingFunction(entered, release);
            Future<?> firstRunning = callers.submit(() -> first.invoke(blockFirst, LuaValue.NONE));
            Future<?> secondRunning = callers.submit(() -> second.invoke(blockSecond, LuaValue.NONE));
            check("different Globals execute in parallel", entered.await(2, TimeUnit.SECONDS));
            release.countDown();
            firstRunning.get();
            secondRunning.get();

            LuaFunction callback = (LuaFunction) first.compiler.compile(
                    new ByteArrayInputStream("callback = (callback or 0) + 1; return callback"
                            .getBytes(StandardCharsets.ISO_8859_1)),
                    "callback", "bt", first);
            ArrayList<Future<VarargsValue>> callbackFutures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                callbackFutures.add(callers.submit(() -> new VarargsValue(
                        first.invoke(callback, LuaValue.NONE).arg1().toint())));
            }
            int callbackLast = 0;
            for (Future<VarargsValue> future : callbackFutures)
                callbackLast = Math.max(callbackLast, future.get().value);
            check("off-thread Lua Function invocation", callbackLast == 8);

            AtomicBoolean currentThreadInvocation = new AtomicBoolean();
            Thread callerThread = Thread.currentThread();
            LuaFunction currentThreadCallback = new LuaFunction() {
                @Override
                public Varargs call(Varargs args) {
                    currentThreadInvocation.set(first.isExecutingOnCurrentThread()
                            && Thread.currentThread() == callerThread);
                    return LuaValue.NONE;
                }
            };
            first.invoke(currentThreadCallback, LuaValue.NONE);
            check("Lua invocation stays on caller thread", currentThreadInvocation.get());
        } catch (Exception e) {
            check("Globals concurrency", false);
        } finally {
            callers.shutdownNow();
        }
    }

    private record VarargsValue(int value) {
    }

    private static LuaFunction blockingFunction(CountDownLatch entered, CountDownLatch release) {
        return new LuaFunction() {
            @Override
            public Varargs call(Varargs args) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("parallel Globals did not release");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                return LuaValue.NONE;
            }
        };
    }

    /**
     * 多状态隔离门禁：两个 Globals 的 GC、字符串、require 与基础类型元表互不串扰。
     * 对应 C 的 global_State 边界：mt[]、strt、allgc、package.loaded 都是按状态的。
     */
    static void testGlobalsStateIsolation() {
        Globals a = LuaPlatform.standardGlobals();
        Globals b = LuaPlatform.standardGlobals();

        // 1) 标准库表是各自的实例，非共享对象
        check("stdlib tables are per state",
                a.get("string") != b.get("string") && a.get("table") != b.get("table"));

        // 2) 基础类型元表按状态：C 的 G(L)->mt[LUA_TSTRING]
        LuaValue mtA = a.typeMetatable(LuaValue.TSTRING);
        LuaValue mtB = b.typeMetatable(LuaValue.TSTRING);
        check("string type metatable is per state",
                mtA != null && mtB != null && mtA != mtB);
        // 元表的 __index 必须指向本状态的 string 表，否则 s:upper() 会解析到别的状态
        check("string metatable __index points to own string table",
                mtA.rawget(LuaValue.INDEX) == a.get("string")
                        && mtB.rawget(LuaValue.INDEX) == b.get("string"));

        // 3) 方法调用走各自的元表链
        check("string methods resolve within own state",
                "AB".equals(a.execute("return ('ab'):upper()").arg1().toJavaString())
                        && "AB".equals(b.execute("return ('ab'):upper()").arg1().toJavaString()));

        // 4) require 的 package.loaded 按状态隔离
        a.execute("mymod = {}; package.loaded['mymod'] = mymod");
        check("package.loaded is per state",
                a.execute("return package.loaded['mymod'] ~= nil").arg1().toboolean()
                        && !b.execute("return package.loaded['mymod'] ~= nil").arg1().toboolean());

        // 5) GC 与内存记账按状态：仅在 a 中造垃圾，b 的收集不受影响
        a.execute("local t = {} for i = 1, 2000 do t[i] = ('x'):rep(200) end return #t");
        long aBefore = a.execute("return collectgarbage('count')").arg1().tolong();
        long bBefore = b.execute("return collectgarbage('count')").arg1().tolong();
        b.execute("collectgarbage('collect')");
        long aAfterBCollect = a.execute("return collectgarbage('count')").arg1().tolong();
        check("collecting one state does not shrink the other",
                aAfterBCollect >= aBefore - 1);
        check("per-state memory accounting is independent", aBefore != bBefore);

        // 6) 长串登记按状态：a 造长串后仅 a 的用量上升
        long bBaseline = b.execute("return collectgarbage('count')").arg1().tolong();
        a.execute("longstr = ('y'):rep(300000)");
        long bAfter = b.execute("return collectgarbage('count')").arg1().tolong();
        check("long strings register to the creating state", bAfter <= bBaseline + 1);

        // 6b) ltests 的对象计数按 C 的 l_memcontrol.objcount[] 语义为进程级：
        //     在 b 中建表后，从任一状态查询 table 计数都应上升（跨状态求和）。
        //     字符串计数本就是进程级，其余四类若按状态就会与它口径不齐。
        LtestsDebugLib.open(a);
        long tablesBefore = a.execute("return (T.totalmem('table'))").arg1().tolong();
        b.execute("bkeep = {} for i = 1, 50 do bkeep[i] = {} end");
        long tablesAfter = a.execute("return (T.totalmem('table'))").arg1().tolong();
        check("ltests object counts are process-wide (C: l_memcontrol.objcount)",
                tablesAfter >= tablesBefore + 50);

        // 7) 跨状态传入可变对象立即报错（不静默共享）
        LuaValue tableFromA = a.execute("return {}").arg1();
        boolean rejected = false;
        try {
            b.setEntry(LuaString.newStr("leak"), tableFromA);
        } catch (LuaError expected) {
            rejected = true;
        }
        check("cross-state mutable object is rejected", rejected);
    }

    /**
     * {@code JavaClass} 缓存必须按状态存放（{@code Globals.javaClassCache}）。
     *
     * <p>{@code JavaClass} 是 {@code LuaUserdata} 且携带 {@code ownerGlobals}，若缓存是
     * 进程级 static，第二个 {@code Globals} 会拿到第一个状态已绑定的对象而抛
     * "belongs to another Globals"。
     *
     * <p>本用例必须显式走 {@code luajava.bindClass}：其余断言路径不经
     * {@code JavaClass.forClass}，若不加这一条，本门禁不覆盖该缓存的所有权修复。
     */
    static void testJavaClassCachePerState() {
        // 建状态本身就必须包 try/catch：bind.Platform.standardGlobals() 会装 JavaLib，
        //   过程中即经 JavaClass.forClass 缓存若干类。进程级缓存下第二个状态在
        //   [构造阶段]就抛 "belongs to another Globals" —— 若不捕获则是未捕获异常终止
        //   进程，退出码虽仍非 0，但拿不到"哪条断言红"的诊断。
        String script = "local C = luajava.bindClass('java.lang.System')\n"
                + "return C:currentTimeMillis() > 0";

        Globals a = null;
        String errA = null;
        boolean okA = false;
        try {
            a = Platform.standardGlobals();
            okA = a.execute(script).arg1().toboolean();
        } catch (LuaError e) {
            errA = e.getMessage();
        }
        check("bindClass works in a fresh state"
                + (errA == null ? "" : "; got: " + errA), okA);

        Globals b = null;
        String errB = null;
        boolean okB = false;
        try {
            b = Platform.standardGlobals();
            okB = b.execute(script).arg1().toboolean();
        } catch (LuaError e) {
            errB = e.getMessage();
        }
        check("JavaClass cache is per state (a second state must not inherit a bound JavaClass)"
                + (errB == null ? "" : "; got: " + errB), okB);

        // 实例侧同样经 JavaClass 取构造器
        String errC = null;
        boolean instOk = false;
        try {
            if (b == null) b = Platform.standardGlobals();
            instOk = b.execute("local sb = luajava.newInstance('java.lang.StringBuilder')\n"
                    + "sb:append('x') return sb:toString() == 'x'").arg1().toboolean();
        } catch (LuaError e) {
            errC = e.getMessage();
        }
        check("newInstance works in the second state"
                + (errC == null ? "" : "; got: " + errC), instOk);
    }

    /**
     * Java 默认元表直通只能在元表未改写时生效；实例级和类级自定义元表必须仍由 Lua
     * 元方法处理，避免 VM 快路径吞掉用户的 __index/__newindex。
     */
    static void testJavaMetatableGuard() {
        Globals g = Platform.standardGlobals();
        String script = "local C = luajava.bindClass('java.lang.StringBuilder')\n"
                // 类级元表必须在实例第一次进入 VM 前就能阻止直通快路径。
                + "local classSeen = false\n"
                + "debug.setmetatable(C, {\n"
                + "  __index = function(_, key)\n"
                + "    if key == 'class-custom' then return 'class-index' end\n"
                + "  end,\n"
                + "  __newindex = function(_, key, value)\n"
                + "    if key == 'class-written' and value == 9 then classSeen = true end\n"
                + "  end\n"
                + "})\n"
                + "local classFresh = luajava.newInstance('java.lang.StringBuilder')\n"
                + "assert(classFresh['class-custom'] == 'class-index')\n"
                + "classFresh['class-written'] = 9\n"
                + "assert(classSeen)\n"
                + "debug.setmetatable(C, nil)\n"
                + "local obj = luajava.newInstance('java.lang.StringBuilder')\n"
                + "local seen = false\n"
                + "debug.setmetatable(obj, {\n"
                + "  __index = function(_, key)\n"
                + "    if key == 'custom' then return 'instance-index' end\n"
                + "  end,\n"
                + "  __newindex = function(_, key, value)\n"
                + "    if key == 'written' and value == 7 then seen = true end\n"
                + "  end\n"
                + "})\n"
                + "assert(obj.custom == 'instance-index')\n"
                + "obj.written = 7\n"
                + "assert(seen)\n"
                + "return true";
        boolean ok = false;
        String error = null;
        try {
            ok = g.execute(script).arg1().toboolean();
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        check("自定义 Java 实例/类元表不被直通快路径绕过"
                + (error == null ? "" : "; got: " + error), ok);
    }

    /**
     * 默认 __newindex 直通调用 setter 时，setter 触发的 Lua 代理回调必须仍能看到
     * 当前 CallInfo。该用例覆盖 Android 控件常用的 onXxxListener 赋值形态。
     */
    static void testJavaListenerReentry() {
        Globals g = Platform.standardGlobals();
        JavaLib.forGlobals(g).setLuaContext(new LuaJavaContext() {
            @Override
            public ClassLoader getClassLoader() {
                return JavaApiContractTest.class.getClassLoader();
            }

            @Override
            public Object getApplication() {
                return null;
            }

            @Override
            public Object createProxy(Class<?>[] ifaces, LuaValue handler) {
                return Proxy.newProxyInstance(getClassLoader(), ifaces, (proxy, method, args) -> {
                    LuaValue callback = handler.istable() ? handler.get(method.getName()) : handler;
                    if (!callback.isnil()) g.invoke((LuaFunction) callback, LuaValue.NONE);
                    return null;
                });
            }

            @Override
            public Object override(Class<?> clazz, Varargs args) {
                return null;
            }
        });
        ListenerBean bean = new ListenerBean();
        g.set("listenerBean", Coercion.toLua(bean));
        boolean ok = false;
        String error = null;
        try {
            ok = g.execute("local function grow(n)\n"
                    + "  if n == 0 then return {} end\n"
                    + "  return { grow(n - 1) }\n"
                    + "end\n"
                    + "local count = 0\n"
                    + "listenerBean.onChange = function() grow(300); count = count + 1 end\n"
                    + "return count == 1").arg1().toboolean();
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        check("Java setter 触发 Lua 监听器回调时 VM 状态保持有效"
                + (error == null ? "" : "; got: " + error), ok && bean.listener != null);
    }

    /** Java getter 重入并扩容 Lua 栈后，结果必须写回当前栈数组。 */
    static void testJavaGetterReentry() {
        Globals g = Platform.standardGlobals();
        GetterBean bean = new GetterBean();
        g.set("getterBean", Coercion.toLua(bean));
        boolean ok = false;
        String error = null;
        try {
            ok = g.execute("local function grow(n)\n"
                    + "  if n == 0 then return {} end\n"
                    + "  return { grow(n - 1) }\n"
                    + "end\n"
                    + "getterBean.callback = function() grow(300) end\n"
                    + "return getterBean.value == 'ok'").arg1().toboolean();
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        check("Java getter 重入并扩容栈后仍返回结果"
                + (error == null ? "" : "; got: " + error), ok);
    }

    /** 测试用 Java 监听器，模拟 Android 的 setOnXxxListener 协议。 */
    public interface ChangeListener {
        void onChange();
    }

    /** setter 在返回前立即回调，专门覆盖 VM 直通路径的重入边界。 */
    public static final class ListenerBean {
        ChangeListener listener;

        public void setOnChangeListener(ChangeListener listener) {
            this.listener = listener;
            if (listener != null) listener.onChange();
        }
    }

    /** getter 在返回前重入 Lua，模拟 Android 属性回调导致的栈扩容。 */
    public static final class GetterBean {
        public LuaFunction callback;

        public String getValue() {
            if (callback != null) LuaCall.callLua(callback, LuaValue.NONE);
            return "ok";
        }
    }

    /**
     * Java 宿主把 {@code Class} 写入某个 Globals 时，包装对象必须归属于目标状态。
     *
     * <p>两个状态都空闲时，通用 {@code Coercion.toLua(Class)} 只能从活动状态表猜测，
     * 会把第二次注入也包装到第一个 Globals；目标表的所有权检查随后拒绝该 userdata。
     * Android 的第二个 Activity 因 {@code JavaCall.set} 静默吞掉异常而只看到全局为 nil。
     */
    static void testJavaClassInjectionPerState() {
        Globals a = Platform.standardGlobals();
        Globals b = Platform.standardGlobals();
        JavaCall.set(a, "InjectedSystem", System.class);
        JavaCall.set(b, "InjectedSystem", System.class);

        boolean aOk = !a.get("InjectedSystem").isnil()
                && a.execute("return InjectedSystem:currentTimeMillis() > 0").arg1().toboolean();
        boolean bOk = !b.get("InjectedSystem").isnil()
                && b.execute("return InjectedSystem:currentTimeMillis() > 0").arg1().toboolean();
        check("JavaCall class injection belongs to the first target state", aOk);
        check("JavaCall class injection belongs to the second target state", bOk);
    }

    /**
     * {@link JavaLib.Package} 借用 userdata 类型标签提供 Lua 侧包访问，但不带
     * {@code BIT_ISCOLLECTABLE}，不属于 Lua GC 对象链。完整 GC 必须跳过它，不能按
     * {@code TUSERDATA} 低位把它压进灰链后强转为 {@link LuaUserdata}。
     */
    static void testJavaPackageSurvivesGC() {
        Globals g = Platform.standardGlobals();
        JavaLib.Package packageProbe = new JavaLib.Package(g, "java");
        g.set("packageProbe", packageProbe);
        String error = null;
        boolean ok = false;
        try {
            g.execute("collectgarbage('collect')\ncollectgarbage('collect')");
            LuaValue resolved = packageProbe.get("lang").get("String");
            ok = g.get("packageProbe") == packageProbe
                    && resolved instanceof LuaUserdata
                    && resolved.checkuserdata(Class.class) == String.class;
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        check("non-collectable Java package survives full GC"
                + (error == null ? "" : "; got: " + error), ok);
    }

    /** luajava.override 的方法表之后的参数必须原样穿过 core SPI 边界。 */
    static void testOverrideConstructorArgsReachContext() {
        Globals g = Platform.standardGlobals();
        boolean[] observed = {false};
        JavaLib.forGlobals(g).setLuaContext(new LuaJavaContext() {
            @Override
            public ClassLoader getClassLoader() {
                return JavaApiContractTest.class.getClassLoader();
            }

            @Override
            public Object getApplication() {
                return null;
            }

            @Override
            public Object createProxy(Class<?>[] ifaces, LuaValue handler) {
                return new Object();
            }

            @Override
            public Object override(Class<?> clazz, Varargs args) {
                observed[0] = clazz == Object.class
                        && args.arg1().istable()
                        && args.narg() == 3
                        && !args.arg(2).toboolean()
                        && args.arg(3).checkint() == 37;
                return new Object();
            }
        });
        LuaTable methods = new LuaTable();
        JavaLib.forGlobals(g).override(Object.class,
                Varargs.of(new LuaValue[]{methods, LuaValue.FALSE, LuaValue.valueOf(37)}));
        check("override constructor args reach LuaJavaContext unchanged", observed[0]);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  OK: " : "  FAIL: ") + name);
        if (!ok) failures++;
    }
}
