// ref: lstate.c / linit.c
// diff: bareGlobals=lua_newstate, standardGlobals+=luaL_openlibs
package org.luajvm.vm;

import org.luajvm.compiler.Parser;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Prototype;
import org.luajvm.core.UpVal;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.spi.LuaConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.ServiceLoader;
import org.luajvm.LuaStandard;

public final class LuaPlatform {
    private LuaPlatform() {
    }

    // lstate.c: lua_newstate
    public static Globals bareGlobals() {
        Globals g = new Globals();
        // java: 近似创建 LuaThread
        // java diff: Globals 构造已 registerGlobals，线程创建失败必须回滚注册，
        //   否则残留 Globals 累积在 activeGlobalsList，fullGC 的 markRoots 退化为 O(n)
        //   （memerr.lua testbytes newstate 循环挂起根因）。
        try {
            if (g.running == null) g.running = new LuaThread(g);
        } catch (RuntimeException | Error error) {
            LuaTable.unregisterGlobals(g);
            throw error;
        }
        g.compiler = (source, chunkname, mode, env) -> protectedParser(g, source, chunkname, mode);
        g.loader = LuaClosure::new;
        // java-only: 平台配置经 SPI 注入。宿主可在此之后直接改写 g.config 覆盖 SPI 值
        //   （ltests 就这么做），故这里只填默认来源，不做校验。
        g.config = ConfigSpiHolder.CONFIG;
        return g;
    }

    /**
     * SPI 加载的进程级共享 {@link LuaConfig}。
     *
     * <p>java-only: 惰性 holder 惯用法——类初始化由 JVM 保证线程安全且恰好一次，
     * 且只在首次访问时触发扫描。加载失败（如 Android 早期版本权限问题）时为 null，
     * 读取方一律按 {@code config == null} 回落到 C 默认值。
     */
    private static final class ConfigSpiHolder {
        static final LuaConfig CONFIG = load();

        private static LuaConfig load() {
            try {
                Iterator<LuaConfig> it = ServiceLoader.load(LuaConfig.class).iterator();
                if (it.hasNext()) return it.next();
            } catch (Throwable ignored) {
                // 扫描失败 -> null -> 调用方用 C 默认值
            }
            return null;
        }
    }

    // ldo.c: luaD_protectedparser
    private static LuaValue protectedParser(Globals g, InputStream source, String chunkname, String mode) {
        LuaThread L = g.running;
        int savedNny = L != null ? L.nny : 0;
        if (L != null) L.nny = savedNny + 1;
        try {
            return fParser(g, source, chunkname, mode);
        } finally {
            if (L != null) L.nny = savedNny;
        }
    }

    // java-only: 直接字节源 protectedParser - 跳过 InputStream->readAllBytes 的拷贝，
    //   用于 load(string) 热路径。语义等价于 protectedParser(g, new ByteArrayInputStream(data), ...)，
    //   但省去 InputStream 对象分配与 readAllBytes 的 byte[] 拷贝。
    public static LuaValue protectedParserBytes(Globals g, byte[] data, String chunkname, String mode) {
        LuaThread L = g.running;
        int savedNny = L != null ? L.nny : 0;
        if (L != null) L.nny = savedNny + 1;
        try {
            return fParserBytes(g, data, chunkname, mode);
        } finally {
            if (L != null) L.nny = savedNny;
        }
    }

    // ldo.c: f_parser
    private static LuaValue fParser(Globals g, InputStream source, String chunkname, String mode) {
        byte[] data;
        try {
            data = source.readAllBytes();
        } catch (IOException e) {
            LuaErrors.error("I/O error reading chunk: " + e.getMessage());
            return LuaValue.NIL;
        }
        return fParserBytes(g, data, chunkname, mode);
    }

    // java-only: f_parser 的字节源核心 - InputStream 路径和 byte[] 路径共用
    /**
     * java-only：把 undump 出来的原型树的 source 全部换成运行期 chunkname。
     *
     * <p>预编译加载（{@code -Dluajvm.luac}）要求 {@code source} 与走源码路径逐字一致  -
     * 源码路径存的是调用方传入的完整路径，而 dump 时存的是编译那台机器上的路径，
     * 运行期路径在编译期未知。不统一则 {@code debug.getinfo().source} 与 traceback
     * 文本会随"是否命中 .luac"而变化。
     *
     * <p>必须递归：{@code debug.getinfo} 查的多是嵌套函数（模块里的 {@code M.add}），
     * 仅改顶层会漏。对齐 C 的 {@code lundump.c:loadFunction}（父 source 传给子）。
     */
    private static void retagSource(Prototype p, LuaString src) {
        p.source = src;
        if (p.p != null) {
            for (Prototype child : p.p) {
                if (child != null) retagSource(child, src);
            }
        }
    }

    private static LuaValue fParserBytes(Globals g, byte[] data, String chunkname, String mode) {
        boolean modeHasBinary = (mode == null) || mode.indexOf('b') >= 0 || mode.isEmpty();
        boolean modeHasText = (mode == null) || mode.indexOf('t') >= 0 || mode.isEmpty();
        boolean fileLoad = chunkname != null && (chunkname.startsWith("@") || chunkname.equals("=stdin"));
        ChunkPrefix prefix = fileLoad ? preprocessFileChunk(data) : new ChunkPrefix(0, false);
        int first = prefix.index < data.length ? (data[prefix.index] & 0xFF) : -1;
        if (first == 0x1B) {
            if (!modeHasBinary) {
                LuaErrors.checkModeError(mode, "binary");
            }
            Prototype p = LuaChunk.undump(slice(data, prefix.index, data.length), chunkname);
            // java-only: 预编译加载（-Dluajvm.luac）要求 source 与源码路径一致，理由见 retagSource 的 Javadoc。
            // [为何安全]仅 '@' 开头的 chunkname（loadFile 拼出的、与源码路径逐字相同的值）才覆盖；
            //   string.dump 再 load 的 chunkname 不以 '@' 开头（或 null），保持 dump 时 source，与 C 一致。
            if (chunkname != null && chunkname.startsWith("@")) {
                // 递归到所有子原型，漏改会让嵌套函数留着编译期路径；
                // C 的 lundump.c:loadFunction 同样把父 source 传给 source==NULL 的子。
                retagSource(p, LuaString.newStr(chunkname));
            }
            return newLuaClosure(p, g);
        }
        if (!modeHasText) {
            LuaErrors.checkModeError(mode, "text");
        }
        // java: 直接字节源 Lexer 快路径，跳过 ByteChunkReader 分配与 per-char 虚方法 dispatch
        boolean compatGlobal = g.config == null || g.config.compatGlobal();
        Prototype p = Parser.parse(data, prefix.index(), data.length - prefix.index(), prefix.skippedComment(), chunkname, compatGlobal);
        return newLuaClosure(p, g);
    }

    // linit.c: luaL_openlibs  -  装配实现在 org.luajvm.LuaStandard（见其类注释）。
    // java-only: 本方法是转发入口，供测试与宿主调用；装配不放在 vm 包内，
    //   使 vm 不依赖 lib 的库类（对齐 C 的 lvm.c 不 include l*lib.c）。
    public static Globals standardGlobals() {
        return LuaStandard.standardGlobals();
    }

    // lfunc.c: luaF_initupvals  -  fill a closure with new closed upvalues
    // C: luaC_newobj(L, LUA_VUPVAL, sizeof(UpVal)) + luaC_objbarrier(L, cl, uv)
    // java diff: UpVal 不是 GC 对象，无需 luaC_newobj/luaC_objbarrier（JVM GC 自动处理引用）
    // java diff: cl.upvals[0] 设为 new UpVal(g) 指向 Globals（_ENV upvalue）
    private static LuaClosure newLuaClosure(Prototype p, Globals g) {
        LuaClosure cl = new LuaClosure(p, g);
        for (int i = 0; i < cl.upvals.length; i++) {
            cl.upvals[i] = UpVal.closedOf(LuaValue.NIL);
        }
        if (cl.upvals.length > 0) {
            cl.upvals[0] = new UpVal(g);
        }
        return cl;
    }

    // lbaselib.c: luaB_load
    private static ChunkPrefix preprocessFileChunk(byte[] data) {
        int i = 0;
        if (data.length >= 3 &&
                (data[0] & 0xFF) == 0xEF &&
                (data[1] & 0xFF) == 0xBB &&
                (data[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        if (i < data.length && data[i] == '#') {
            while (i < data.length && data[i] != '\n') i++;
            if (i < data.length) i++;
            return new ChunkPrefix(i, true);
        }
        return new ChunkPrefix(i, false);
    }


    private static byte[] slice(byte[] data, int from, int to) {
        int len = Math.max(0, to - from);
        byte[] out = new byte[len];
        System.arraycopy(data, from, out, 0, len);
        return out;
    }

    // lbaselib.c: luaB_load
    public static Varargs execute(Globals g, String source) {
        try {
            InputStream is = new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
            LuaValue f = g.compiler.compile(is, "test", "bt", g);
            if (f instanceof LuaFunction fn) return LuaCall.callLua(fn);
        } catch (Exception e) {
            LuaErrors.error(e.getMessage());
        }
        return LuaValue.NONE;
    }

    // lua.c: pmain
    public static byte[] compileToChunk(String source, boolean strip) {
        try {
            InputStream is = new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
            Prototype p = Parser.parse(new InputStreamReader(is, StandardCharsets.ISO_8859_1), "chunk");
            return LuaChunk.dump(p, strip);
        } catch (Exception e) {
            LuaErrors.error(e.getMessage());
        }
        return new byte[0];
    }

    // lbaselib.c: luaB_load
    public static Varargs executeChunk(Globals g, byte[] chunk) {
        try {
            Prototype p = LuaChunk.undump(chunk);
            LuaClosure fn = newLuaClosure(p, g);
            return LuaCall.callLua(fn);
        } catch (Exception e) {
            LuaErrors.error(e.getMessage());
        }
        return LuaValue.NONE;
    }

    private record ChunkPrefix(int index, boolean skippedComment) {
    }

    // java: 字节级 Reader，映射 0..255
    private static final class ByteChunkReader extends Reader {
        private final byte[] data;
        private final int end;
        private int index;
        private boolean pendingNewline;

        ByteChunkReader(byte[] data, ChunkPrefix prefix) {
            this.data = data;
            this.index = prefix.index();
            this.end = data.length;
            this.pendingNewline = prefix.skippedComment();
        }

        @Override
        public int read() {
            if (pendingNewline) {
                pendingNewline = false;
                return '\n';
            }
            return index < end ? data[index++] & 0xFF : -1;
        }

        @Override
        public int read(char[] cbuf, int off, int len) {
            if (len == 0) return 0;
            int n = 0;
            if (pendingNewline) {
                cbuf[off++] = '\n';
                len--;
                n++;
                pendingNewline = false;
            }
            int take = Math.min(len, end - index);
            for (int i = 0; i < take; i++) {
                cbuf[off + i] = (char) (data[index + i] & 0xFF);
            }
            index += take;
            n += take;
            return n > 0 ? n : -1;
        }

        @Override
        public void close() {
        }
    }
}
