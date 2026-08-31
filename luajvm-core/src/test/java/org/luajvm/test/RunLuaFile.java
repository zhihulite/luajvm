// java-only: 单文件 Lua 执行器（LuaPlatform 宿主入口，模拟 lua.c 的 pmain）
package org.luajvm.test;

import org.luajvm.core.LuaDebug;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.lib.BaseLib;
import org.luajvm.lib.DebugHook;
import org.luajvm.vm.CFnCallStats;
import org.luajvm.vm.LuaPlatform;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.luajvm.bind.Platform;

/**
 * 通过 LuaPlatform 运行单个 Lua 文件。
 * <p>
 * 用法：RunLuaFile &lt;Lua 文件路径&gt; [超时秒数]
 */
public class RunLuaFile {
    static Globals g;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: RunLuaFile <path-to-lua-file> [timeout-seconds]");
            System.exit(2);
        }
        int timeout = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> run(args[0]));
        try {
            future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            System.err.println("TIMEOUT: " + args[0] + " (>" + timeout + "s)");
            System.exit(124);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } finally {
            executor.shutdownNow();
        }
    }

    static void run(String path) {
        try {
            // C Lua 测试使用相对路径，因此同步引擎的工作目录。
            File f = new File(path).getAbsoluteFile();
            File dir = f.getParentFile();
            // 官方聚合入口位于 official_tests，all.lua 位于其 lua-5.5.1-tests 子目录。
            // 运行包装入口时，工作目录必须切到实际套件目录。
            if (dir != null && "run.lua".equalsIgnoreCase(f.getName())) {
                File suiteDir = new File(dir, "lua-5.5.1-tests");
                if (new File(suiteDir, "all.lua").isFile()) dir = suiteDir;
            }
            if (dir != null) System.setProperty("user.dir", dir.getAbsolutePath());
            if (dir != null) BaseLib.setCwd(dir.getAbsolutePath());
            String src = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.ISO_8859_1);
            // java-only: -Dluajvm.luajava=true 时装 luajava（bind.Platform），供互操作微基准使用。
            g = Boolean.getBoolean("luajvm.luajava")
                    ? Platform.standardGlobals()
                    : LuaPlatform.standardGlobals();
            if (!Boolean.getBoolean("luajvm.disableLtests")) {
                LtestsDebugLib.open(g);
            }
            // Windows 上官方测试用 _port 跳过不可移植的文件系统和动态库测试。
            if (File.separatorChar == '\\') {
                g.set("_port", LuaValue.TRUE);
            }
            InputStream is = new ByteArrayInputStream(src.getBytes(StandardCharsets.ISO_8859_1));
            LuaValue f2 = g.compiler.compile(is, "@" + path, "bt", g);
            if (f2 instanceof LuaFunction fn) {
            // C：lua.c : main -> lua_pcall(pmain)；先补出 pmain 这层真实 C 帧。
                LuaCall.callNoYield(new HostPMain(fn), LuaValue.NONE);
            }

            System.out.println("OK: " + path);

            if (Boolean.getBoolean("luajvm.countcfn")) {
                CFnCallStats.printStats();
            }
        } catch (Throwable e) {
            if (Boolean.getBoolean("luajvm.printJavaStackTrace")) {
                e.printStackTrace(System.out);
            }
            StringBuilder sb = new StringBuilder();
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            ArrayList<Globals.DebugFrame> frames = null;
            if (e instanceof LuaError le && le.savedStack != null && !le.savedStack.isEmpty()) {
                frames = le.savedStack;
            }
            int errorLine = -1;
            if (frames != null) {
                Globals.DebugFrame topFrame = frames.get(0);
                if (topFrame.func instanceof LuaClosure lc) {
                    errorLine = LuaDebug.getFuncLinePub(lc.p, topFrame.pc);
                }
            }
            // 消息已自带位置时原样输出：判据只看首行。require 的加载失败消息是多行
            //   （"error loading module ... :\n\tbad.lua:1: ..."），整串匹配会漏判并
            //   再拼一次脚本路径，与 C 差一个前缀
            int nl = msg.indexOf('\n');
            String firstLine = nl < 0 ? msg : msg.substring(0, nl);
            if (firstLine.matches("^[^\\n]+:\\d+: .*$") || msg.startsWith("error loading module ")) {
                sb.append(msg).append("\n");
            } else if (errorLine > 0) {
                sb.append(path).append(":").append(errorLine).append(": ").append(msg).append("\n");
            } else {
                sb.append(path).append(":").append(msg).append("\n");
            }
            // 经 DebugHook.traceback 输出，与 lua.c 的 msghandler 同一实现：
            //   它带 namewhat 限定（"[C]: in global 'require'"）并对 source 走 chunkid
            //   去掉 '@' 前缀。runner 自建简化版会与引擎、与 C 三方不一致。
            if (frames != null && !frames.isEmpty()) {
                LuaThread errThread = g != null ? g.running : null;
                LuaValue tb = DebugHook.tracebackFromSnapshot(
                        errThread, frames, LuaValue.NIL, 0);
                if (tb.isstring()) sb.append(tb.toJavaString()).append("\n");
            }
            // 去掉末尾换行：sb 各段自带换行，println 再加一个会比 C 多出空行
            String out = sb.toString();
            while (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
            System.out.println(out);
            System.exit(1);
        }
    }

    private static void doCall(LuaFunction chunk) {
        // C：lua.c : docall -> lua_pushcfunction(msghandler) + lua_insert + lua_pcallk
        //  -  main chunk 调用点 L.top 比 pmain 帧顶多 1（msghandler 占位 + chunk）。
        // java diff: Java 缺这层占位则 main chunk 起始帧比 C 低 1 槽，cstack 的
        //   stack recovery 边界递归深度随之差 1 层；HostPMain 帧（func=1）内
        //   push 后由 poscall 恢复。
        LuaThread L = g != null ? g.running : null;
        if (L != null) {
            L.stack[L.top] = LuaValue.NIL;  // 占位槽（模拟 docall 的 msghandler）
            L.top++;
        }
        LuaCall.callNoYield(chunk, LuaValue.NONE);
    }

    private static final class HostPMain extends LuaFunction {
        private final LuaFunction chunk;

        HostPMain(LuaFunction chunk) {
            this.chunk = chunk;
        }

        @Override
        public Varargs call(Varargs args) {
            // C：lua.c : pmain -> handle_script -> docall
            doCall(chunk);
            return LuaValue.TRUE;
        }
    }
}
