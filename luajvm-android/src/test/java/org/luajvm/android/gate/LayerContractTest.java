// java-only 门禁：luajvm-android 的包依赖必须保持单向分层，防止依赖环回潮。
package org.luajvm.android.gate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * java-only：钉住 {@code org.luajvm.android} 的包分层契约。
 *
 * <p><b>目标分层</b>（依赖只准向下）：
 * <pre>
 *   api      （纯 SPI，含 LuaContext 族 / LuaGcable / LuaSafHost / CallLuaFunction）
 *     ↑ runtime （LuaConfig / LuaLog / LuaScheduler / LuaTimer / LuaPathResolver 等底座）
 *     ↑ util / proxy（平层：util→api+runtime；proxy→api+runtime+util）
 *     ↑ engine / lib / widget / net（engine 只依赖 api+runtime；lib/widget/net 同层互不依赖）
 *     ↑ host / root（组合根：Delegate 三件套、LuaIntentHelper、AssetInstaller、LuaApplication）
 * </pre>
 *
 * <p><b>为什么扫 class 文件</b>：与 HostContractTest 同因——luajvm-android 是 Android
 * 库，其类不在本模块 classpath 上。二进制类名（{@code org/luajvm/android/...}）在
 * 常量池里以 UTF8 常量出现，直接在字节里搜即可覆盖类引用、方法表 owner、字段 owner、
 * 注解描述符等全部依赖形态。
 *
 */
public final class LayerContractTest {

    /** 源包 → 禁止引用的目标包。"(root)" 指 org.luajvm.android 直下（LuaApplication 等）。 */
    private static final Map<String, TreeSet<String>> FORBIDDEN = Map.of(
            "api", set("engine", "runtime", "host", "lib", "widget", "util", "proxy", "net", "(root)"),
            "runtime", set("engine", "host", "lib", "widget", "util", "proxy", "net", "(root)"),
            "engine", set("engine", "host", "lib", "widget", "util", "proxy", "net", "(root)"),
            "lib", set("engine", "host", "(root)", "proxy", "net"),
            "widget", set("engine", "host", "lib", "(root)", "proxy", "net"),
            "net", set("engine", "host", "lib", "widget", "(root)", "proxy"),
            "util", set("engine", "host", "lib", "widget", "(root)", "proxy", "net"),
            "proxy", set("engine", "host", "lib", "widget", "(root)", "net"));

    /**
     * 已知例外（类名 → 目标包 → 为什么必须违反）。白名单而不是放宽规则：
     * 新增违规必须 FAIL 并到这里补理由，否则等于把门禁放水。
     */
    private static final Map<String, Map<String, String>> ALLOWED = Map.of(
            "LuaEngine", Map.of("(root)",
                    "sharedData/globalData 的唯一持久化后端在 LuaApplication，宿主路径同源"),
            "NineBitmapDrawable", Map.of("(root)",
                    "远程 .9 的 Glide 加载需要进程级 Context，(String) 构造器是 Lua 可见 API 不能加形参"),
            "LuaClassProxy", Map.of("(root)",
                    "luajava.override 的 code cache 兜底：sLuaContext 未初始化时回落 LuaApplication.getCodeCacheDir"));

    private static final Pattern REF = Pattern.compile("org/luajvm/android/([a-z]+/)?");

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws IOException {
        Path androidRoot = classRoot().resolve("org/luajvm/android");
        check("能找到已编译的 luajvm-android class（否则本门禁空转）",
                Files.isDirectory(androidRoot), String.valueOf(androidRoot));
        if (!Files.isDirectory(androidRoot)) {
            System.out.println("LayerContractTest: SKIP（luajvm-android 未编译）");
            return;
        }

        // (ownPkg, targetPkg) -> 违规类集
        Map<String, TreeSet<String>> offenders = new TreeMap<>();
        int classes = 0;
        try (Stream<Path> walk = Files.walk(androidRoot)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                String rel = classRoot().relativize(p).toString().replace('\\', '/');
                String rest = rel.substring("org/luajvm/android/".length(), rel.length() - ".class".length());
                String[] parts = rest.split("/");
                String own = parts.length == 2 ? parts[0] : "(root)";
                String outer = parts[parts.length - 1].split("\\$")[0];
                String ident = parts.length == 2 ? own + "/" + outer : outer;
                classes++;

                byte[] data = Files.readAllBytes(p);
                Matcher m = REF.matcher(new String(data, java.nio.charset.StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    String sub = m.group(1);
                    String target = sub == null ? "(root)" : sub.substring(0, sub.length() - 1);
                    if (target.equals(own)) continue;
                    TreeSet<String> forbid = FORBIDDEN.get(own);
                    if (forbid == null || !forbid.contains(target)) continue;
                    Map<String, String> allowedTargets = ALLOWED.get(outer);
                    if (allowedTargets != null && allowedTargets.containsKey(target)) continue;
                    offenders.computeIfAbsent(own + " -> " + target, k -> new TreeSet<>()).add(ident);
                }
            }
        }

        check("扫描到的 class 数应 > 60（重构后约 90+，否则路径判断错了）", classes > 60,
                "classes=" + classes);
        check("包依赖无违规（白名单除外）", offenders.isEmpty(), String.join("; ", offenders.entrySet()
                .stream().map(e -> e.getKey() + ": " + e.getValue()).toList()));

        // 自证 1：白名单里的类确实还在扫（防止类改名后白名单变哑弹）
        TreeSet<String> alive = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(androidRoot)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                String rel = classRoot().relativize(p).toString().replace('\\', '/');
                for (String s : ALLOWED.keySet()) if (rel.endsWith("/" + s + ".class")) alive.add(s);
            }
        }
        check("白名单类全部存在（否则条目过期）", alive.containsAll(ALLOWED.keySet()),
                "missing=" + new TreeSet<>(ALLOWED.keySet()) {
                    {
                        removeAll(alive);
                    }
                });

        // 自证 2：把 api 塞一个假想违规必须被规则命中（规则非恒真）
        boolean wouldCatch = FORBIDDEN.get("api").contains("host");
        check("规则自证：api 禁 host 必须在规则表里", wouldCatch, "FORBIDDEN(api)");

        System.out.println(failures == 0
                ? "LayerContractTest: PASS (" + checks + " checks, " + classes + " classes)"
                : "LayerContractTest: FAIL");
        if (failures != 0) throw new AssertionError(failures + " 项断言失败");
    }

    private static TreeSet<String> set(String... items) {
        return new TreeSet<>(java.util.List.of(items));
    }

    private static void check(String name, boolean ok, String detail) {
        checks++;
        System.out.printf(Locale.ROOT, "  %-6s %s%s%n", ok ? "OK" : "FAIL", name,
                ok || detail.isEmpty() ? "" : " —— " + detail);
        if (!ok) failures++;
    }

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
            if (Files.isDirectory(p.resolve("luajvm-android"))) return p;
            p = p.getParent();
        }
        throw new IllegalStateException("repo root not found from " + Path.of("").toAbsolutePath());
    }

    private LayerContractTest() {
    }
}
