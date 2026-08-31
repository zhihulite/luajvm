// java-only 门禁：bind 层四项快路径的行为等价 + 内存有界性。
package org.luajvm.test;

import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaGC;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.luajvm.bind.Coercion;

/**
 * 覆盖四条 bind 层快路径，每条都必须与关闭时行为逐位一致。
 *
 * <ol>
 *   <li>{@code luajvm.strjcache}：{@code LuaString.toJavaString()} 对纯 ASCII 短串复用
 *       {@code cachedString}（ISO-8859-1 视图）。判别点是<b>非 ASCII 必须仍走 UTF-8 解码</b>
 *       —— 若误判成 ASCII，中文和非法字节序列会被逐字节当 Latin-1 解读，静默产出错字符串。</li>
 *   <li>{@code luajvm.bindmhlazy}：{@code JavaMethod}/{@code JavaConstructor} 的
 *       spreader 惰性构造。判别点是 varargs 方法（spreader 恒为 null）必须回退
 *       {@code Method.invoke} 并保持数组参数原样。</li>
 *   <li>{@code luajvm.bindfastcall}：绑定对象直调绕过 Lua 帧。判别点是<b>用户改写
 *       {@code __call} 后必须回到完整元方法协议</b>，以及错误仍以 LuaError 正常传播、
 *       宿主栈不泄漏。</li>
 *   <li>{@code luajvm.bindargs}：实例方法参数数组直接带 receiver。判别点是静态方法、
 *       0 参、多参、varargs 四种形态都要正确。</li>
 * </ol>
 *
 * <p><b>判别力前置</b>：非 ASCII 用例先断言"期望值确实不等于 Latin-1 逐字节解读"，
 * 否则一旦构造失手，断言会恒真 —— 空转的门禁比没有门禁更危险。
 */
public final class BindFastPathTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        Globals g = Platform.standardGlobals();

        testToJavaString();
        testLongStringNotCached();
        testBindingCallSemantics(g);
        testVarargsAndStatics(g);
        testCustomCallMetamethod(g);
        testErrorPropagation(g);
        testHostStackBounded(g);
        testCacheFieldsAreInstanceScoped();
        testInnerClassLazy();
        testNoMethodHandleInCore();

        if (failures > 0) {
            System.out.println("BindFastPathTest FAILED: " + failures + " 处");
            System.exit(1);
        }
        System.out.println("BindFastPathTest: PASS");
    }

    // ---- 1. toJavaString 的字节形态覆盖 ----

    private static void testToJavaString() {
        byte[][] cases = {
                {},                                                  // 空串
                "plain".getBytes(StandardCharsets.US_ASCII),          // 纯 ASCII
                {'a', 0, 'b'},                                       // 含 NUL（Lua 串可含任意字节）
                {0x7f},                                              // ASCII 上边界
                "中文".getBytes(StandardCharsets.UTF_8),               // 合法多字节 UTF-8
                {(byte) 0x80},                                       // 非法 UTF-8 单字节
                {(byte) 0xff, (byte) 0xfe},                          // 非法 UTF-8 序列
                {'a', (byte) 0xc3, (byte) 0xa9, 'z'},                // ASCII 混多字节
        };
        for (byte[] b : cases) {
            LuaString s = LuaString.valueOfOwned(b.clone());
            String expected = new String(b, StandardCharsets.UTF_8);
            String actual = s.toJavaString();
            check(expected.equals(actual),
                    "toJavaString 必须等于 UTF-8 解码（bytes=" + hex(b)
                            + " 期望=" + esc(expected) + " 实测=" + esc(actual) + "）");
            // 判别力前置：非 ASCII 用例的 UTF-8 结果必须真的不同于 Latin-1 逐字节解读，
            //   否则"没走错分支"这个断言是空转的。
            boolean ascii = true;
            for (byte x : b)
                if (x < 0) {
                    ascii = false;
                    break;
                }
            if (!ascii) {
                String latin1 = new String(b, StandardCharsets.ISO_8859_1);
                check(!latin1.equals(expected),
                        "前置：非 ASCII 用例的 UTF-8 与 Latin-1 解读必须不同（bytes=" + hex(b) + "）");
            }
            // 重复调用必须稳定（缓存路径与首次一致）
            check(actual.equals(s.toJavaString()) && actual.equals(s.toJavaString()),
                    "重复 toJavaString 必须稳定（bytes=" + hex(b) + "）");
        }
    }

    // ---- 2. 长串不进缓存（避免大字符串内存翻倍）----

    private static void testLongStringNotCached() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('x');
        LuaString longStr = LuaString.newStr(sb.toString());
        check(longStr.toJavaString().length() == 200, "长串 toJavaString 内容正确");
        Field f = LuaString.class.getDeclaredField("cachedString");
        f.setAccessible(true);
        check(f.get(longStr) == null,
                "长串（>40B）不得填充 cachedString（否则大字符串内存翻倍）");

        // 这一条按开关分向断言：ON 时必须命中缓存，OFF 时必须不命中。
        //   两态各有判别力 —— 只写 ON 的断言会在 OFF 下误报，只写"结果正确"则
        //   无法证明 ON 真的走了缓存路径（那样断言就是空转的）。
        LuaString shortStr = LuaString.newStr("shortAscii");
        shortStr.toJavaString();
        boolean cacheOn = System.getProperty("luajvm.strjcache") == null
                || Boolean.parseBoolean(System.getProperty("luajvm.strjcache"));
        if (cacheOn) {
            check(f.get(shortStr) != null,
                    "strjcache=on：纯 ASCII 短串必须命中 cachedString 复用");
        } else {
            check(f.get(shortStr) == null,
                    "strjcache=off：必须完全不碰 cachedString（回到每次 UTF-8 解码）");
        }

        LuaString nonAscii = LuaString.valueOfOwned("短".getBytes(StandardCharsets.UTF_8));
        nonAscii.toJavaString();
        check(f.get(nonAscii) == null,
                "非 ASCII 短串不得填充 cachedString（ISO 视图与 UTF-8 视图不同）");
    }

    // ---- 3. 绑定调用语义（构造器、成员、getter/setter、重载）----

    private static void testBindingCallSemantics(Globals g) {
        g.execute("""
                local SB = luajava.bindClass('java.lang.StringBuilder')
                local sb = SB('seed')
                assert(sb:toString() == 'seed', 'ctor with arg')
                sb:append('X'):append(1):append(true)
                assert(sb:toString() == 'seedXtrue' or sb:toString() == 'seedX1true',
                       'overloaded append: ' .. sb:toString())
                local sb2 = SB()
                assert(sb2:toString() == '', '0-arg ctor')
                assert(sb2:length() == 0, '0-arg method with return')
                local AL = luajava.bindClass('java.util.ArrayList')
                local a = AL()
                a:add('p') a:add('q')
                assert(a:size() == 2, 'size')
                assert(a:get(0) == 'p' or a:get(1) == 'p', 'get')
                local F = luajava.bindClass('java.io.File')
                local file = F('parent', 'child')
                assert(type(file:getName()) == 'string', '2-arg ctor + getter')
                """);
        check(true, "构造器/重载/0 参/多参/getter 语义正常");
    }

    // ---- 4. varargs 与静态方法（bindargs 与 bindmhlazy 的判别点）----

    private static void testVarargsAndStatics(Globals g) {
        g.execute("""
                local S = luajava.bindClass('java.lang.String')
                -- 静态方法（spreader 不含 receiver）
                assert(S:valueOf(42) == '42', 'static valueOf')
                -- String.format 是 varargs：spreader 恒为 null（InvokeSupport.spreaderFor
                --   直接拒收），必须回退 Method.invoke。两种入参形态都要正确：
                --   (a) Lua 侧逐个传参，由 score/convertArgs 收成数组
                local out = S:format('%s-%s', 'a', 'b')
                assert(out == 'a-b', 'varargs static format (spread args): ' .. tostring(out))
                --   (b) Lua 侧传一个真数组，Method.invoke 必须保持它原样、不再展开
                local arr = luajava.bindClass('java.lang.Object').array({'c', 'd'})
                local out2 = S:format('%s-%s', arr)
                assert(out2 == 'c-d', 'varargs static format (array arg): ' .. tostring(out2))
                local M = luajava.bindClass('java.lang.Math')
                assert(M:max(3, 7) == 7, 'static max')
                assert(M:abs(-5) == 5, 'static abs')
                """);
        check(true, "静态方法与 varargs 方法语义正常（varargs 须走 Method.invoke 回退）");
    }

    // ---- 5. 用户改写 __call 后必须回到完整元方法协议 ----

    private static void testCustomCallMetamethod(Globals g) {
        g.execute("""
                local SB = luajava.bindClass('java.lang.StringBuilder')
                local mt = getmetatable(SB)
                assert(type(mt) == 'table', 'java userdata should expose a metatable')
                local saved = mt.__call
                assert(saved ~= nil, '前置：默认 __call 必须存在，否则本用例空转')
                local hits = 0
                mt.__call = function(self, ...) hits = hits + 1 return 'intercepted' end
                local r = SB('ignored')
                assert(r == 'intercepted', 'custom __call must win, got ' .. tostring(r))
                assert(hits == 1, 'custom __call must be invoked exactly once, got ' .. hits)
                mt.__call = saved
                local back = SB('ok')
                assert(back:toString() == 'ok', 'restoring __call must restore construction')
                """);
        check(true, "改写 __call 后直调必须回退到元方法协议，恢复后构造正常");
    }

    // ---- 6. 错误传播 ----

    private static void testErrorPropagation(Globals g) {
        boolean caught = false;
        try {
            g.execute("""
                    local AL = luajava.bindClass('java.util.ArrayList')
                    local a = AL()
                    a:get(99)
                    """);
        } catch (LuaError e) {
            caught = true;
        }
        check(caught, "绑定方法内部异常必须以 LuaError 传播（直调不得吞掉）");

        // pcall 必须能捕获（错误对象经 Lua 侧可见）
        g.execute("""
                local AL = luajava.bindClass('java.util.ArrayList')
                local a = AL()
                local ok, err = pcall(function() return a:get(99) end)
                assert(not ok, 'pcall must report failure')
                assert(err ~= nil, 'pcall must yield an error value')
                """);
        check(true, "pcall 可捕获绑定调用错误");
    }

    // ---- 7. 宿主栈有界（直调不得让 L.top / CallInfo 链增长）----

    private static void testHostStackBounded(Globals g) throws Exception {
        Object th = field(g, "running");
        if (th == null) th = field(g, "mainThread");
        check(th != null, "前置：应能取到 Lua 线程对象");
        if (th == null) return;
        g.execute("""
                SB = luajava.bindClass('java.lang.StringBuilder')
                B = SB()
                """);
        int baseTop = (Integer) field(th, "top");
        int baseNci = (Integer) field(th, "nci");
        check(baseTop >= 0 && baseNci > 0,
                "前置：基线 top/nci 应有真实值（top=" + baseTop + " nci=" + baseNci + "）");
        g.execute("for i = 1, 20000 do B:append('z') B:length() local _ = SB() end");
        int afterTop = (Integer) field(th, "top");
        int afterNci = (Integer) field(th, "nci");
        check(afterTop - baseTop <= 8,
                "2 万次绑定调用后 top 不得上涨（" + baseTop + " -> " + afterTop + "）");
        check(afterNci - baseNci <= 8,
                "2 万次绑定调用后 CallInfo 链不得增长（" + baseNci + " -> " + afterNci + "）");
        g.execute("B = nil SB = nil");
    }

    // ---- 8. 新增缓存字段的内存性质：必须挂在实例上、不得引入进程级表 ----

    /**
     * {@code toJavaString} 的缓存不得成为独立的泄漏源。
     *
     * <p>判别点是<b>结构</b>而非一次 GC 观测：只要 {@code cachedString} 与
     * {@code asciiState} 都是实例字段，缓存的生命周期就恒等于串本身，串可回收则缓存
     * 必然一起回收；反之若改成 {@code static Map<LuaString,String>} 之类，缓存就脱离了
     * 串的生命周期、成为只增不减的进程级表。
     *
     * <p>"短串驻留表在堆压力下可回收"由 {@code InternPressureTest} 专项覆盖
     * （它用 {@code -Xmx256m} 制造真实压力清软引用），此处不重复。
     */
    private static void testCacheFieldsAreInstanceScoped() throws Exception {
        Field cached = LuaString.class.getDeclaredField("cachedString");
        Field ascii = LuaString.class.getDeclaredField("asciiState");
        check(!java.lang.reflect.Modifier.isStatic(cached.getModifiers()),
                "cachedString 必须是实例字段（若变成进程级表即为泄漏源）");
        check(!java.lang.reflect.Modifier.isStatic(ascii.getModifiers()),
                "asciiState 必须是实例字段");
        check(ascii.getType() == byte.class,
                "asciiState 必须是 byte（占用 extra 之后的既有对齐 padding，不增加实例大小）");

        // LuaString 上不得因本次改动新增任何 static 的 String/Map 容器。
        //   已知合法的 static 引用类型只有驻留表 holder 与常量，这里逐个列出白名单，
        //   出现名单外的 static 容器就说明有人把缓存提成了全局表。
        java.util.List<String> unexpected = new java.util.ArrayList<>();
        for (Field f : LuaString.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            boolean container = java.util.Map.class.isAssignableFrom(t)
                    || java.util.Collection.class.isAssignableFrom(t)
                    || (t.isArray() && !t.getComponentType().isPrimitive())
                    || t == String.class;
            if (container) unexpected.add(f.getName() + ":" + t.getSimpleName());
        }
        check(unexpected.isEmpty(),
                "LuaString 不得有 static 的 String/集合/对象数组容器（实测多余项 "
                        + unexpected + "）");

        // 有界性对照：大量互异短串各调一次 toJavaString，缓存只是每串一个 String，
        //   不得出现"每次调用都新增一条全局记录"的形态。用驻留表条目数作代理指标。
        int before = internCount();
        check(before >= 0, "前置：应能读到驻留表条目数（实测 " + before + "）");
        final int n = 20_000;
        for (int i = 0; i < n; i++) {
            LuaString.newStr("bfpt_key_" + i).toJavaString();
        }
        int after = internCount();
        check(after - before <= n + 64,
                "2 万个互异短串后驻留表增量不得超过串数本身（" + before + " -> " + after + "）");
        // 同一个串重复调用 toJavaString 不得让任何计数继续增长
        LuaString repeat = LuaString.newStr("bfpt_repeat_key");
        int mid = internCount();
        for (int i = 0; i < 50_000; i++) repeat.toJavaString();
        check(internCount() == mid,
                "重复 toJavaString 不得新增任何全局条目（" + mid + " -> " + internCount() + "）");
    }

    /** 读短串驻留表条目数（嵌套 holder 类的 count 字段）。读不到返回 -1。 */
    private static int internCount() {
        try {
            for (Class<?> c : LuaString.class.getDeclaredClasses()) {
                if (!c.getSimpleName().equals("Intern")) continue;
                Field f = c.getDeclaredField("count");
                f.setAccessible(true);
                return (Integer) f.get(null);
            }
        } catch (ReflectiveOperationException ignored) {
            // 结构变化时退化为不检查（前置断言会报出来）
        }
        return -1;
    }

    // ---- 9. 内部类包装必须按需建（bindinnerlazy）----

    /**
     * 守 {@code JavaClass.getInnerClass} 的按需构建：只查一个名字不得把沿继承链的
     * **全部** public 内部类都包装成 {@code JavaClass}——Android View 子类动辄数十个
     * 内部类（{@code RecyclerView} 68 个），每个 {@code JavaClass} 构造时立即分配 9 张 Map，
     * 且按 {@code Globals} 缓存 ⇒ 每个 Activity 重建都要重付一遍。
     * 实际只有 {@code LuaLayout} 的 {@code viewClass.get("LayoutParams")} 用得上一个名字。
     *
     * <p>三组断言：<b>索引完整</b>（与测试自己沿链收集的集合逐一相等，防止惰性化漏项）、
     * <b>取值正确</b>（拿到的就是那个内部类）、<b>数量按开关分向</b>
     * （lazy 只包装被查的那一个；eager 包装全部）。
     */
    private static void testInnerClassLazy() throws Exception {
        // JTable 沿链有 4 个 public 内部类（DropLocation/PrintMode/AccessibleJComponent/
        //   BaselineResizeBehavior），足够让 lazy(1) 与 eager(4) 的数量断言分得开。
        LuaValue panelClass = Coercion.toLua(javax.swing.JTable.class);

        // 前置：沿继承链自己收集一遍 public 内部类简名（独立算法，作为期望值）
        java.util.Map<String, Class<?>> expected = new java.util.LinkedHashMap<>();
        for (Class<?> k = javax.swing.JTable.class; k != null; k = k.getSuperclass()) {
            for (Class<?> m : k.getDeclaredClasses()) {
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
                String n = m.getName();
                String stub = n.substring(Math.max(n.lastIndexOf('$'), n.lastIndexOf('.')) + 1);
                expected.putIfAbsent(stub, m);   // 先到先得，同生产实现
            }
        }
        check(expected.size() >= 3,
                "前置：JTable 沿链应有多个 public 内部类，否则数量断言空转（实测 "
                        + expected.size() + "）");
        String probeName = "DropLocation";
        check(expected.containsKey(probeName),
                "前置：期望集合应含 " + probeName + "（实测键 " + expected.keySet() + "）");

        // 取值正确
        LuaValue got = panelClass.get(LuaString.newStr(probeName));
        check(got != null && !got.isnil(), "查内部类应返回非 nil（" + probeName + "）");
        Object ud = got.touserdata();
        check(ud == expected.get(probeName),
                "拿到的必须正好是那个内部类 Class（期望 " + expected.get(probeName)
                        + "，实测 " + ud + "）");

        boolean lazy = System.getProperty("luajvm.bindinnerlazy") == null
                || Boolean.parseBoolean(System.getProperty("luajvm.bindinnerlazy"));

        // 索引完整性（仅 lazy 路径有 innerClassIndex）
        Object idx = field(panelClass, "innerClassIndex");
        if (lazy) {
            check(idx instanceof java.util.Map, "lazy：innerClassIndex 必须已建立");
            if (idx instanceof java.util.Map<?, ?> map) {
                java.util.Set<String> actual = new java.util.HashSet<>();
                for (Object k : map.keySet()) actual.add(k.toString());
                check(actual.equals(new java.util.HashSet<>(expected.keySet())),
                        "lazy：索引键集合必须与独立收集的完全一致（缺 "
                                + diff(expected.keySet(), actual) + "，多 "
                                + diff(actual, expected.keySet()) + "）");
            }
        } else {
            check(idx == null, "eager：不应建立 innerClassIndex");
        }

        // 数量按开关分向：这是"惰性真的生效"的判别点
        Object wrapped = field(panelClass, "innerClassMap");
        int n = wrapped instanceof java.util.Map<?, ?> m2 ? m2.size() : -1;
        if (lazy) {
            check(n == 1,
                    "lazy：只应为被查到的那一个名字建 JavaClass 包装（实测 " + n
                            + "，全量会是 " + expected.size() + "）");
        } else {
            check(n == expected.size(),
                    "eager：应为全部 public 内部类建包装（实测 " + n
                            + "，期望 " + expected.size() + "）");
        }

        // 重复查同一名字必须命中缓存、不新增
        LuaValue again = panelClass.get(LuaString.newStr(probeName));
        check(again == got, "重复查同一内部类名必须返回同一实例");
        Object wrapped2 = field(panelClass, "innerClassMap");
        int n2 = wrapped2 instanceof java.util.Map<?, ?> m3 ? m3.size() : -1;
        check(n2 == n, "重复查询不得新增包装（" + n + " -> " + n2 + "）");

        // 不存在的名字必须返回 nil 且不污染缓存
        LuaValue missing = panelClass.get(LuaString.newStr("NoSuchInnerClass_xyz"));
        check(missing != null && missing.isnil(), "不存在的内部类名必须返回 nil");
    }

    private static String diff(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> d = new java.util.TreeSet<>(a);
        d.removeAll(b);
        return d.toString();
    }

    // ---- 10. minSdk 守卫：core 里不得出现 MethodHandle ----

    /**
     * {@code luajvm-core} 的 class 输出里不得引用 {@code java.lang.invoke.MethodHandle}。
     *
     * <p><b>为什么是门禁而不是风格建议</b>：D8 对签名多态的
     * {@code MethodHandle.invoke/invokeExact} 要求 <b>min-api 26</b>。哪怕只有一个类、
     * 哪怕那段代码在 Android 上永不执行（{@code ContSupport} 在 ART 上 {@code SUPPORTED}
     * 恒 false），D8 也会拒绝整个 dex 化，把下游 app 的 minSdk 顶到 26。
     *
     * <p>本用例直接扫 class 二进制的常量池字节，不依赖源码文本，
     * 因此换成全限定名写法也躲不过。
     *
     * <p><b>判别力前置</b>：先断言确实扫到了足量 class 文件，否则"零引用"会空转通过。
     */
    private static void testNoMethodHandleInCore() throws Exception {
        java.io.File root = coreClassesDir();
        check(root != null && root.isDirectory(),
                "前置：应能定位 luajvm-core 的 class 输出目录（实测 " + root + "）");
        if (root == null || !root.isDirectory()) return;

        java.util.List<java.io.File> classes = new java.util.ArrayList<>();
        collectClasses(root, classes);
        check(classes.size() > 100,
                "前置：应扫到足量 class 文件，否则本用例空转（实测 " + classes.size() + "）");

        java.util.List<String> offenders = new java.util.ArrayList<>();
        for (java.io.File f : classes) {
            String hit = signaturePolymorphicCall(java.nio.file.Files.readAllBytes(f.toPath()));
            if (hit != null) {
                offenders.add(root.toPath().relativize(f.toPath()) + " -> " + hit);
            }
        }
        check(offenders.isEmpty(),
                "luajvm-core 不得有签名多态调用（D8 要求 min-api 26，会把下游 minSdk 顶到 26）。"
                        + "实测违规：" + offenders);

        // 检测器自证：BindDispatchBench 里故意留了一处 MethodHandle.invoke（从不调用），
        //   检测器必须抓到它 —— 否则上面那句"零违规"是空转的。
        java.io.File decoy = new java.io.File(
                new java.io.File(BindDispatchBench.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()),
                "org/luajvm/test/BindDispatchBench.class");
        check(decoy.isFile(), "前置：应能找到诱饵 class（实测 " + decoy + "）");
        if (decoy.isFile()) {
            String found = signaturePolymorphicCall(
                    java.nio.file.Files.readAllBytes(decoy.toPath()));
            check("java/lang/invoke/MethodHandle.invoke".equals(found),
                    "检测器自证：必须在诱饵里检出 MethodHandle.invoke（实测 " + found + "）");
        }
    }

    /**
     * 解析 class 常量池，找指向 {@code MethodHandle}/{@code VarHandle} 的
     * {@code invoke}/{@code invokeExact} 方法引用 —— 那正是 D8 要求 min-api 26 的
     * <b>签名多态</b>调用。返回第一处的 "类.方法"，没有则返回 null。
     *
     * <p>机制上必须解析常量池：字节序列 {@code java/lang/invoke/MethodHandle} 是
     * {@code MethodHandles$Lookup} 的前缀，而后者出现在**每个**用了字符串拼接或
     * lambda 的类里（{@code invokedynamic} 的 bootstrap 方法签名），按子串扫会大面积误报；
     * 故解析常量池、核对方法引用的 owner 与方法名。
     */
    private static String signaturePolymorphicCall(byte[] b) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b);
        if (bb.remaining() < 10 || bb.getInt() != 0xCAFEBABE) return null;
        bb.getShort();                       // minor
        bb.getShort();                       // major
        int cpCount = bb.getShort() & 0xFFFF;
        String[] utf8 = new String[cpCount];
        int[] classNameIdx = new int[cpCount];
        int[][] methodRef = new int[cpCount][];   // [classIndex, nameAndTypeIndex]
        int[][] nameAndType = new int[cpCount][]; // [nameIndex, descIndex]
        for (int i = 1; i < cpCount; i++) {
            int tag = bb.get() & 0xFF;
            switch (tag) {
                case 1 -> {                       // Utf8
                    int len = bb.getShort() & 0xFFFF;
                    byte[] raw = new byte[len];
                    bb.get(raw);
                    utf8[i] = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                }
                case 7, 8, 16, 19, 20 -> classNameIdx[i] = bb.getShort() & 0xFFFF;
                case 9, 10, 11, 17, 18 -> {       // *ref / Dynamic / InvokeDynamic
                    int a = bb.getShort() & 0xFFFF;
                    int c = bb.getShort() & 0xFFFF;
                    if (tag == 10 || tag == 11) methodRef[i] = new int[]{a, c};
                }
                case 12 -> {                      // NameAndType
                    int n = bb.getShort() & 0xFFFF;
                    int d = bb.getShort() & 0xFFFF;
                    nameAndType[i] = new int[]{n, d};
                }
                case 15 -> { bb.get(); bb.getShort(); }        // MethodHandle
                case 3, 4 -> bb.getInt();                      // Integer/Float
                case 5, 6 -> { bb.getLong(); i++; }            // Long/Double 占两个槽
                default -> {
                    return null;   // 未知 tag：结构超出预期，保守放过（不误报）
                }
            }
        }
        for (int i = 1; i < cpCount; i++) {
            int[] mr = methodRef[i];
            if (mr == null) continue;
            String owner = utf8Of(utf8, classNameIdx, mr[0]);
            int[] nt = mr[1] < cpCount ? nameAndType[mr[1]] : null;
            if (owner == null || nt == null) continue;
            String name = nt[0] < cpCount ? utf8[nt[0]] : null;
            if (name == null) continue;
            boolean polymorphicOwner = "java/lang/invoke/MethodHandle".equals(owner)
                    || "java/lang/invoke/VarHandle".equals(owner);
            if (polymorphicOwner && (name.equals("invoke") || name.equals("invokeExact"))) {
                return owner + "." + name;
            }
        }
        return null;
    }

    private static String utf8Of(String[] utf8, int[] classNameIdx, int classIndex) {
        if (classIndex <= 0 || classIndex >= classNameIdx.length) return null;
        int ni = classNameIdx[classIndex];
        return ni > 0 && ni < utf8.length ? utf8[ni] : null;
    }

    private static java.io.File coreClassesDir() {
        try {
            java.net.URL loc = Platform.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            java.io.File f = new java.io.File(loc.toURI());
            return f.isDirectory() ? f : null;   // 只在 class 目录形态下有效（本项目不打 JAR）
        } catch (Exception e) {
            return null;
        }
    }

    private static void collectClasses(java.io.File dir, java.util.List<java.io.File> out) {
        java.io.File[] kids = dir.listFiles();
        if (kids == null) return;
        for (java.io.File k : kids) {
            if (k.isDirectory()) collectClasses(k, out);
            else if (k.getName().endsWith(".class")) out.add(k);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ---- 工具 ----

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", b[i]));
        }
        return sb.append(']').toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7f) sb.append(c);
            else sb.append(String.format("\\u%04x", (int) c));
        }
        return sb.toString();
    }

    private static Object field(Object owner, String name) {
        for (Class<?> t = owner.getClass(); t != null; t = t.getSuperclass()) {
            try {
                Field f = t.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
                // 继续父类
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    private BindFastPathTest() {
    }
}
