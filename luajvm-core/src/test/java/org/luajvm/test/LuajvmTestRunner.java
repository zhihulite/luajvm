// java-only: luajvm_tests 聚合运行器（执行 run.lua，装配 luajava 宿主扩展）
package org.luajvm.test;

import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.lib.BaseLib;

import java.io.File;
import java.net.URL;

/**
 * luajvm_tests 运行器 - 不扫描目录，仅执行 luajvm_tests/run.lua 聚合入口。
 * <p>
 * run.lua 负责逐个执行 tests/ 下的 Java 绑定测试并统计（失败不中断，
 * 整体失败以 error 结束）。runner 仅装配环境：
 * <b>必须使用 org.luaj.bind.Platform.standardGlobals()</b> - 它在标准 Lua 库之外
 * 追加装配 luajava 宿主扩展库；LuaPlatform.standardGlobals() 仅装载标准库。
 * 引擎 cwd 设为 run.lua 所在目录（luajvm_tests/），run.lua 内 dofile("tests/xx.lua")
 * 与 require("_fixtures.*") 基于此解析。
 */
public class LuajvmTestRunner {

    /**
     * 入口文件：luajvm_tests/run.lua（相对 classpath 资源定位）
     */
    private static final String ENTRY_RESOURCE = "luajvm_tests/run.lua";

    public static void main(String[] args) {
        // 从 classpath 定位 luajvm_tests/run.lua 真实目录（build/resources/test 或 src 资源）
        String testDir;
        try {
            URL url = LuajvmTestRunner.class.getClassLoader().getResource(ENTRY_RESOURCE);
            testDir = new File(url.toURI()).getParentFile().getAbsolutePath();
        } catch (Exception e) {
            testDir = "luajvm_tests";
        }
        String entryPath = testDir + "/run.lua";

        int passed = 0, failed = 0;
        String testFile = "run.lua";
        try {
            // java-only: 必须用 Platform.standardGlobals() - 追加 luajava 宿主扩展库
            Globals g = Platform.standardGlobals();
            BaseLib.setCwd(testDir);

            // 加载聚合入口（baselib.loadFile 对齐官方 runner）
            Varargs loadResult = g.baselib.loadFile(entryPath, "bt", g);
            if (loadResult.arg1().isnil()) {
                System.out.println(">>> " + testFile + ": ERROR  -  loadfile failed: "
                        + loadResult.arg(2).toJavaString());
                failed++;
            } else {
                Varargs pcallResult = LuaCall.callLua(g.get("pcall"), loadResult.arg1());
                if (pcallResult.arg1().toboolean()) {
                    System.out.println(">>> " + testFile + ": PASS");
                    passed++;
                } else {
                    System.out.println(">>> " + testFile + ": FAIL  -  "
                            + pcallResult.arg(2).toJavaString());
                    failed++;
                }
            }
        } catch (Exception e) {
            System.out.println(">>> " + testFile + ": ERROR  -  " + e);
            failed++;
        }
        System.out.println("\nFINAL SUMMARY: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
