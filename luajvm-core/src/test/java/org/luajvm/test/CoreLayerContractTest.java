// java-only 门禁：luajvm-core 的包依赖矩阵必须保持已声明的形状，防止新增反向边。
package org.luajvm.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * java-only：钉住 {@code org.luajvm}（core 模块）的包依赖矩阵。
 *
 * <p><b>目标分层</b>：
 * <pre>
 *   spi        纯接口（Compiler/Loader/LuaConfig/LuaJavaContext…）：只准看 core
 *   core       值模型 / 状态 / GC —— 与 vm **同层**（见下）
 *   vm         解释器、调用、索引、FlatTFor、装配（LuaPlatform）
 *   compiler   Lexer/Parser/CodeGen/undump
 *   lib        标准库
 *   bind       luajava 绑定
 *   tools      luac CLI（顶层，可依赖全部）
 * </pre>
 *
 * <p><b>为什么 {@code core ↔ vm} 是同层而不是环</b>：C 本身就是互调 ——
 * {@code ltable.c}/{@code lgc.c} 调 {@code luaT_callTM}（ltm/lvm），{@code lvm.c} 调
 * {@code luaH_*}。为消环把元方法派发从 {@code LuaTable} 挪走会背离 C 的结构，
 * 违反项目「逐函数对齐」的第一原则。故本门禁**不禁** core→vm，只禁
 * core→lib/bind/compiler/tools。
 *
 * <p><b>白名单即债务台账</b>：`ALLOWED` 里的每条都附理由与消除方案。
 * 新增违规必须 FAIL 并在白名单登记理由，而不是放宽规则。
 *
 * <p><b>为什么扫 class 文件</b>：常量池里的二进制类名（{@code org/luajvm/lib/...}）
 * 覆盖类引用、方法/字段 owner、注解描述符等全部依赖形态；按源码 import 扫会漏掉
 * 全限定名直接使用与内部类引用。
 */
public final class CoreLayerContractTest {

    /** 源包 → 禁止引用的目标包。 */
    private static final Map<String, TreeSet<String>> FORBIDDEN = Map.of(
            "spi", set("vm", "lib", "bind", "compiler", "tools"),
            // core 不禁 compiler：compiler.Opcodes 是 C 的 lopcodes.h（llimits/lobject 之上、
            //   lparser 与 lvm 之下的公共常量层），Java 把它放进 compiler 包只是位置问题，
            //   它自身零 luajvm 依赖（本类 onlyReferencesOpcodes 自证）。
            "core", set("lib", "bind", "tools"),
            // vm 禁 lib：装配已归位到 org.luajvm.LuaStandard（= linit.c），C 的 lvm.c
            //   从不 include 任何 l*lib.c。hook 解析与 next 身份改经 core 侧
            //   Globals.hookResolver / NextMark，故此边现为 0，可硬禁。
            "vm", set("lib", "bind", "tools"),
            "compiler", set("lib", "bind", "tools"),
            "lib", set("bind", "tools"),
            "bind", set("lib", "compiler", "tools"));

    /**
     * 已知例外（外层类名 → 目标包 → 理由与消除方案）。
     */
    private static final Map<String, Map<String, String>> ALLOWED = Map.ofEntries(
            // core 不依赖 lib / bind，故 Globals 无需例外：
            //   · baselib/debuglib 改 spi.BaseLibrary / spi.DebugTracer
            //   · warnf = lstate.c 的 global_State.warnf；hookResolver = lua_gethook 的间接层
            //   · package_ / luajavaLib 降为 LuaValue—— core 对二者【零方法调用】，
            //     反向边纯由字段声明类型造成；宿主改用 g.get("require") 与 JavaLib.forGlobals(g)。
            // [勿回填] 白名单是"放行"语义，而过期检查只问"类还在吗"——留一条已不需要的条目
            //   等于永久放水且检测不到（实测：条目在时注入违规仍 PASS）。
            Map.entry("LuaVM", Map.of(
                    "bind", "Java 宿主 __index 快路径直调 JavaObject.get（bindfastcall，"
                            + "有 A/B 与 bindFastPathTests 兜底）；属有意的跨层性能分叉，不计划消除")),
            Map.entry("LuaErrorsBridge", Map.of("lib", "占位哨兵：本类不存在，用于自证白名单过期检查非恒真")));

    private static final Pattern REF = Pattern.compile("org/luajvm/([a-z]+)/");

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = classRoot();
        check("能找到已编译的 luajvm-core class（否则本门禁空转）",
                Files.isDirectory(root.resolve("core")), String.valueOf(root));
        if (!Files.isDirectory(root.resolve("core"))) {
            throw new AssertionError("luajvm-core 未编译：" + root);
        }

        Map<String, TreeSet<String>> offenders = new TreeMap<>();
        int classes = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String[] parts = rel.split("/");
                if (parts.length < 2) continue;               // org/luajvm 直下：当前无类
                String own = parts[0];
                String outer = parts[parts.length - 1].split("\\$")[0].replace(".class", "");
                TreeSet<String> forbid = FORBIDDEN.get(own);
                if (forbid == null) continue;                 // tools 等无约束包
                classes++;

                String body = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                Matcher m = REF.matcher(body);
                while (m.find()) {
                    String target = m.group(1);
                    if (target.equals(own) || !forbid.contains(target)) continue;
                    // 结构性例外：Opcodes 对应 C 的 lopcodes.h（三方共用的指令格式定义，
                    //   零 luajvm 依赖），【指令定义】不算 compiler 依赖；用精确类名而非
                    //   放宽整包，避免顺手引入真正的编译器依赖。
                    if (target.equals("compiler") && onlyReferencesOpcodes(body)) continue;
                    Map<String, String> allowedTargets = ALLOWED.get(outer);
                    if (allowedTargets != null && allowedTargets.containsKey(target)) continue;
                    offenders.computeIfAbsent(own + " -> " + target, k -> new TreeSet<>())
                            .add(own + "/" + outer);
                }
            }
        }

        check("扫描到的受约束 class 数应 > 150（否则路径判断错了）", classes > 150,
                "classes=" + classes);
        check("包依赖矩阵无新增违规（白名单即债务台账）", offenders.isEmpty(),
                String.join("; ", offenders.entrySet().stream()
                        .map(e -> e.getKey() + ": " + e.getValue()).toList()));

        // 自证 1：白名单里的类必须都还在（改名/删除后条目会变哑弹）
        TreeSet<String> alive = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).toList()) {
                String rel = p.getFileName().toString();
                for (String s : ALLOWED.keySet()) if (rel.equals(s + ".class")) alive.add(s);
            }
        }
        TreeSet<String> missing = new TreeSet<>(ALLOWED.keySet());
        missing.removeAll(alive);
        // LuaErrorsBridge 是**故意**放的过期占位，用来证明本自证不是恒真
        check("自证：白名单的过期条目会被发现（哨兵 LuaErrorsBridge 必须落在 missing 里）",
                missing.contains("LuaErrorsBridge"), "missing=" + missing);
        missing.remove("LuaErrorsBridge");
        check("白名单其余条目全部存在", missing.isEmpty(), "missing=" + missing);

        // 自证 2：Opcodes 例外不得放行其他 compiler 类（否则等于把 compiler 整包放水）
        check("例外自证：只引 Opcodes 放行",
                onlyReferencesOpcodes("xx org/luajvm/compiler/Opcodes yy"), "");
        check("例外自证：引 Parser 必须拦下",
                !onlyReferencesOpcodes("org/luajvm/compiler/Opcodes org/luajvm/compiler/Parser"), "");

        // 自证 3：规则非恒真 —— core 禁 lib 必须在规则表里，且 core→vm 必须不禁
        check("规则自证：core 禁 lib", FORBIDDEN.get("core").contains("lib"), "");
        check("规则自证：core→vm 属同层（不得禁）", !FORBIDDEN.get("core").contains("vm"), "");
        check("规则自证：vm 禁 lib（装配已归位 org.luajvm.LuaStandard）",
                FORBIDDEN.get("vm").contains("lib"), "");
        check("规则自证：lib→vm 属向下依赖（不得禁）", !FORBIDDEN.get("lib").contains("vm"), "");

        System.out.println(failures == 0
                ? "CoreLayerContractTest: PASS (" + checks + " checks, " + classes + " classes)"
                : "CoreLayerContractTest: FAIL");
        if (failures != 0) throw new AssertionError(failures + " 项断言失败");
    }

    /**
     * 该 class 对 {@code org/luajvm/compiler/} 的全部引用是否只有 {@code Opcodes}。
     * 逐个引用点核对，任何其他 compiler 类（Parser/CodeGen/Lexer…）一出现即返回 false。
     */
    private static boolean onlyReferencesOpcodes(String body) {
        Matcher m = Pattern.compile("org/luajvm/compiler/([A-Za-z0-9_$]+)").matcher(body);
        boolean sawAny = false;
        while (m.find()) {
            sawAny = true;
            String cls = m.group(1).split(java.util.regex.Pattern.quote("$"))[0];
            if (!cls.equals("Opcodes")) return false;
        }
        return sawAny;
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

    private static Path classRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            Path c = p.resolve("luajvm-core/build/classes/java/main/org/luajvm");
            if (Files.isDirectory(c)) return c;
            p = p.getParent();
        }
        throw new IllegalStateException("luajvm-core class 输出未找到，先编译 :luajvm-core");
    }

    private CoreLayerContractTest() {
    }
}
