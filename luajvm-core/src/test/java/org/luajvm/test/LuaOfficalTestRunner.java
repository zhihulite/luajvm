// java-only: Lua 官方测试套件聚合运行器（执行 all.lua，final OK 判定）
package org.luajvm.test;

import org.luajvm.compiler.Parser;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.lib.BaseLib;
import org.luajvm.vm.CFnCallStats;
import org.luajvm.vm.LuaPlatform;

import java.io.File;
import java.net.URL;

/**
 * Lua 官方测试运行器：不扫描目录，仅执行 official_tests/run.lua 聚合入口。
 * <p>
 * run.lua 负责加载并执行 lua-5.5.1-tests/all.lua（官方聚合套件，内部 dofile
 * 全部测试并输出 "final OK !!!"）。runner 仅装配环境：独立 Globals + 官方
 * usertests 模式（_U/_soft/_port/_nomsg）+ package.path（libs 辅助库）。
 * <p>
 * 入口文件相对路径基于引擎 cwd（BaseLib.setCwd = run.lua 所在目录）；
 * all.lua 内部 dofile('main.lua') 等相对路径由 run.lua 用环境注入重定义
 * loadfile/dofile 前缀到 lua-5.5.1-tests/ 子目录解决。
 */
public class LuaOfficalTestRunner {

    /**
     * 入口文件：official_tests/run.lua（相对 classpath 资源定位）
     */
    private static final String ENTRY_RESOURCE = "official_tests/run.lua";

    public static void main(String[] args) {
        if (Boolean.getBoolean("luajvm.countcompile")) {
            long __start = System.nanoTime();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                long wall = System.nanoTime() - __start;
                long comp = Parser.COMPILE_NANOS.get();
                System.err.println("PROBE_COMPILE calls=" + Parser.COMPILE_CALLS.get()
                        + " bytes=" + Parser.COMPILE_BYTES.get()
                        + " compileMs=" + comp / 1000000
                        + " wallMs=" + wall / 1000000
                        + String.format(" share=%.2f%%", comp * 100.0 / wall));
            }));
        }
        // 从 classpath 定位 official_tests/run.lua 真实目录（build/resources/test
        // 或 src 资源） - 引擎 cwd 必须指向资源实际位置，all.lua/attrib.lua 的
        // dofile/require 相对路径基于引擎 cwd 解析。
        String testDir;
        try {
            URL url = LuaOfficalTestRunner.class.getClassLoader().getResource(ENTRY_RESOURCE);
            testDir = new File(url.toURI()).getParentFile().getAbsolutePath();
        } catch (Exception e) {
            testDir = "official_tests";
        }
        String suiteDir = testDir + "/lua-5.5.1-tests"; // 官方套件子目录（含 all.lua）
        // java-only: 可选入口覆盖 - 诊断单个官方测试文件也需要同一套装配（含 ltests 的 T 库），
        //   RunLuaFile 不装 T，无法复现 `if T then` 段落。传绝对路径或相对套件目录的路径。
        String entryPath = args.length > 0 && !args[0].isBlank()
                ? (new File(args[0]).isAbsolute() ? args[0] : suiteDir + "/" + args[0])
                : testDir + "/run.lua";

        int passed = 0, failed = 0;
        try {
            Globals g = LuaPlatform.standardGlobals();
            // 引擎 cwd 直接设官方套件目录（对齐 C 在该目录跑 all.lua）—— loadfile
            // 前缀 workaround 会把 dofile(".luaj*.tmp") 拼错前缀致找不到
            BaseLib.setCwd(suiteDir);
            // 不设 _soft / _nomsg：完整强度运行，且不静默"未执行"提示。
            // 不设 _U：all.lua 在 _U 下把 T 置 nil，连带跳过大量 `if T then` 守卫段
            //   -> 聚合"部分通过"假象。
            // 保留 _port：它守的是"假定 POSIX 目录布局与动态库"的段落。依据是
            //   C 参照实现在本机去掉 _port 后同样失败、失败点完全相同
            //   （attrib.lua 期望值硬编码正斜杠而 Windows dirsep 是 '\'；
            //   main.lua 更早，os.tmpname() 即返回 Windows 无效路径）。
            g.set("_port", LuaValue.TRUE);
            // 提供 ltests 库 T（对齐单跑 RunLuaFile） - all.lua 大量 `if T then` 守卫段
            // 依赖它；缺失会静默跳过（聚合"部分通过"假象）
            LtestsDebugLib.open(g);
            // 设置 package.path：libs 辅助库 + 官方套件目录（require"tracegc"、
            // attrib.lua 的 require libs/err.lua 依赖）
            LuaValue pkg = g.get("package");
            if (!pkg.isnil()) {
                LuaValue pp = pkg.get("path");
                if (!pp.isnil()) {
                    String newPath = "libs/?.lua;?.lua;?;" + pp.toJavaString();
                    pkg.set("path", LuaString.valueOf(newPath));
                }
            }
            // 加载并执行聚合入口 run.lua
            Varargs loadResult = g.baselib.loadFile(entryPath, "bt", g);
            if (loadResult.arg1().isnil()) {
                System.out.println("FAIL: Cannot load " + entryPath + ": " + loadResult.arg(2).toJavaString());
                failed++;
            } else {
                Varargs pcallResult = LuaCall.callLua(g.get("pcall"), loadResult.arg1());
                if (pcallResult.arg1().toboolean()) {
                    System.out.println("\n>>> run.lua: PASS (final OK !!!)");
                    passed++;
                } else {
                    System.out.println("\n>>> run.lua: FAIL");
                    System.out.println("    Error: " + pcallResult.arg(2).toJavaString());
                    failed++;
                }
            }
        } catch (Exception e) {
            System.out.println("\n>>> run.lua: FAIL (Java exception)");
            System.out.println("    Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("    Caused by: " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage());
            }
            failed++;
        }

        System.out.println("\n========================================");
        System.out.println("FINAL SUMMARY: " + passed + " passed, " + failed + " failed");
        System.out.println("========================================");

        if (Boolean.getBoolean("luajvm.countcfn")) {
            CFnCallStats.printStats();
        }

        if (failed > 0) System.exit(1);
    }
}
