// java-only: Java API 合约测试（chunk dump/load round-trip）
package org.luajvm.test;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.vm.LuaPlatform;

import java.io.ByteArrayInputStream;

/**
 * Java API 合约测试：Lua 5.5 二进制 chunk dump/load round-trip。
 * <p>
 * 覆盖：compileToChunk/executeChunk、Lua 5.5 chunk 头（0x1B 4C 75 61 + 0x55 版本）、
 * strip 行为。全部使用内存流（Android 无 /tmp）。
 */
public class ChunkTest {
    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("=== Lua Chunk Dump/Load Test ===\n");

        // 测试 1: dump/load round trip（compileToChunk/executeChunk）
        testRoundTrip("return 42", "42");
        testRoundTrip("return 1 + 2", "3");
        testRoundTrip("return \"hello\" .. \" \" .. \"world\"", "hello world");
        testRoundTrip("local s = 0\nfor i=1,5 do s = s + i end\nreturn s", "15");
        testRoundTrip("return 2 ^ 3", "8.0");
        testRoundTrip("return 10 // 3", "3");
        testRoundTrip("return 1 << 4", "16");

        // 测试 2: compile -> 内存流二进制 chunk -> 内存流 load -> 执行
        try {
            byte[] chunk = LuaPlatform.compileToChunk("return 5 * 5", false);
            System.out.println("  chunk size: " + chunk.length + " bytes");
            Varargs result = LuaPlatform.executeChunk(LuaPlatform.standardGlobals(), chunk);
            checkEquals("compile/execute round-trip", "25", result.arg1().toJavaString());

            // 内存流 load（compiler 自动识别 0x1B 二进制 chunk 前缀）
            Globals g = LuaPlatform.standardGlobals();
            LuaValue fn = g.compiler.compile(new ByteArrayInputStream(chunk), "test_chunk", "bt", g);
            if (fn instanceof LuaFunction lf) {
                Varargs r = LuaCall.callLua(lf);
                checkEquals("load from memory stream via compiler", "25", r.arg1().toJavaString());
            } else {
                System.err.println("[FAIL] compile from memory stream did not return function");
                failed++;
            }
        } catch (Exception e) {
            System.err.println("[FAIL] memory-stream round-trip: " + e.getMessage());
            failed++;
        }

        // 测试 3: strip vs no-strip
        byte[] full = LuaPlatform.compileToChunk("return 1+2", false);
        byte[] stripped = LuaPlatform.compileToChunk("return 1+2", true);
        System.out.println("\n  full chunk: " + full.length + " bytes");
        System.out.println("  stripped chunk: " + stripped.length + " bytes");
        if (stripped.length < full.length) {
            System.out.println("[OK] stripped chunk is smaller");
            passed++;
        } else {
            System.err.println("[FAIL] stripped chunk should be smaller");
            failed++;
        }
        // strip 后仍可执行
        try {
            Varargs r = LuaPlatform.executeChunk(LuaPlatform.standardGlobals(), stripped);
            checkEquals("stripped chunk executes", "3", r.arg1().toJavaString());
        } catch (Exception e) {
            System.err.println("[FAIL] stripped chunk execution: " + e.getMessage());
            failed++;
        }

        // 测试 4: Lua 5.5 chunk 头
        byte[] c = LuaPlatform.compileToChunk("return 1", false);
        if (c[0] == 0x1B && c[1] == 'L' && c[2] == 'u' && c[3] == 'a') {
            System.out.println("[OK] signature '\\x1bLua' correct");
            passed++;
        } else {
            System.err.println("[FAIL] signature wrong: " + String.format("%02x %02x %02x %02x",
                    c[0], c[1], c[2], c[3]));
            failed++;
        }
        if (c[4] == 0x55) {
            System.out.println("[OK] LUAC_VERSION = 0x55 (Lua 5.5)");
            passed++;
        } else {
            System.err.println("[FAIL] LUAC_VERSION wrong: " + String.format("%02x", c[4]));
            failed++;
        }

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) System.exit(1);
    }

    static void testRoundTrip(String src, String expected) {
        try {
            // 1. compile source -> execute（baseline）
            Globals g = LuaPlatform.standardGlobals();
            Varargs baseline = LuaPlatform.execute(g, src);
            String baseStr = baseline.arg1().toJavaString();

            // 2. compileToChunk -> executeChunk（round-trip）
            byte[] chunk = LuaPlatform.compileToChunk(src, false);
            Varargs result = LuaPlatform.executeChunk(g, chunk);
            String actual = result.arg1().toJavaString();

            if (actual.equals(expected) && baseStr.equals(expected)) {
                System.out.println("[OK] " + src.substring(0, Math.min(40, src.length())).replace('\n', ' '));
                passed++;
            } else {
                System.err.println("[FAIL] " + src.substring(0, Math.min(40, src.length())).replace('\n', ' ')
                        + " expected=" + expected + " baseline=" + baseStr + " roundtrip=" + actual);
                failed++;
            }
        } catch (Exception e) {
            System.err.println("[FAIL] " + src.substring(0, Math.min(40, src.length())).replace('\n', ' ')
                    + " => " + e.getClass().getSimpleName() + ": " + e.getMessage());
            failed++;
        }
    }

    static void checkEquals(String name, String expected, String actual) {
        if (actual.equals(expected)) {
            System.out.println("[OK] " + name);
            passed++;
        } else {
            System.err.println("[FAIL] " + name + " expected=" + expected + " actual=" + actual);
            failed++;
        }
    }
}
