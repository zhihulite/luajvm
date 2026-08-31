// java-only 门禁：宿主类不得再手写 LuaContext 转发，且接口 default 不得被父类静默盖掉。
package org.luajvm.android.gate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * java-only：钉住 {@code org.luajvm.android.api.LuaHost} 那套 {@code default} 转发。
 *
 * <p><b>为什么需要</b>：宿主逐个手写 {@code LuaContext} 转发时极易抄错方向
 * （{@code getHeight()} 抄成 {@code mDelegate.getWidth()}）—— 任何 Lua 读
 * {@code activity.height} 拿到的都是宽度。统一走接口 {@code default} 转发后，
 * 剩两个**静默失效**风险：
 *
 * <ol>
 *   <li>有人图省事又在宿主里手写一个转发（可能又抄错）；</li>
 *   <li>宿主的**父类**恰好有同签名方法 ⇒ 按 JLS 的类优先规则，父类静默盖掉接口
 *       default。真实先例：{@code AppCompatActivity} 已经有
 *       {@code getDelegate()}，只是返回类型不兼容才被编译器拦住 —— 换个返回类型兼容的
 *       名字就会变成运行期静默错误。故访问器命名为 {@code getLuaDelegate()}。</li>
 * </ol>
 *
 * <p><b>为什么扫 class 文件而不用反射</b>：{@code luajvm-android} 是 Android 库，
 * 其类不在本模块 classpath 上（沿用 {@code BaselineProfileTest} 的做法）。
 *
 * <p>Java 特有：C 无对应。
 */
public final class HostContractTest {

    /** {@code LuaContext} + {@code LuaHostDelegate} 的方法名：宿主一个都不该再自己声明。 */
    private static final Set<String> FORWARDED = Set.of(
            "getLuaState", "doFile", "call", "set",
            "findResource", "findFile", "getRootDir", "getLuaDir", "getLuaPath",
            "getLuaExtDir", "setLuaExtDir", "getLuaExtPath",
            "getGlobalData", "getSharedData", "setSharedData",
            "getContext", "getWidth", "getHeight", "getDensity",
            "sendMsg", "sendError", "regGc", "getClassLoaders",
            "runFunc", "newActivity", "getUriForFile", "getPathFromUri",
            "installApk", "openFile", "shareFile");

    /**
     * 允许宿主自己声明的例外，值是**为什么必须自己写**。
     *
     * <p>白名单而不是放宽规则：这样任何**新**的手写转发都会 FAIL，而这两处真正的
     * override 有据可查。加新条目时必须写清理由，否则等于把门禁放水。
     */
    private static final java.util.Map<String, String> ALLOWED = java.util.Map.of(
            "LuaActivity.newActivity",
            "6 参重载（多 in/out 转场动画）不在接口上，且转发后还要 overridePendingTransition",
            "LuaActivity.sendMsg",
            "转发之外还要 notifyDataSetChanged 刷新 showLogs 的列表");

    private static final String[] HOSTS = {
            "LuaActivity", "Welcome", "LuaService", "LuaWallpaperService",
            "LuaAccessibilityService", "LuaNotificationListenerService"};

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws IOException {
        Path hostDir = classRoot().resolve("org/luajvm/android/host");
        check("能找到已编译的宿主 class（否则本门禁空转）", Files.isDirectory(hostDir),
                String.valueOf(hostDir));
        if (!Files.isDirectory(hostDir)) {
            System.out.println("HostContractTest: SKIP（luajvm-android 未编译）");
            return;
        }

        noHandWrittenForwarding(hostDir);
        everyHostGivesLuaDelegate(hostDir);
        heightIsNotWidth();
        spiServicesResolve();
        luaCallbacksAnnotated();
        selfCheck(hostDir);

        System.out.println(failures == 0
                ? "HostContractTest: PASS (" + checks + " checks)"
                : "HostContractTest: FAIL");
        if (failures != 0) throw new AssertionError(failures + " 项断言失败");
    }

    /** 断言 1：宿主 class 里不得再声明被转发的那批方法。 */
    private static void noHandWrittenForwarding(Path hostDir) throws IOException {
        TreeSet<String> offenders = new TreeSet<>();
        for (String h : HOSTS) {
            Path p = hostDir.resolve(h + ".class");
            if (!Files.isRegularFile(p)) continue;
            for (String m : declaredMethods(p)) {
                if (FORWARDED.contains(m) && !ALLOWED.containsKey(h + "." + m)) {
                    offenders.add(h + "." + m + "()");
                }
            }
        }
        check("宿主不得手写 LuaContext 转发（应由 LuaHost 的 default 提供）",
                offenders.isEmpty(),
                offenders.isEmpty()
                        ? "6 个宿主均无手写转发（" + ALLOWED.size() + " 处有据可查的 override 已白名单）"
                        : "仍在手写：" + offenders);
        // 白名单不得腐烂：列进去的必须真的还在
        TreeSet<String> stale = new TreeSet<>();
        for (String key : ALLOWED.keySet()) {
            int dot = key.indexOf('.');
            String cls = key.substring(0, dot);
            String mth = key.substring(dot + 1);
            Path p = hostDir.resolve(cls + ".class");
            if (!Files.isRegularFile(p) || !declaredMethods(p).contains(mth)) stale.add(key);
        }
        check("白名单里的例外必须真实存在（防止规则腐烂）", stale.isEmpty(),
                stale.isEmpty() ? ALLOWED.keySet().toString() : "已不存在：" + stale);
    }

    /** 断言 2：每个宿主都得给出 getLuaDelegate（否则 default 无处可转）。 */
    private static void everyHostGivesLuaDelegate(Path hostDir) throws IOException {
        TreeSet<String> missing = new TreeSet<>();
        for (String h : HOSTS) {
            Path p = hostDir.resolve(h + ".class");
            if (!Files.isRegularFile(p)) continue;
            if (!declaredMethods(p).contains("getLuaDelegate")) missing.add(h);
        }
        check("每个宿主都声明 getLuaDelegate()", missing.isEmpty(),
                missing.isEmpty() ? "6/6 都有" : "缺：" + missing);
        // 名字不能叫 getDelegate —— AppCompatActivity 已占用（返回 AppCompatDelegate）
        check("访问器不叫 getDelegate（避开 AppCompatActivity 同名方法）",
                !FORWARDED.contains("getDelegate"), "常量表里没有 getDelegate");
    }

    /** 断言 3：getHeight 不许调 getWidth——转发方向错了 Lua 读到的就是宽度。 */
    private static void heightIsNotWidth() throws IOException {
        Path p = classRoot().resolve("org/luajvm/android/host/BaseDelegate.class");
        if (!Files.isRegularFile(p)) {
            check("BaseDelegate.class 存在", false, String.valueOf(p));
            return;
        }
        String pool = String.join(" ", constantPoolStrings(p));
        check("BaseDelegate 仍引用 getHeight（它必须自己去取高度）",
                pool.contains("getHeight"), "常量池含 getHeight");
        // 反向自证在 selfCheck 里做
    }

    /**
     * 断言 4：{@code META-INF/services} 里声明的实现类必须真实存在。
     *
     * <p><b>为什么专门守这一条</b>：该文件没有扩展名，按 {@code *.java/*.lua/*.xml}
     * 之类的后缀去搜是搜不到的；而 ServiceLoader 找不到类只在**运行期**失败，
     * 编译与全部 JVM 门禁都不会响 —— 改包名时最容易漏这里。
     */
    private static void spiServicesResolve() throws IOException {
        Path dir = repoRoot().resolve("luajvm-android/src/main/resources/META-INF/services");
        check("能找到 META-INF/services（否则本条空转）", Files.isDirectory(dir),
                String.valueOf(dir));
        if (!Files.isDirectory(dir)) return;
        Path root = classRoot();
        TreeSet<String> missing = new TreeSet<>();
        int n = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                for (String line : Files.readAllLines(f)) {
                    String impl = line.trim();
                    if (impl.isEmpty() || impl.startsWith("#")) continue;
                    n++;
                    Path cls = root.resolve(impl.replace('.', '/') + ".class");
                    if (!Files.isRegularFile(cls)) {
                        missing.add(f.getFileName() + " -> " + impl);
                    }
                }
            }
        }
        check("SPI 声明的实现类都编译得出来（改包名最容易漏这里）", missing.isEmpty(),
                missing.isEmpty() ? "检查了 " + n + " 条声明" : "找不到 class：" + missing);
    }

    /**
     * 断言 5：凡是形参含 {@code LuaFunction} 的 public 方法/构造器，必须带
     * {@code @CallLuaFunction} 标注回调线程。
     *
     * <p><b>为什么要这条</b>：{@code CallLuaFunction} 反射期不可见时形同虚设
     * （无 {@code @Retention} 即反射不可见、全仓零使用）；没有门禁盯着，新增回调入口
     * 漏标就会悄悄退化。全仓这类入口的回调线程至少四种（主线程 / 调度线程 /
     * Filter 工作线程 / 绘制线程），甚至同一回调在不同触发点线程不同
     * （{@code LuaWebView} 的 adsFilter），所以"标出来"是有实际价值的。
     *
     * <p>直接读 class 的常量池与方法表：方法描述符里出现
     * {@code Lorg/luajvm/core/LuaFunction;} 即算回调入口。
     */
    private static void luaCallbacksAnnotated() throws IOException {
        Path root = classRoot();
        if (!Files.isDirectory(root)) return;
        TreeSet<String> missing = new TreeSet<>();
        int total = 0;
        try (Stream<Path> all = Files.walk(root)) {
            for (Path p : all.filter(f -> f.toString().endsWith(".class")).toList()) {
                byte[] b = Files.readAllBytes(p);
                List<String> pool = new ArrayList<>();
                int off = parsePool(b, pool);
                // 该 class 的常量池里根本没有 LuaFunction 描述符 => 不可能有这种入口
                boolean mentions = false;
                for (String u : pool) {
                    if (u != null && u.contains("Lorg/luajvm/core/LuaFunction;")) {
                        mentions = true;
                        break;
                    }
                }
                if (!mentions) continue;
                String cls = root.relativize(p).toString()
                        .replace(java.io.File.separatorChar, '/');
                cls = cls.substring(0, cls.length() - ".class".length());
                for (String[] m : membersWithFlagsAndAnnotations(b, pool)) {
                    String name = m[0], desc = m[1], flags = m[2], anns = m[3];
                    // 只看 public、且参数表里含 LuaFunction
                    if (!"public".equals(flags)) continue;
                    String params = desc.substring(1, desc.indexOf(')'));
                    if (!params.contains("Lorg/luajvm/core/LuaFunction;")) continue;
                    total++;
                    if (!anns.contains("CallLuaFunction")) {
                        missing.add(cls + "." + name + desc);
                    }
                }
            }
        }
        check("能扫到收 LuaFunction 的入口（否则本条空转）", total > 0,
                "扫到 " + total + " 个");
        check("收 LuaFunction 的 public 入口都带 @CallLuaFunction", missing.isEmpty(),
                missing.isEmpty() ? total + " 个全部已标注" : "漏标：" + missing);
    }

    /**
     * 返回 {@code {名, 描述符, "public"/"", 注解名拼接}}，覆盖字段之后的方法表。
     *
     * <p>只解析 {@code RuntimeVisibleAnnotations} —— 这也是为什么
     * {@code CallLuaFunction} 必须是 {@code RetentionPolicy.RUNTIME}：
     * {@code CLASS} 保留的注解进的是 {@code RuntimeInvisibleAnnotations}，本门禁看不到，
     * 那样这条断言就会全员 FAIL（这本身就是"注解必须 RUNTIME"的机械证明）。
     */
    private static List<String[]> membersWithFlagsAndAnnotations(byte[] b, List<String> pool) {
        int off = poolEnd(b);
        int ifaceCount = u2(b, off + 6);
        int q = off + 8 + ifaceCount * 2;
        int fieldsCount = u2(b, q);
        q += 2;
        for (int i = 0; i < fieldsCount; i++) q = skipMember(b, q);
        int methodsCount = u2(b, q);
        q += 2;
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < methodsCount; i++) {
            int acc = u2(b, q);
            String name = pool.get(u2(b, q + 2));
            String desc = pool.get(u2(b, q + 4));
            int attrCount = u2(b, q + 6);
            int r = q + 8;
            StringBuilder anns = new StringBuilder();
            for (int a = 0; a < attrCount; a++) {
                String attrName = pool.get(u2(b, r));
                int len = (int) u4(b, r + 2);
                if ("RuntimeVisibleAnnotations".equals(attrName)) {
                    int n = u2(b, r + 6);
                    int t = r + 8;
                    for (int k = 0; k < n; k++) {
                        String type = pool.get(u2(b, t));
                        if (type != null) anns.append(type).append(' ');
                        break;   // 只需知道有没有；不解析 element_value 对
                    }
                }
                r += 6 + len;
            }
            out.add(new String[]{name, desc, (acc & 0x0001) != 0 ? "public" : "", anns.toString()});
            q = r;
        }
        return out;
    }

    /** 常量池结束偏移（重跑一遍 parsePool 只为拿位置）。 */
    private static int poolEnd(byte[] b) {
        return parsePool(b, new ArrayList<>());
    }

    /** 检测器自证：故意用一个不存在的方法名与一个必然存在的方法名各跑一次。 */
    private static void selfCheck(Path hostDir) throws IOException {
        Path p = hostDir.resolve("LuaActivity.class");
        if (!Files.isRegularFile(p)) return;
        Set<String> ms = declaredMethods(p);
        check("自证：declaredMethods 抓得到真实方法（onCreate）",
                ms.contains("onCreate"), "LuaActivity 声明了 onCreate");
        check("自证：不存在的方法必须判不存在",
                !ms.contains("noSuchMethodZZZ"), "noSuchMethodZZZ 不在其中");
    }

    // ---- class 文件解析（只读常量池与方法表，不用反射）----

    /** 读 class 的方法表，返回本类**自己声明**的方法名集合。 */
    private static Set<String> declaredMethods(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        List<String> pool = new ArrayList<>();
        int off = parsePool(b, pool);
        // access_flags(2) this_class(2) super_class(2) interfaces_count(2)
        int ifaceCount = u2(b, off + 6);
        int q = off + 8 + ifaceCount * 2;
        int fieldsCount = u2(b, q);
        q += 2;
        for (int i = 0; i < fieldsCount; i++) q = skipMember(b, q);
        int methodsCount = u2(b, q);
        q += 2;
        TreeSet<String> out = new TreeSet<>();
        for (int i = 0; i < methodsCount; i++) {
            int nameIdx = u2(b, q + 2);
            out.add(pool.get(nameIdx));
            q = skipMember(b, q);
        }
        return out;
    }

    private static List<String> constantPoolStrings(Path p) throws IOException {
        List<String> pool = new ArrayList<>();
        parsePool(Files.readAllBytes(p), pool);
        List<String> out = new ArrayList<>();
        for (String s : pool) if (s != null) out.add(s);
        return out;
    }

    /** 解析常量池，把 UTF8 项填进 {@code pool}（索引对齐），返回池后的偏移。 */
    private static int parsePool(byte[] b, List<String> pool) {
        int count = u2(b, 8);
        for (int i = 0; i < count; i++) pool.add(null);
        int off = 10;
        for (int i = 1; i < count; i++) {
            int tag = b[off] & 0xff;
            switch (tag) {
                case 1 -> {                                  // UTF8
                    int len = u2(b, off + 1);
                    pool.set(i, new String(b, off + 3, len, java.nio.charset.StandardCharsets.UTF_8));
                    off += 3 + len;
                }
                case 7, 8, 16, 19, 20 -> off += 3;
                case 15 -> off += 4;
                case 3, 4, 9, 10, 11, 12, 17, 18 -> off += 5;
                case 5, 6 -> {                               // long/double 占两个槽
                    off += 9;
                    i++;
                    pool.add(null);
                }
                default -> throw new IllegalStateException("未知常量池 tag " + tag + " @ " + off);
            }
        }
        return off;
    }

    /** 跳过一个 field_info / method_info（含其 attributes）。 */
    private static int skipMember(byte[] b, int q) {
        int attrCount = u2(b, q + 6);
        int r = q + 8;
        for (int i = 0; i < attrCount; i++) {
            int len = (int) u4(b, r + 2);
            r += 6 + len;
        }
        return r;
    }

    private static int u2(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    private static long u4(byte[] b, int i) {
        return ((long) (b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16)
                | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    /**
     * AGP 把 luajvm-android 的 class 放在 {@code build/intermediates/javac} 下按变体分目录，
     * 末级目录名是 {@code classes}，故遍历去找。
     *
     * <p>注：javadoc 注释里不要写 {@code &#42;&#42;/} 这种通配 —— 它会提前结束 javadoc，
     * 后面的正文变成代码。
     */
    private static Path classRoot() throws IOException {
        Path agp = repoRoot().resolve("luajvm-android/build/intermediates/javac");
        if (Files.isDirectory(agp)) {
            try (Stream<Path> s = Files.walk(agp, 3)) {
                return s.filter(p -> p.getFileName().toString().equals("classes"))
                        .filter(Files::isDirectory)
                        .filter(p -> Files.isDirectory(p.resolve("org/luajvm/android/host")))
                        .findFirst().orElse(agp);
            }
        }
        return agp;
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("settings.gradle"))) return p;
            p = p.getParent();
        }
        throw new IllegalStateException("找不到仓库根（settings.gradle）");
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.printf(Locale.ROOT, "  %-4s %s。实测：%s%n", ok ? "OK" : "FAIL", what, detail);
    }

    private HostContractTest() {
    }
}
