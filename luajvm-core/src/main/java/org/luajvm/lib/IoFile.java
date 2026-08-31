// ref: liolib.c
// diff: RandomAccessFile/InputStream/OutputStream 替代 FILE*；手动写缓冲替代 setvbuf；正则解析数字替代 luaO_str2num；静态列表跟踪句柄
package org.luajvm.lib;

import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IoFile {
    // C：luaconf.h:LUAL_BUFFERSIZE（默认 16*sizeof(void*)*sizeof(lua_Number)=1024，测试版
    //   ltests.h 覆盖为 23），liolib.c:f_setvbuf
    // java diff: C 编译期 #define -> Java 系统属性可配置（-Dluajvm.buffersize=23 对齐测试版）
    public static final int LUAL_BUFFERSIZE =
            Integer.getInteger("luajvm.buffersize", 16 * 8 * 8);
    private static final Pattern LUA_NUMBER_PREFIX = Pattern.compile(
            "[+-]?(?:(?:0[xX](?:(?:[0-9a-fA-F]+(?:\\.[0-9a-fA-F]*)?)|(?:\\.[0-9a-fA-F]+))(?:[pP][+-]?[0-9]+)?)|(?:(?:[0-9]+(?:\\.[0-9]*)?)|(?:\\.[0-9]+))(?:[eE][+-]?[0-9]+)?)");
    /**
     * 当前**打开**的文件句柄。
     *
     * <p>C 无对应结构：C 的 {@code LStream} 就是普通 userdata，由 Lua GC 释放
     * （{@code liolib.c : f_gc} -> {@code aux_close}）。本表是 Java 独有的，仅为
     * {@link #closeHandlesForName} 服务 - Windows 不允许删除/改名仍被打开的文件，
     * 而 {@code os.remove}/{@code os.rename} 必须能先关掉它们。
     *
     * <p>仅登记打开的句柄：{@link IoFileHandle#close()} 摘除自己（被丢弃未显式关闭的
     * 句柄由 {@code __gc}/{@code __close} 关闭，与 C 同，同样摘除）。
     *
     * <p><b>不登记标准流</b>：见 {@link #trackOpen}。
     *
     * <p>{@code synchronized} 保护：{@code Globals} 可在任意线程创建并打开文件，
     * 而本表是进程级的（文件系统本身就是进程级资源，与 C 的 fd 表同层），
     * 裸 {@code ArrayList} 的并发 add 属真实竞争。
     */
    private static final ArrayList<IoFileHandle> openHandles = new ArrayList<>();

    private IoFile() {
    }

    /**
     * 登记打开的句柄。标准流不登记 - 它们对本表唯一的消费者
     * {@link #closeHandlesForName} 无意义（那里显式跳过 {@code isStdFile()}），
     * 而 {@link IoFileHandle#close()} 对标准流直接返回错误、从不摘除自己。
     */
    private static void trackOpen(IoFileHandle h) {
        if (h.isStdFile()) return;
        synchronized (openHandles) {
            openHandles.add(h);
        }
    }

    private static void untrack(IoFileHandle h) {
        synchronized (openHandles) {
            // 反向查找：刚打开的句柄最可能最先关闭
            for (int i = openHandles.size() - 1; i >= 0; i--) {
                if (openHandles.get(i) == h) {
                    openHandles.remove(i);
                    return;
                }
            }
        }
    }

    /** java-only 诊断：当前打开的句柄数（供 {@code IoHandleLeakTest} 断言有界）。 */
    public static int openHandleCount() {
        synchronized (openHandles) {
            return openHandles.size();
        }
    }

    // java-only
    public static void closeHandlesForName(String name) {
        IoFileHandle[] snapshot;
        synchronized (openHandles) {
            snapshot = openHandles.toArray(new IoFileHandle[0]);
        }
        for (IoFileHandle h : snapshot) {
            if (h != null && !h.isStdFile() && h.name != null && h.name.equals(name)) {
                try {
                    h.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // liolib.c: luaL_Stream
    public static class IoFileHandle {
        public InputStream in;
        public OutputStream out;
        public RandomAccessFile raf;
        public String name;
        public int ftype; // 0=stdin,1=stdout,2=stderr,3=命名文件
        public boolean readable;
        public boolean writable;
        public boolean fullDevice;
        public Process process;
        private String bufferMode = "no";
        private int bufferSize = LUAL_BUFFERSIZE;
        private byte[] writeBuffer = new byte[LUAL_BUFFERSIZE];
        private int writeBufferSize;
        // java-only: 读缓冲减少逐字节 JNI 调用（对齐 C stdio 缓冲）
        private final byte[] readBuffer = new byte[LUAL_BUFFERSIZE];
        private int readBufferPos;
        private int readBufferLen;

        public IoFileHandle(InputStream in, OutputStream out, String name, int ftype) {
            this.in = in;
            this.out = out;
            this.name = name;
            this.ftype = ftype;
            this.readable = in != null;
            this.writable = out != null;
            initDefaultBuffering();
            trackOpen(this);
        }

        public IoFileHandle(RandomAccessFile raf, String name, int ftype, boolean readable, boolean writable) {
            this.raf = raf;
            this.name = name;
            this.ftype = ftype;
            this.readable = readable;
            this.writable = writable;
            initDefaultBuffering();
            trackOpen(this);
        }

        // java-only: 快速空白检查，对齐 "C" locale 下 C 的 isspace
        private static boolean isSpaceByte(int c) {
            return c == ' ' || (c >= '\t' && c <= '\r');
        }

        // java-only
        public boolean isStdFile() {
            return ftype <= 2;
        }

        // java-only
        public boolean isClosed() {
            return !isStdFile() && raf == null && in == null && out == null;
        }

        private void initDefaultBuffering() {
            if (!isStdFile() && writable) bufferMode = "full";
        }

        // liolib.c: aux_close
        public Varargs close() throws IOException {
            if (isStdFile()) {
                return LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr("cannot close standard file"));
            }
            if (isClosed()) return LuaErrors.error("attempt to use a closed file");
            flushWriteBuffer();
            if (raf != null) raf.close();
            if (in != null) in.close();
            if (out != null) out.close();
            Varargs processResult = null;
            if (process != null) {
                try {
                    processResult = OsLib.execResult(process.waitFor());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    processResult = LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr(e.getMessage()), LuaInteger.valueOf(1));
                }
                process = null;
            }
            raf = null;
            in = null;
            out = null;
            readable = false;
            writable = false;
            // 已关闭的句柄对 closeHandlesForName 无用，留在表里是纯泄漏（见 openHandles）
            untrack(this);
            return processResult != null ? processResult : LuaValue.TRUE;
        }

        // liolib.c: f_flush
        public Varargs flush() throws IOException {
            if (fullDevice)
                return LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr("No space left on device"), LuaInteger.valueOf(28));
            flushWriteBuffer();
            if (out != null) out.flush();
            return LuaValue.TRUE;
        }

        // liolib.c: g_read
        public Varargs read(Varargs args) throws IOException {
            if (!readable)
                return LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr("file is not open for reading"), LuaInteger.valueOf(2));
            int n = args.narg();
            if (n == 0) {
                return readLine(false);
            }
            LuaValue[] results = new LuaValue[n];
            for (int i = 1; i <= n; i++) {
                LuaValue arg = args.arg(i);
                if (arg.isnumber()) {
                    int len = arg.checkint();
                    if (len == 0) {
                        results[i - 1] = isEof() ? LuaValue.NIL : LuaString.newStr("");
                        continue;
                    }
                    byte[] buf = new byte[len];
                    int read = readBytes(buf);
                    results[i - 1] = read >= 0 ? LuaString.newLstr(buf, 0, Math.max(read, 0)) : LuaValue.NIL;
                    continue;
                }

                String mode = arg.optJavaString("l");
                if (mode.startsWith("*")) mode = mode.substring(1);
                mode = switch (mode) {
                    case "all" -> "a";
                    case "line" -> "l";
                    case "number" -> "n";
                    default -> mode;
                };
                switch (mode) {
                    case "l" -> results[i - 1] = readLine(false);
                    case "L" -> results[i - 1] = readLine(true);
                    case "a" -> {
                        byte[] all = readAllRemaining();
                        results[i - 1] = LuaString.newLstr(all, 0, all.length);
                    }
                    case "n" -> results[i - 1] = readNumber();
                    default -> {
                        LuaErrors.argError(i, "invalid format");
                        results[i - 1] = LuaValue.NIL;
                    }
                }
            }
            return results.length == 1 ? results[0] : LuaValue.varargsOf(results);
        }

        // liolib.c: g_write
        // java diff: C 的 g_write(L, f, arg) 第三参是【绝对栈索引】起点 —— io.write 传 1、
        //   f:write 传 2（self 占 #1）。Java 侧 f_write 已 subargs(2) 剥掉 self，故 args
        //   内下标恒从 1 起；argBase 把报错序号还原成 C 的口径（否则 f:write({}) 报 #1 而 C 报 #2）。
        public Varargs write(Varargs args) throws IOException {
            return write(args, 1);
        }

        public Varargs write(Varargs args, int argBase) throws IOException {
            if (!writable)
                return LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr("file is not open for writing"), LuaInteger.valueOf(2));
            for (int i = 1, n = args.narg(); i <= n; i++) {
                LuaValue arg = args.arg(i);
                if (!arg.isstring() && !arg.isnumber()) {
                    LuaErrors.argError(i + argBase - 1, "string expected, got " + arg.typeName());
                }
                LuaString s = arg.strValue();
                byte[] bytes = s.bytes();
                writeBuffered(bytes);
            }
            return LuaValue.TRUE;
        }

        // liolib.c: f_seek
        public Varargs seek(String whence, int offset) throws IOException {
            flushWriteBuffer();
            invalidateReadBuffer();  // java-only: discard read-ahead so raf position = logical position
            if (raf != null) {
                long pos = switch (whence != null ? whence : "cur") {
                    case "set" -> offset;
                    case "cur" -> raf.getFilePointer() + offset;
                    case "end" -> raf.length() + offset;
                    default -> offset;
                };
                raf.seek(pos);
                return LuaInteger.valueOf(raf.getFilePointer());
            }
            if (!(in instanceof SeekableInputStream sis))
                return LuaErrors.error("stream is not seekable");
            int pos = switch (whence != null ? whence : "cur") {
                case "set" -> offset;
                case "cur" -> (int) (sis.position()) + offset;
                case "end" -> (int) (sis.size()) + offset;
                default -> offset;
            };
            sis.seek(pos);
            return LuaInteger.valueOf(sis.position());
        }

        // liolib.c: f_setvbuf
        public Varargs setvbuf(String mode, int size) throws IOException {
            if (!"no".equals(mode) && !"full".equals(mode) && !"line".equals(mode))
                LuaErrors.argError(1, "invalid option");
            flushWriteBuffer();
            invalidateReadBuffer();  // java-only: discard read buffer before changing buffer config
            bufferMode = mode;
            bufferSize = Math.max(1, size);
            if (writeBuffer.length != bufferSize) {
                writeBuffer = new byte[bufferSize];
            }
            return LuaValue.TRUE;
        }

        // liolib.c: f_lines
        public Varargs lines() {
            return new LinesIterator(this);
        }

        // liolib.c: read_line
        LuaValue readLine() throws IOException {
            return readLine(false);
        }

        // liolib.c: read_line
        // java diff: 快路径直接在读缓冲中扫 '\n'，避免逐字节 readByte()
        LuaValue readLine(boolean keepNewline) throws IOException {
            // 快路径：在当前读缓冲中扫 '\n'
            int start = readBufferPos;
            int nl = -1;
            for (int i = start; i < readBufferLen; i++) {
                if (readBuffer[i] == '\n') {
                    nl = i;
                    break;
                }
            }
            if (nl >= 0) {
                int contentLen = nl - start;
                int resultLen = keepNewline ? contentLen + 1 : contentLen;
                byte[] bytes = new byte[resultLen];
                System.arraycopy(readBuffer, start, bytes, 0, resultLen);
                readBufferPos = nl + 1;
                return LuaString.valueOfOwned(bytes);
            }
            // 慢路径：当前缓冲无 '\n'，跨多次填充累积
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // 复制剩余缓冲内容
            if (start < readBufferLen) {
                baos.write(readBuffer, start, readBufferLen - start);
                readBufferPos = readBufferLen;
            }
            int c;
            while ((c = readByte()) >= 0) {
                if (c == '\n') {
                    if (keepNewline) baos.write(c);
                    return LuaString.valueOfOwned(baos.toByteArray());
                }
                baos.write(c);
            }
            return baos.size() > 0 ? LuaString.valueOfOwned(baos.toByteArray()) : LuaValue.NIL;
        }

        // java-only: 直接从读缓冲解析简单整数（可选符号 + 全部数字）
        // 非简单整数返回 null，调用方回退通用解析器
        private LuaValue tryParseIntFromBuffer(int tokenStart, int tokenLen) {
            int p = tokenStart;
            int end = tokenStart + tokenLen;
            boolean neg = false;
            byte b0 = readBuffer[p];
            if (b0 == '-') {
                neg = true;
                p++;
            } else if (b0 == '+') {
                p++;
            }
            if (p >= end) return null;  // just a sign
            long val = 0;
            while (p < end) {
                byte b = readBuffer[p++];
                if (b < '0' || b > '9') return null;  // not a simple integer
                long digit = b - '0';
                // 溢出检查：val * 10 + digit > Long.MAX_VALUE
                if (val > (Long.MAX_VALUE - digit) / 10) return null;  // overflow, use float
                val = val * 10 + digit;
            }
            return LuaInteger.valueOf(neg ? -val : val);
        }

        // java-only: 用 Double.parseDouble 直接从读缓冲解析十进制浮点，失败返回 null 回退通用解析器。
        //   不处理十六进制浮点（0x...p...），交调用方通用路径；对齐 "C" locale 下 C 的
        //   l_str2d（strtod）十进制浮点，并拒绝 NaN/Infinity（Double.parseDouble 接受
        //   "NaN"/"Infinity"，C 的 luaO_str2num 在 "C" locale 不接受）。
        private LuaValue tryParseDoubleFromBuffer(int tokenStart, int tokenLen) {
            int p = tokenStart;
            int end = tokenStart + tokenLen;
            byte b0 = readBuffer[p];
            if (b0 == '-' || b0 == '+') p++;
            if (p >= end) return null;  // just a sign
            // 拒绝十六进制前缀  -  Double.parseDouble 不解析 0x 浮点，交给通用路径
            if (p + 1 < end && readBuffer[p] == '0'
                    && (readBuffer[p + 1] == 'x' || readBuffer[p + 1] == 'X')) {
                return null;
            }
            // 扫描记号：数字、可选单个 '.'、可选 e/E + 可选符号 + 必需数字
            boolean anyDigit = false;
            boolean anyDot = false;
            boolean hasExp = false;
            while (p < end) {
                byte b = readBuffer[p++];
                if (b >= '0' && b <= '9') {
                    anyDigit = true;
                    continue;
                }
                if (b == '.' && !anyDot && !hasExp) {
                    anyDot = true;
                    continue;
                }
                if ((b == 'e' || b == 'E') && anyDigit && !hasExp) {
                    hasExp = true;
                    // e/E 后的可选符号
                    if (p < end && (readBuffer[p] == '+' || readBuffer[p] == '-')) p++;
                    // 指数至少一位数字（对齐 strtod/C 的 l_str2d）
                    if (p >= end || readBuffer[p] < '0' || readBuffer[p] > '9') return null;
                    continue;
                }
                return null;  // invalid char or malformed structure
            }
            if (!anyDigit) return null;
            // 一次构造 String，比通用路径的 LuaString + scannumber 多趟便宜
            String s = new String(readBuffer, tokenStart, tokenLen, StandardCharsets.ISO_8859_1);
            try {
                double d = Double.parseDouble(s);
                // 拒绝 NaN/Infinity（扫描器已拒绝 'N'/'I'，但防御性保留）
                if (Double.isNaN(d) || Double.isInfinite(d)) return null;
                return LuaFloat.valueOf(d);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // liolib.c: read_number
        // java diff: 快路径直接扫读缓冲 + 先试 tonumber 再 regex
        private LuaValue readNumber() throws IOException {
            // 跳过空白（快速字节检查）
            int c;
            do {
                c = readByte();
            } while (c >= 0 && isSpaceByte(c));
            if (c < 0) return LuaValue.NIL;
            // 快路径：在当前读缓冲中扫描记号
            int tokenStart = readBufferPos - 1;  // c's position
            int tokenEnd = tokenStart + 1;
            while (tokenEnd < readBufferLen && !isSpaceByte(readBuffer[tokenEnd] & 0xFF)) {
                tokenEnd++;
            }
            if (tokenEnd < readBufferLen) {
                // 记号完全在缓冲内
                int tokenLen = tokenEnd - tokenStart;
                if (tokenLen > 0 && tokenLen <= 200) {
                    // 超快路径：简单整数解析（零对象创建）
                    LuaValue n = tryParseIntFromBuffer(tokenStart, tokenLen);
                    if (n == null) {
                        // 浮点快路径：非十六进制记号试 Double.parseDouble，
                        // 避免 LuaString 创建 + scannumber 多次扫描开销
                        n = tryParseDoubleFromBuffer(tokenStart, tokenLen);
                    }
                    if (n == null) {
                        // 从缓冲走通用 tonumber（处理十六进制、边界情形）
                        n = LuaString.newLstr(readBuffer, tokenStart, tokenLen).tonumber();
                    }
                    if (!n.isnil()) {
                        readBufferPos = tokenEnd;
                        return n;
                    }
                }
                // 快解析失败，走带 regex 的慢路径
                String token = new String(readBuffer, tokenStart, tokenLen, StandardCharsets.ISO_8859_1);
                readBufferPos = tokenEnd;
                return readNumberFromToken(token);
            }
            // 慢路径：记号跨过当前缓冲边界
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(c);
            // 排空剩余缓冲
            while (readBufferPos < readBufferLen) {
                baos.write(readBuffer[readBufferPos++]);
            }
            // 继续从文件读取
            while (true) {
                c = readByte();
                if (c < 0) break;
                if (isSpaceByte(c)) {
                    unreadByte();
                    break;
                }
                baos.write(c);
            }
            String token = baos.toString(StandardCharsets.ISO_8859_1);
            return readNumberFromToken(token);
        }

        // java-only: readNumber 的 regex 慢路径（处理边界情形）
        private LuaValue readNumberFromToken(String token) throws IOException {
            int invalidPrefix = invalidForcedNumberPrefix(token);
            if (invalidPrefix > 0) {
                unreadBytes(token.length() - invalidPrefix);
                return LuaValue.NIL;
            }
            Matcher matcher = LUA_NUMBER_PREFIX.matcher(token);
            if (matcher.lookingAt()) {
                int len = matcher.end();
                if (len > 200) {
                    unreadBytes(token.length() - 200);
                    return LuaValue.NIL;
                }
                LuaValue n = LuaString.newStr(token.substring(0, len)).tonumber();
                if (!n.isnil()) {
                    unreadBytes(token.length() - len);
                    return n;
                }
            }
            int consume = invalidNumberConsume(token);
            unreadBytes(token.length() - consume);
            return LuaValue.NIL;
        }

        // java-only
        private int invalidForcedNumberPrefix(String token) {
            int i = 0;
            if (i < token.length() && (token.charAt(i) == '+' || token.charAt(i) == '-')) i++;
            if (i + 1 < token.length() && token.charAt(i) == '0' && (token.charAt(i + 1) == 'x' || token.charAt(i + 1) == 'X') &&
                    (i + 2 >= token.length() || hexDigit(token.charAt(i + 2)) < 0)) {
                return i + 2;
            }
            int j = i;
            while (j < token.length() && Character.isDigit(token.charAt(j))) j++;
            if (j > i && j + 1 < token.length() && token.charAt(j) == '.' &&
                    (token.charAt(j + 1) == 'e' || token.charAt(j + 1) == 'E') &&
                    (j + 2 >= token.length() || !isExponentTail(token, j + 2))) {
                return j + 2;
            }
            return 0;
        }

        // java-only
        private boolean isExponentTail(String token, int i) {
            if (i < token.length() && (token.charAt(i) == '+' || token.charAt(i) == '-')) i++;
            return i < token.length() && Character.isDigit(token.charAt(i));
        }

        // java-only
        private int invalidNumberConsume(String token) {
            if (token.isEmpty()) return 0;
            char c0 = token.charAt(0);
            if ((c0 == '+' || c0 == '-') &&
                    (token.length() < 2 || (!Character.isDigit(token.charAt(1)) && token.charAt(1) != '.')))
                return 1;
            if (c0 == '.' && (token.length() < 2 || !Character.isDigit(token.charAt(1))))
                return 1;
            if (token.length() >= 2 && token.charAt(0) == '0' && (token.charAt(1) == 'x' || token.charAt(1) == 'X')) {
                if (token.length() < 3 || hexDigit(token.charAt(2)) < 0) return 2;
            }
            int i = 0;
            while (i < token.length()) {
                char c = token.charAt(i);
                if (Character.isDigit(c) || c == '.' || c == '+' || c == '-' ||
                        c == 'e' || c == 'E' || c == 'p' || c == 'P' ||
                        c == 'x' || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                    i++;
                } else break;
            }
            return i;
        }

        // java-only
        private int hexDigit(char c) {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return -1;
        }

        // java-only
        private int readByte() throws IOException {
            if (readBufferPos >= readBufferLen) {
                if (!fillReadBuffer()) return -1;
            }
            return readBuffer[readBufferPos++] & 0xFF;
        }

        // java-only: 从底层流重新填充读缓冲，EOF 返回 false
        private boolean fillReadBuffer() throws IOException {
            readBufferPos = 0;
            readBufferLen = 0;
            int n;
            if (raf != null) n = raf.read(readBuffer);
            else n = in.read(readBuffer);
            if (n > 0) readBufferLen = n;
            return n > 0;
        }

        // java-only: 丢弃读缓冲，把 raf 回退到逻辑位置
        private void invalidateReadBuffer() throws IOException {
            if (readBufferPos < readBufferLen && raf != null) {
                long logicalPos = raf.getFilePointer() - (readBufferLen - readBufferPos);
                raf.seek(logicalPos);
            }
            readBufferPos = 0;
            readBufferLen = 0;
        }

        // java-only
        private void unreadByte() throws IOException {
            if (readBufferPos > 0) {
                readBufferPos--;
            } else if (raf != null) {
                // java diff: 缓冲在 pos 0，必须把 raf 回退到 logical_pos - 1
                long logicalPos = raf.getFilePointer() - readBufferLen;
                readBufferPos = 0;
                readBufferLen = 0;
                raf.seek(Math.max(0, logicalPos - 1));
            }
        }

        // java-only
        private void unreadBytes(int n) throws IOException {
            if (n <= 0) return;
            if (n <= readBufferPos) {
                readBufferPos -= n;
            } else if (raf != null) {
                // java diff: n > readBufferPos，必须把 raf 回退到 logical_pos - n
                long logicalPos = raf.getFilePointer() - (readBufferLen - readBufferPos);
                readBufferPos = 0;
                readBufferLen = 0;
                raf.seek(Math.max(0, logicalPos - n));
            }
        }

        // java-only
        private int readBytes(byte[] buf) throws IOException {
            int total = 0;
            // 先排空读缓冲
            if (readBufferPos < readBufferLen) {
                int toCopy = Math.min(readBufferLen - readBufferPos, buf.length);
                System.arraycopy(readBuffer, readBufferPos, buf, 0, toCopy);
                readBufferPos += toCopy;
                total = toCopy;
            }
            // 缓冲排空后仍需更多数据，直接从文件读取
            if (total < buf.length) {
                int n;
                if (raf != null) n = raf.read(buf, total, buf.length - total);
                else n = in.read(buf, total, buf.length - total);
                if (n > 0) total += n;
            }
            return total == 0 ? -1 : total;
        }

        // java-only
        private byte[] readAllRemaining() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // 先排空读缓冲
            if (readBufferPos < readBufferLen) {
                baos.write(readBuffer, readBufferPos, readBufferLen - readBufferPos);
                readBufferPos = readBufferLen;
            }
            // 从文件读取剩余部分
            if (raf != null) {
                long remaining = raf.length() - raf.getFilePointer();
                if (remaining > 0) {
                    byte[] buf = new byte[(int) Math.min(remaining, Integer.MAX_VALUE)];
                    raf.readFully(buf);
                    baos.write(buf);
                }
            } else {
                baos.write(in.readAllBytes());
            }
            return baos.toByteArray();
        }

        // java-only
        private boolean isEof() throws IOException {
            if (readBufferPos < readBufferLen) return false;
            if (raf != null) return raf.getFilePointer() >= raf.length();
            return false;
        }

        // java-only
        private void writeBuffered(byte[] bytes) throws IOException {
            if (bytes.length == 0) return;
            if ("no".equals(bufferMode)) {
                rawWrite(bytes);
                return;
            }
            int limit = Math.max(1, bufferSize);
            if (writeBufferSize + bytes.length > limit) {
                flushWriteBuffer();
            }
            if (bytes.length >= limit) {
                rawWrite(bytes);
                return;
            }
            System.arraycopy(bytes, 0, writeBuffer, writeBufferSize, bytes.length);
            writeBufferSize += bytes.length;
            if (writeBufferSize >= limit || ("line".equals(bufferMode) && containsNewline(bytes))) {
                flushWriteBuffer();
            }
        }

        // java-only
        private boolean containsNewline(byte[] bytes) {
            for (byte b : bytes) if (b == '\n') return true;
            return false;
        }

        // java-only
        private void flushWriteBuffer() throws IOException {
            if (writeBufferSize == 0) return;
            int n = writeBufferSize;
            writeBufferSize = 0;
            rawWrite(writeBuffer, 0, n);
        }

        // java-only
        private void rawWrite(byte[] bytes) throws IOException {
            rawWrite(bytes, 0, bytes.length);
        }

        // java-only
        private void rawWrite(byte[] bytes, int offset, int length) throws IOException {
            if (raf != null) raf.write(bytes, offset, length);
            else if (out != null) out.write(bytes, offset, length);
        }
    }

    // LinesIterator
    public static class LinesIterator extends LuaFunction {
        private final IoFileHandle file;
        private final boolean closeAtEof;
        private boolean closed;

        public LinesIterator(IoFileHandle f) {
            this(f, false);
        }

        public LinesIterator(IoFileHandle f, boolean closeAtEof) {
            this.file = f;
            this.closeAtEof = closeAtEof;
        }

        @Override
        public Varargs call(Varargs args) {
            if (closed) return LuaErrors.error("file is already closed");
            if (!file.readable) return LuaErrors.error("file is not open for reading");
            try {
                LuaValue line = file.readLine();
                if (line.isnil() && closeAtEof) {
                    file.close();
                    closed = true;
                }
                return line;
            } catch (Exception e) {
                return LuaErrors.error(e.getMessage());
            }
        }
    }

    // java-only
    public abstract class SeekableInputStream extends InputStream {
        abstract long position();

        abstract void seek(long pos) throws IOException;

        abstract long size() throws IOException;
    }
}
