// java-only 基准：量化 Java 宿主 -> Java 绑定调用的分派成本。
//   背景：luajvm:android loadlayout 较慢。
//   嫌疑是宿主侧的 Java->Java 调用被套进完整 Lua 调用协议（ccall/precall/__call/
//   prepCallInfo/Varargs 打包/poscall），而对比基线是 LuaValue 虚方法直调。
//   本基准在 JVM 上隔离测量每条路径的单次成本，用于证明收益上界（"先证收益，再写生产实现"）。
//
// 用法（每个用例必须独立进程，避免 JIT 互相污染）：
//   java -cp <test+core> org.luajvm.test.BindDispatchBench <case> [iters]
package org.luajvm.test;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.bind.JavaObject;
import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

public final class BindDispatchBench {

    /** 模仿 Android View 的构造 + setter 形态（1 参构造器、若干 setter/getter）。 */
    public static final class BenchView {
        public final Object context;
        private String text = "";
        private int minimumWidth;
        private boolean clickable;

        public BenchView(Object context) {
            this.context = context;
        }

        public void setText(String t) {
            this.text = t;
        }

        public String getText() {
            return text;
        }

        public void setMinimumWidth(int w) {
            this.minimumWidth = w;
        }

        public int getMinimumWidth() {
            return minimumWidth;
        }

        public void setClickable(boolean c) {
            this.clickable = c;
        }

        public boolean isClickable() {
            return clickable;
        }

        public void addView(Object child) {
            // no-op：只测分派成本，不测 Android 布局
        }
    }

    // 防 DCE 的累加器
    private static long sink;

    private static String caseName = "";
    private static int iters;
    private static long elapsedNanos;

    public static void main(String[] args) {
        caseName = args.length > 0 ? args[0] : "all";
        iters = args.length > 1 ? Integer.parseInt(args[1]) : 200_000;

        // 这几个用例自己建 Globals（测的就是 Globals 级 JavaClass 缓存重建成本），
        //   不能跑在别的 Globals 的执行区里。
        if (caseName.startsWith("jclass_") || caseName.equals("globals_only")
                || caseName.startsWith("settings_")) {
            runGlobalsScopeCase();
        } else if (caseName.equals("perglobals")) {
            runPerGlobalsProbe();
            return;
        } else {
            Globals g = Platform.standardGlobals();
            // loadlayout 是从 Lua 调进来的，此时 Globals.running != null，LuaCall.* 走完整 Lua 帧。
            //   若在 Lua 执行区外跑，LuaCall.callLua 的 L == null 分支已经是直调，测不到真实路径。
            g.set("runbench", new LuaFunction() {
                @Override
                public Varargs call(Varargs a) {
                    runInsideLua(g);
                    return LuaValue.NONE;
                }
            });
            g.execute("runbench()");
        }

        System.out.printf("%s iters=%d total=%.3fms per_op=%.1fns sink=%d alloc=%s%n",
                caseName, iters, elapsedNanos / 1e6, (double) elapsedNanos / iters, sink,
                allocPerOp());
    }

    /**
     * 每次操作的分配字节数（`com.sun.management` 不可用时返回 "n/a"）。
     *
     * <p>ART 上分配压力会显性影响 wall-time（与 JVM 分代 GC 下"短命对象近乎免费"不同），
     * 所以这个读数在判 Android 收益时和 wall-time 同等重要。
     */
    private static String allocPerOp() {
        long now = allocatedBytes();
        if (now < 0 || allocAtStart < 0) return "n/a";
        return String.format("%.1fB/op", (double) (now - allocAtStart) / Math.max(iters, 1));
    }

    private static long allocAtStart;

    private static void markAllocStart() {
        allocAtStart = allocatedBytes();
    }

    /** 当前线程累计分配字节；`com.sun.management` 缺失（如 ART）时返回 -1。 */
    private static long allocatedBytes() {
        try {
            var bean = java.lang.management.ManagementFactory.getThreadMXBean();
            Class<?> sun = Class.forName("com.sun.management.ThreadMXBean");
            if (!sun.isInstance(bean)) return -1;
            return (long) sun.getMethod("getCurrentThreadAllocatedBytes").invoke(bean);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Globals 级 JavaClass 缓存重建成本（重建的完整构成与读法见
     * {@link #runPerGlobalsProbe}）。
     */
    private static void runGlobalsScopeCase() {
        // 深继承 + 数百方法/字段，规模接近 Android View 子类
        String script = switch (caseName) {
            case "globals_only" -> "";
            case "settings_like", "settings_like_warm", "settings_like_cold" -> SETTINGS_LIKE_SCRIPT;
            default -> """
                    local P = luajava.bindClass('javax.swing.JPanel')
                    local L = luajava.bindClass('javax.swing.JLabel')
                    local B = luajava.bindClass('javax.swing.JButton')
                    local p = luajava.newInstance('javax.swing.JPanel')
                    local l = luajava.newInstance('javax.swing.JLabel')
                    local b = luajava.newInstance('javax.swing.JButton')
                    p.name = 'a'   l.name = 'b'   b.name = 'c'
                    l.text = 'x'   b.text = 'y'
                    p.opaque = true
                    local _ = p.name .. l.text .. b.text
                    """;
        };
        // *_cold 用例不预热：真实场景是"启动 App 后第一次打开设置页"，而 JavaMethod/
        //   JavaConstructor/SHARED_METHOD_INDEX 都是**进程级**缓存 => 反射与
        //   MethodHandle 的构造成本只在首轮发生，预热后的稳态测不到它。
        int warmup = caseName.endsWith("_cold") ? 0 : Math.max(iters / 5, 2);
        if (caseName.equals("jclass_reuse") || caseName.equals("settings_like_warm")) {
            Globals g = Platform.standardGlobals();
            for (int i = 0; i < warmup; i++) g.execute(script);
            long t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) g.execute(script);
            elapsedNanos = System.nanoTime() - t0;
            sink += g.hashCode();
            return;
        }
        for (int i = 0; i < warmup; i++) {
            Globals g = Platform.standardGlobals();
            if (!script.isEmpty()) g.execute(script);
            sink += g.hashCode();
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            Globals g = Platform.standardGlobals();
            if (!script.isEmpty()) g.execute(script);
            sink += g.hashCode();
        }
        elapsedNanos = System.nanoTime() - t0;
    }

    /**
     * 判别性探针：分离"JIT 冷"与"Globals 级 JavaClass 缓存重建"两种成本。
     *
     * <p>luajvm 的 {@code JavaClass} 按 {@code Globals} 缓存（{@code Globals.javaClassCache}），
     * 对比基线是进程级 {@code static}（{@code JavaClass.classes}）。Android 每个 Activity
     * 建一个 Globals ⇒ luajvm 每次打开页面都要重建 {@code fieldMap}（{@code getFields()}
     * 继承链全扫）、{@code innerClassMap}（沿继承链逐层 {@code getDeclaredClasses()}，
     * 并为每个 public 内部类新建一个 JavaClass）、{@code methodMap}、
     * {@code cachedGetters/Setters}、三张 accessTypeCache。
     * {@code LuaLayout.load} 的 {@code viewClass.get("LayoutParams")} 让每个容器
     * View 类都必然触发内部类扫描。
     *
     * <p>读法：<b>t_newG − t_sameG</b> 就是这项重建的成本（此时 JIT 已热、
     * {@code ExecutableBinding.EXECUTABLES} 与 {@code SHARED_METHOD_INDEX} 进程级缓存已命中），
     * 即"把这些元数据提到进程级共享"的收益上界。
     */
    private static void runPerGlobalsProbe() {
        Globals g1 = Platform.standardGlobals();
        long tCold = timeTreeIn(g1, 1);
        timeTreeIn(g1, 300);                 // 预热 JIT
        long tSame = timeTreeIn(g1, 1);
        long tNewG2 = timeTreeIn(Platform.standardGlobals(), 1);
        long tNewG3 = timeTreeIn(Platform.standardGlobals(), 1);
        long tNewG4 = timeTreeIn(Platform.standardGlobals(), 1);
        System.out.printf("perglobals cold=%.3fms sameG=%.3fms newG=[%.3f, %.3f, %.3f]ms "
                        + "rebuild=%.3fms(%.1fx) sink=%d%n",
                tCold / 1e6, tSame / 1e6,
                tNewG2 / 1e6, tNewG3 / 1e6, tNewG4 / 1e6,
                (tNewG3 - tSame) / 1e6, (double) tNewG3 / tSame, sink);
    }

    /** 在 g 的 Lua 执行区内建 rounds 棵树，返回耗时（含该 Globals 的 JavaClass 建立）。 */
    private static long timeTreeIn(Globals g, int rounds) {
        final long[] out = new long[1];
        g.set("probe", new LuaFunction() {
            @Override
            public Varargs call(Varargs a) {
                long t = System.nanoTime();
                // initLayoutTree 里的 Coercion.toLua(Class) 会为当前 Globals 建 JavaClass，
                //   本身就是"重建"的一部分，必须计入。
                initLayoutTree();
                for (int i = 0; i < rounds; i++) sink += buildLayoutTree();
                out[0] = System.nanoTime() - t;
                return LuaValue.NONE;
            }
        });
        g.execute("probe()");
        return out[0];
    }

    /**
     * 端到端负载：模拟 Android 设置页 loadlayout 的形态。
     *
     * <p>13 个 item，每 item 一个容器 + 3 个子控件，逐个走"bindClass -> 构造 ->
     * 遍历表设属性 -> 加子控件"，与 {@code LuaLayout.load} 的结构一致
     * （构造走 JavaClass 的 __call、属性走 JavaObject.set 的 setter 分派、
     * 加子控件走 JavaObject 成员方法）。用 swing 组件替代 Android View：
     * 深继承 + 325-395 个方法 + 18-56 个字段，反射规模同量级。
     *
     * <p>{@code settings_like} 每轮新建 Globals（对应"每次打开设置页新建 Activity"）；
     * {@code settings_like_warm} 复用 Globals（对应同进程二次打开）。
     */
    private static final String SETTINGS_LIKE_SCRIPT = """
            local ITEM = {
              'javax.swing.JPanel', name='card', opaque=true, toolTipText='card',
              visible=true, enabled=true,
              { 'javax.swing.JLabel', name='title', text='Title', toolTipText='t',
                enabled=true, visible=true },
              { 'javax.swing.JLabel', name='summary', text='Summary', toolTipText='s',
                enabled=true, visible=true },
              { 'javax.swing.JButton', name='action', text='Go', toolTipText='g',
                enabled=true, visible=true },
            }
            local function build(spec)
              local cls = luajava.bindClass(spec[1])
              local v = cls()
              for k, val in pairs(spec) do
                if type(k) == 'string' then v[k] = val end
              end
              for i = 2, #spec do
                v:add(build(spec[i]))
              end
              return v
            end
            local root = luajava.newInstance('javax.swing.JPanel')
            for i = 1, 13 do root:add(build(ITEM)) end
            """;


    private static void runInsideLua(Globals g) {
        LuaValue viewClass = Coercion.toLua(BenchView.class);
        LuaValue ctxLua = Coercion.toLua(new Object());
        LuaValue newKey = LuaString.newStr("new");
        LuaValue textKey = LuaString.newStr("text");
        LuaValue widthKey = LuaString.newStr("minimumWidth");
        LuaValue textVal = LuaString.newStr("hello");
        LuaValue widthVal = LuaValue.valueOf(42);
        JavaObject view = (JavaObject) LuaCall.call(viewClass, ctxLua);
        LuaValue ctorDirect = viewClass.get(newKey);
        LuaValue addView = view.getJavaMethod("addView");
        LuaValue setText = view.getJavaMethod("setText");
        LuaValue getText = view.getJavaMethod("getText");
        LuaValue keyStr = LuaString.newStr("setMinimumWidth");
        if (caseName.startsWith("layout_host")) initLayoutTree();

        int warmup = caseName.endsWith("_cold") ? 0 : Math.max(iters / 10, 20_000);
        run(caseName, warmup, viewClass, ctxLua, newKey, textKey, widthKey,
                textVal, widthVal, view, ctorDirect, addView, setText, getText, keyStr);
        markAllocStart();
        long t0 = System.nanoTime();
        run(caseName, iters, viewClass, ctxLua, newKey, textKey, widthKey,
                textVal, widthVal, view, ctorDirect, addView, setText, getText, keyStr);
        elapsedNanos = System.nanoTime() - t0;
    }

    private static void run(String c, int n, LuaValue viewClass, LuaValue ctxLua,
                            LuaValue newKey, LuaValue textKey, LuaValue widthKey,
                            LuaValue textVal, LuaValue widthVal, JavaObject view,
                            LuaValue ctorDirect, LuaValue addView, LuaValue setText,
                            LuaValue getText, LuaValue keyStr) {
        switch (c) {
            // ---- View 构造：三层剥离 ----
            // A1：当前 LuaLayout 路径 —— LuaCall.call 建第 1 层 Lua 帧 + __call 元方法，
            //     JavaClass.call 内部 LuaCall.callLua(ctor) 再建第 2 层。
            case "ctor_frame2" -> {
                for (int i = 0; i < n; i++) sink += LuaCall.call(viewClass, ctxLua).hashCode();
            }
            // A2：绕过第 1 层（直接虚调 JavaClass.call），内部第 2 层仍在。
            case "ctor_frame1" -> {
                for (int i = 0; i < n; i++) sink += ((JavaObject) viewClass).call(ctxLua).arg1().hashCode();
            }
            // A3：两层全绕过（直调构造器包装），等价 viewClass.call(ctx) 直调的成本量级。
            case "ctor_frame0" -> {
                for (int i = 0; i < n; i++)
                    sink += ((LuaFunction) ctorDirect).call(ctxLua).arg1().hashCode();
            }

            // ---- 成员方法调用（addView / setTextSize 等形态）----
            // B1：当前 LuaLayout 路径 —— JavaCall.callLua -> LuaCall.invoke 建 Lua 帧。
            case "member_frame1" -> {
                for (int i = 0; i < n; i++) sink += JavaCall.callLua(addView, (Object) null).hashCode();
            }
            // B2：直调 JavaOOMethod.call，零 Lua 帧（等价直调路径）。
            case "member_frame0" -> {
                for (int i = 0; i < n; i++)
                    sink += ((LuaFunction) addView).call(LuaValue.NIL).arg1().hashCode();
            }

            // ---- 属性写入（view.set，不经 Lua 帧，两版结构相同）----
            case "set_str" -> {
                for (int i = 0; i < n; i++) {
                    view.set(textKey, textVal);
                    sink++;
                }
            }
            case "set_int" -> {
                for (int i = 0; i < n; i++) {
                    view.set(widthKey, widthVal);
                    sink++;
                }
            }
            case "get_str" -> {
                for (int i = 0; i < n; i++) sink += view.get(textKey).hashCode();
            }

            // ---- LuaString.toJavaString：每次重新 UTF-8 解码，无解码结果缓存 ----
            case "tojstring" -> {
                for (int i = 0; i < n; i++) sink += keyStr.toJavaString().length();
            }
            // 对照：JavaClass.get 每次都 key.toJavaString() + String switch
            case "javaclass_get" -> {
                for (int i = 0; i < n; i++) sink += viewClass.get(newKey).hashCode();
            }

            // ---- JavaMethod 构造成本：MethodHandle unreflect+asSpreader vs 纯反射 ----
            //   luajvm 的 JavaMethod 构造器**无条件**做 unreflect + asSpreader；对比基线零 MethodHandle。
            //   Android setter 常有多重载（TextView.setText 有 5+ 个），getMethod 会为
            //   每个重载都建 JavaMethod，但只有一个会被真正调用 => 绝大多数 MH 白建。
            //   n 在这两个 case 里是"轮数"，每轮处理 METHODS 全部方法。
            case "mh_build" -> {
                java.lang.reflect.Method[] ms = benchMethods();
                java.lang.invoke.MethodHandles.Lookup lk = java.lang.invoke.MethodHandles.lookup();
                for (int i = 0; i < n; i++) {
                    for (java.lang.reflect.Method m : ms) {
                        try {
                            java.lang.invoke.MethodHandle mh = lk.unreflect(m);
                            int cnt = m.getParameterCount()
                                    + (java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? 0 : 1);
                            sink += mh.asSpreader(Object[].class, cnt).hashCode();
                        } catch (IllegalAccessException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
            }
            case "refl_build" -> {
                java.lang.reflect.Method[] ms = benchMethods();
                for (int i = 0; i < n; i++) {
                    for (java.lang.reflect.Method m : ms) {
                        sink += m.getParameterTypes().length + m.getReturnType().hashCode()
                                + m.getModifiers();
                    }
                }
            }

            // ---- 分派开销剥离：同一个目标方法，经 set/get 分派 vs 直调已解析成员 ----
            //   set_str/get_str 减去这里，就是 JavaObject.set/get 自身的分派成本
            //   （两次 HashMap 查表 + Integer 拆箱 + 若干 type 分支）。
            case "set_direct" -> {
                for (int i = 0; i < n; i++) {
                    ((LuaFunction) setText).call(textVal);
                    sink++;
                }
            }
            case "get_direct" -> {
                for (int i = 0; i < n; i++)
                    sink += ((LuaFunction) getText).call(LuaValue.NONE).arg1().hashCode();
            }

            // ---- 宿主用**常量字符串键**读表：LuaLayout 每个 View 必走 13-21 次 ----
            //   `LuaValue.get(String)` = `rawget(LuaString.newStr(k))`，而 newStr(String) 每次
            //   `s.getBytes(UTF_8)` 新建 byte[] 再去 intern 表双散列探测。
            //   LuaLayout 的 parseViewStyle(4 键)/get("id")/applyMargins(4-8)/applyPadding(4-8)
            //   全是编译期常量键 ⇒ 应提升为 static final LuaString。
            //   读法：constkey_get − hoisted_get = 可省掉的部分。
            case "constkey_get" -> {
                LuaTable t = attrTable();
                for (int i = 0; i < n; i++) {
                    sink += t.get("layout_marginLeft").hashCode();
                    sink += t.get("layout_marginTop").hashCode();
                    sink += t.get("paddingLeft").hashCode();
                    sink += t.get("id").hashCode();
                }
            }
            case "hoisted_get" -> {
                LuaTable t = attrTable();
                for (int i = 0; i < n; i++) {
                    sink += t.rawget(K_MARGIN_LEFT).hashCode();
                    sink += t.rawget(K_MARGIN_TOP).hashCode();
                    sink += t.rawget(K_PADDING_LEFT).hashCode();
                    sink += t.rawget(K_ID).hashCode();
                }
            }
            // 只测 newStr 自身（剥离 rawget），确认成本在编码+intern 而非查表
            case "newstr_only" -> {
                for (int i = 0; i < n; i++) {
                    sink += LuaString.newStr("layout_marginLeft").hashCode();
                }
            }

            // ---- Java 宿主侧的端到端布局构建（LuaLayout 形态）----
            //   settings_like 那组是 Lua 脚本驱动（走 VM 的 OP_CALL -> tryfuncTM），
            //   测不到 LuaLayout 这种"Java 宿主主动调 JavaCall.construct / JavaObject.set /
            //   getJavaMethod" 的路径。本用例复现后者：13 个 item，每 item 一个容器 +
            //   3 个子控件，每个控件构造后设 5 个属性再加进父容器。
            case "layout_host", "layout_host_cold" -> {
                for (int i = 0; i < n; i++) sink += buildLayoutTree();
            }
            default -> throw new IllegalArgumentException("unknown case: " + c);
        }
    }

    // 常量键提升后的形态（对照 constkey_get）
    private static final LuaString K_MARGIN_LEFT = LuaString.newStr("layout_marginLeft");
    private static final LuaString K_MARGIN_TOP = LuaString.newStr("layout_marginTop");
    private static final LuaString K_PADDING_LEFT = LuaString.newStr("paddingLeft");
    private static final LuaString K_ID = LuaString.newStr("id");

    /** 属性表：形态同 LuaLayout 的 layout 表（有 margin、无 padding，故 padding 走 nil 回退）。 */
    private static LuaTable attrTable() {
        LuaTable t = new LuaTable();
        t.set("id", LuaString.newStr("card"));
        t.set("layout_marginLeft", LuaValue.valueOf(16));
        t.set("layout_marginTop", LuaValue.valueOf(8));
        return t;
    }

    // 迷你布局器的预备值（与 LuaLayout 一样：类引用、属性键、属性值都在循环外备好）
    private static LuaValue clsPanel;
    private static LuaValue clsLabel;
    private static LuaValue clsButton;
    private static LuaValue[] attrKeys;
    private static LuaValue[] attrVals;
    private static LuaValue addKey;

    private static void initLayoutTree() {
        clsPanel = Coercion.toLua(javax.swing.JPanel.class);
        clsLabel = Coercion.toLua(javax.swing.JLabel.class);
        clsButton = Coercion.toLua(javax.swing.JButton.class);
        attrKeys = new LuaValue[]{
                LuaString.newStr("name"), LuaString.newStr("toolTipText"),
                LuaString.newStr("opaque"), LuaString.newStr("visible"),
                LuaString.newStr("enabled")};
        attrVals = new LuaValue[]{
                LuaString.newStr("card"), LuaString.newStr("tip"),
                LuaValue.TRUE, LuaValue.TRUE, LuaValue.TRUE};
        addKey = LuaString.newStr("add");
    }

    /** 构建一棵 13 item x (1 容器 + 3 子控件) 的树，全程走 bind 层宿主 API。 */
    private static int buildLayoutTree() {
        JavaObject root = (JavaObject) JavaCall.construct(clsPanel);
        for (int item = 0; item < 13; item++) {
            JavaObject card = newNode(clsPanel);
            attach(card, newNode(clsLabel));
            attach(card, newNode(clsLabel));
            attach(card, newNode(clsButton));
            attach(root, card);
        }
        return root.hashCode();
    }

    private static JavaObject newNode(LuaValue cls) {
        JavaObject v = (JavaObject) JavaCall.construct(cls);
        for (int a = 0; a < attrKeys.length; a++) v.set(attrKeys[a], attrVals[a]);
        return v;
    }

    private static void attach(JavaObject parent, JavaObject child) {
        JavaCall.invokeMember(parent.getJavaMethod(addKey), child);
    }

    /**
     * 仅供 {@code BindFastPathTest} 的签名多态检测器自证：这里**故意**保留一处
     * {@code MethodHandle.invoke}（签名多态）调用，让那个门禁能验证自己真的抓得到。
     * <b>从不被调用</b>；本类属 test 模块、不进 APK，故不影响 minSdk。
     */
    @SuppressWarnings("unused")
    private static Object signaturePolymorphicDecoy(java.lang.invoke.MethodHandle mh)
            throws Throwable {
        return mh.invoke();
    }

    /** 取一组固定的 public 方法作为 JavaMethod 构造负载（非 varargs，可 unreflect）。 */
    private static java.lang.reflect.Method[] benchMethods() {
        java.lang.reflect.Method[] all = java.util.ArrayList.class.getMethods();
        java.util.List<java.lang.reflect.Method> keep = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : all) {
            if (!m.isVarArgs()) keep.add(m);
        }
        return keep.toArray(new java.lang.reflect.Method[0]);
    }

    private BindDispatchBench() {
    }
}
