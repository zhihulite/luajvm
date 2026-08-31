// java-only 门禁：luajvm-android 的 Lua VM 进入点必须全部经自动执行区（LuaCall.invoke / JavaCall）。
package org.luajvm.android.gate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * java-only：钉死「线程进入统一」的契约。
 *
 * <p><b>背景</b>：core 层唯一自动进执行区（per-Globals 可重入 ReentrantLock）的入口是
 * {@code LuaCall.invoke}；{@code LuaCall.call/callLua/callNoYield/callOnStack*} 契约上
 * 「串行化由上层执行所有者负责」，跨线程裸进会与 Lua 执行并发直捣解释器栈（真实形态：
 * LuaWebView 的 shouldInterceptRequest 在 WebView 后台线程用 call 进广告过滤，与主线程
 * 脚本无互斥）。{@code JavaCall.call}
 * 经 dispatch 对真 Lua 目标路由到 invoke，属自动保护。
 *
 * <p><b>规则</b>（源码扫描 luajvm-android/src/main）：
 * <ol>
 *   <li>{@code LuaCall.} 后只允许 {@code invoke}——其余全系（call、callLua、callNoYield、
 *       callOnStack 系列、invokeNoYield）都不拿锁，宿主层一律不准用；</li>
 *   <li>裸 {@code xxx.call(}（receiver 非 JavaCall/非已知安全路由）零容忍——
 *       ActivityDelegate.onActivityResult 的 {@code func.call(...)} 与 loadmenu 的
 *       {@code mLoadBitmap.call(...)} 就是这条规则要拦的形态。</li>
 * </ol>
 *
 * <p><b>为什么扫源码而不是 class</b>：方法引用在常量池里拆成 Class/NameAndType 多个
 * 常量，源码级的 receiver 名（JavaCall vs func）在字节码里反而难以精确关联。
 */
public final class ThreadEntryContractTest {

    /** LuaCall 允许的唯一前缀。 */
    private static final Pattern LUA_CALL_FORBIDDEN =
            Pattern.compile("LuaCall\\.(?!invoke\\()\\w");

    /** 检出所有 .call( 形态（先由调用方剔除允许前缀）。 */
    private static final Pattern RAW_DOT_CALL = Pattern.compile("[\\w)\\]]\\.call\\(");

    /** 已知安全的 .call( 前缀：JavaCall（dispatch→invoke 自动进区）与自家路由方法。 */
    private static final String[] SAFE_DOT_CALL = {
            "JavaCall.call(", "getLuaDelegate().call(", "mEngine.call("};

    /**
     * 白名单（src/main 下相对路径 + receiver → 理由）。加条目必须写清为什么它不是 Lua 进入点。
     */
    private static final Map<String, String> ALLOWED_RAW_CALL = Map.of(
            "org/luajvm/android/runtime/LuaConfig.java:action",
            "java.util.concurrent.Callable.call()，与 Lua VM 无关");

    private static final String SRC_ROOT = "luajvm-android/src/main/java";

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = repoRoot().resolve(SRC_ROOT);
        check("能找到 luajvm-android 源码目录（否则本门禁空转）",
                Files.isDirectory(root), String.valueOf(root));
        if (!Files.isDirectory(root)) {
            System.out.println("ThreadEntryContractTest: SKIP（源码目录不存在）");
            return;
        }

        TreeSet<String> badLuaCall = new TreeSet<>();
        TreeSet<String> badRawCall = new TreeSet<>();
        int files = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                files++;
                String rel = repoRoot().relativize(p).toString().replace('\\', '/');
                rel = rel.substring(SRC_ROOT.length() + 1);
                int lineNo = 0;
                boolean inBlockComment = false;
                for (String rawLine : Files.readAllLines(p)) {
                    lineNo++;
                    // 只扫代码：去掉 // 尾注与 /* */ 块注（javadoc 里提到 LuaCall.call
                    // 是文档不是调用；字符串字面量出现这些模式在本库不存在）
                    String line = rawLine;
                    if (inBlockComment) {
                        int end = line.indexOf("*/");
                        if (end < 0) continue;
                        line = line.substring(end + 2);
                        inBlockComment = false;
                    }
                    int blockStart = line.indexOf("/*");
                    if (blockStart >= 0) {
                        int blockEnd = line.indexOf("*/", blockStart + 2);
                        if (blockEnd >= 0) {
                            line = line.substring(0, blockStart) + " " + line.substring(blockEnd + 2);
                        } else {
                            line = line.substring(0, blockStart);
                            inBlockComment = true;
                        }
                    }
                    int lineComment = line.indexOf("//");
                    if (lineComment >= 0) line = line.substring(0, lineComment);

                    if (LUA_CALL_FORBIDDEN.matcher(line).find()) {
                        badLuaCall.add(rel + ":" + lineNo + " " + rawLine.trim());
                    }
                    if (line.contains(".call(")) {
                        String rest = line;
                        for (String safe : SAFE_DOT_CALL) rest = rest.replace(safe, "");
                        if (RAW_DOT_CALL.matcher(rest).find()) {
                            String receiver = rest.replaceAll(".*?(?:^|[\\s(=,])([\\w)\\]]+)\\.call\\(.*", "$1");
                            String key = rel + ":" + receiver;
                            if (!ALLOWED_RAW_CALL.containsKey(key)) {
                                badRawCall.add(rel + ":" + lineNo + " " + line.trim());
                            }
                        }
                    }
                }
            }
        }

        check("扫描的 java 文件数应 > 70（否则路径判断错了）", files > 70, "files=" + files);
        check("LuaCall 只允许 invoke 系（call/callLua/callNoYield/callOnStack* 全禁）",
                badLuaCall.isEmpty(), String.join("; ", badLuaCall));
        check("裸 .call( 零容忍（白名单：" + ALLOWED_RAW_CALL.size() + " 条）",
                badRawCall.isEmpty(), String.join("; ", badRawCall));

        // 自证 1：规则非恒真——合成违规串必须被两条规则各自命中
        boolean syntheticLuaCall = LUA_CALL_FORBIDDEN.matcher("Object r = LuaCall.call(f);").find()
                && !LUA_CALL_FORBIDDEN.matcher("Object r = LuaCall.invoke(f, a);").find();
        check("规则自证：LuaCall.call 合成串命中且 invoke 不误伤", syntheticLuaCall, "ruleA");
        String stripped = "JavaCall.call(func, x)";
        for (String safe : SAFE_DOT_CALL) stripped = stripped.replace(safe, "");
        boolean syntheticRaw = RAW_DOT_CALL.matcher("func.call(x)").find()
                && !RAW_DOT_CALL.matcher(stripped).find();
        check("规则自证：裸 func.call 命中且 JavaCall.call 不算裸调", syntheticRaw, "ruleB");

        // 自证 2：白名单条目对应的 receiver 在源码里真实存在（防条目过期变哑弹）
        TreeSet<String> stale = new TreeSet<>();
        for (String key : ALLOWED_RAW_CALL.keySet()) {
            String file = key.substring(0, key.lastIndexOf(':'));
            String receiver = key.substring(key.lastIndexOf(':') + 1);
            Path p = root.resolve(file);
            boolean found = false;
            if (Files.isRegularFile(p)) {
                try (Stream<String> ls = Files.lines(p)) {
                    found = ls.anyMatch(l -> l.contains(receiver + ".call("));
                }
            }
            if (!found) stale.add(key);
        }
        check("白名单 receiver 仍存在（否则条目过期）", stale.isEmpty(), String.valueOf(stale));

        System.out.println(failures == 0
                ? "ThreadEntryContractTest: PASS (" + checks + " checks, " + files + " files)"
                : "ThreadEntryContractTest: FAIL");
        if (failures != 0) throw new AssertionError(failures + " 项断言失败");
    }

    private static void check(String name, boolean ok, String detail) {
        checks++;
        System.out.printf(Locale.ROOT, "  %-6s %s%s%n", ok ? "OK" : "FAIL", name,
                ok || detail == null || detail.isEmpty() ? "" : " —— " + detail);
        if (!ok) failures++;
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.isDirectory(p.resolve(SRC_ROOT))) return p;
            p = p.getParent();
        }
        throw new IllegalStateException("repo root not found from " + Path.of("").toAbsolutePath());
    }

    private ThreadEntryContractTest() {
    }
}
