// java-only 门禁：校验 Baseline Profile 的每条规则都真实匹配到类，避免 profile 变哑弹。
package org.luajvm.android.gate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * java-only：校验 {@code luajvm-android/src/main/baselineProfiles/baseline-prof.txt}。
 *
 * <p><b>为什么需要这个门禁</b>：ART 的 profile 编译器对**匹配不到任何类/方法的规则静默
 * 丢弃**——没有警告、没有错误、构建照样成功。一旦包名或类名被重命名，profile 就悄悄
 * 退化成空文件，而"冷启动变慢"只会在设备上体现，代码评审看不出来。
 * **空转的门禁比没有门禁更危险**——它给人虚假的安全感。
 *
 * <p>三组断言：
 * <ol>
 *   <li>文件存在、非空、且被 AGP 放进 release AAR（后者由 {@code assembleRelease} 保证，
 *       此处只校验源文件）；</li>
 *   <li>每条规则语法合法（HRF：可选 {@code HSP} 标志 + {@code Lpkg/Cls;} + 可选
 *       {@code ->method}）；</li>
 *   <li><b>每条规则的类前缀至少匹配一个真实 class 文件</b>——按已编译的 class 目录扫，
 *       不用反射（{@code luajvm-android} 是 Android 库，其类不在本模块 classpath 上）。</li>
 * </ol>
 *
 * <p><b>检测器自证</b>：最后一步用一条**故意写错**的规则跑同一个匹配器，必须判不匹配。
 * 否则说明匹配器恒真，前面的断言全是空转。
 *
 * <p>Java 特有：C 无对应（ART profile 是 Android 平台机制）。
 */
public final class BaselineProfileTest {

    /** 规则行：可选标志（H/S/P 任意组合）+ 类描述符 + 可选方法部分。 */
    private static final Pattern RULE = Pattern.compile(
            "^(?<flags>[HSP]*)L(?<cls>[A-Za-z0-9/_$*]+);(?<method>->.*)?$");

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws IOException {
        Path profile = locateProfile();
        String text = Files.readString(profile);
        List<String> rules = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            rules.add(line);
        }

        check("profile 文件存在且非空", !rules.isEmpty(),
                "规则条数=" + rules.size() + " @ " + profile);

        // ---- 语法 ----
        TreeSet<String> malformed = new TreeSet<>();
        for (String r : rules) {
            if (!RULE.matcher(r).matches()) malformed.add(r);
        }
        check("每条规则语法合法（HRF）", malformed.isEmpty(), "非法行：" + malformed);

        // ---- 类前缀必须匹配到真实 class ----
        List<String> classes = scanClasses();
        check("能扫到已编译的 class（否则本门禁空转）", classes.size() > 200,
                "class 数=" + classes.size());

        TreeSet<String> dead = new TreeSet<>();
        for (String r : rules) {
            var m = RULE.matcher(r);
            if (!m.matches()) continue;                  // 语法断言已单独报过
            if (!matchesAny(m.group("cls"), classes)) dead.add(r);
        }
        check("每条规则至少匹配一个真实类（profile 不得有哑弹规则）", dead.isEmpty(),
                "匹配不到类的规则：" + dead);

        // ---- 检测器自证：写错的规则必须被判不匹配 ----
        check("自证：不存在的包必须判不匹配",
                !matchesAny("org/luajvm/nosuchpkg/**", classes),
                "不存在的包判不匹配（若 OK 则 matcher 非恒真）");
        check("自证：拼错的类名必须判不匹配",
                !matchesAny("org/luajvm/core/LuaStringg", classes),
                "拼错的类名判不匹配（若 OK 则 matcher 非恒真）");
        check("自证：真实存在的类必须判匹配",
                matchesAny("org/luajvm/core/LuaString", classes),
                "真实类判匹配（若 OK 则 matcher 非恒假）");

        System.out.println(failures == 0
                ? "BaselineProfileTest: PASS (" + checks + " checks, " + rules.size() + " rules)"
                : "BaselineProfileTest: FAIL");
        if (failures != 0) throw new AssertionError(failures + " 项断言失败");
    }

    /**
     * 类前缀匹配：{@code **} 跨包，{@code *} 不跨 {@code /}（与 profgen 一致）。
     * 无通配符时按"精确类或其内部类"匹配（{@code Foo} 命中 {@code Foo$Bar}）。
     *
     * <p>必须**单趟**翻译：若先 {@code Pattern.quote} 再两次 {@code replace}，第二次会把
     * 自己刚插入的 {@code .*} 里的 {@code *} 又替一遍，产出非法正则。
     */
    private static boolean matchesAny(String pattern, List<String> classes) {
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*') {
                boolean dbl = i + 1 < pattern.length() && pattern.charAt(i + 1) == '*';
                re.append(dbl ? ".*" : "[^/]*");
                i += dbl ? 2 : 1;
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        if (pattern.indexOf('*') < 0) re.append("(\\$.*)?");
        re.append('$');
        Pattern p = Pattern.compile(re.toString());
        for (String c : classes) {
            if (p.matcher(c).matches()) return true;
        }
        return false;
    }

    /** 扫两个模块已编译的 class，返回 {@code org/luajvm/core/LuaString} 形态的内部名。 */
    private static List<String> scanClasses() throws IOException {
        List<String> out = new ArrayList<>();
        for (Path root : classRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    String rel = root.relativize(p).toString()
                            .replace('\\', '/');
                    out.add(rel.substring(0, rel.length() - ".class".length()));
                });
            }
        }
        return out;
    }

    /**
     * class 输出目录。{@code luajvm-android} 是 Android 库，其 class 在
     * {@code build/intermediates/javac/**} 下（AGP 布局），路径按变体不同，故遍历找。
     */
    private static List<Path> classRoots() throws IOException {
        Path repo = repoRoot();
        List<Path> roots = new ArrayList<>();
        roots.add(repo.resolve("luajvm-core/build/classes/java/main"));
        Path agp = repo.resolve("luajvm-android/build/intermediates/javac");
        if (Files.isDirectory(agp)) {
            try (Stream<Path> s = Files.walk(agp, 3)) {
                s.filter(p -> p.getFileName().toString().equals("classes"))
                        .filter(Files::isDirectory)
                        .forEach(roots::add);
            }
        }
        return roots;
    }

    private static Path locateProfile() {
        return repoRoot().resolve("luajvm-android/src/main/baselineProfiles/baseline-prof.txt");
    }

    /** 从工作目录向上找含 {@code settings.gradle} 的目录。 */
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("settings.gradle"))
                    || Files.isRegularFile(p.resolve("settings.gradle.kts"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("找不到仓库根（settings.gradle）");
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.printf(Locale.ROOT, "  %-4s %s。实测：%s%n", ok ? "OK" : "FAIL", what, detail);
    }

    private BaselineProfileTest() {
    }
}
