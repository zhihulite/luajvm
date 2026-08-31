// ref: liolib.c
// diff: RandomAccessFile/InputStream/OutputStream 代替 FILE*；LuaUserdata 代替 luaL_Stream；ProcessBuilder 代替 popen；Globals.STDIN/STDOUT 代替全局默认文件
// diff: 共享 metatable/methods 表（对齐 C 的 createmeta/luaL_setmetatable）
package org.luajvm.lib;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaUserdata;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public class IoLib extends LuaFunction {
    // C：liolib.c : createmeta  -  FILE* 元表与方法表按状态存放（Globals.ioFile*），
    // 句柄绑建句柄所属状态的方法表（跨状态复用会绑错函数对象）。

    public IoLib() {
    }

    // liolib.c: createmeta
    static void createmeta(Globals g) {
        LuaTable fileMethods = LuaValue.tableOf();
        fileMethods.set("close", new f_close());
        fileMethods.set("read", new f_read());
        fileMethods.set("write", new f_write());
        fileMethods.set("flush", new f_flush());
        fileMethods.set("seek", new f_seek());
        fileMethods.set("lines", new f_lines());
        fileMethods.set("setvbuf", new f_setvbuf());

        LuaTable fileMetatable = LuaValue.tableOf();
        fileMetatable.set(LuaString.newStr("__name"), LuaString.newStr("FILE*"));
        fileMetatable.set(LuaString.newStr("__gc"), new f_gc());
        fileMetatable.set(LuaString.newStr("__close"), new f_gc());
        fileMetatable.set(LuaString.newStr("__tostring"), new f_tostring());
        fileMetatable.set(LuaString.newStr("__index"), fileMethods);
        // fileMethods 已作 fileMetatable.__index 挂上，经 metatable 可达，无需单独存字段
        g.ioFileMetatable = fileMetatable;
    }

    // liolib.c: newprefile + luaL_setmetatable  -  元表取自建句柄所属状态 g
    // （从运行中的状态推断会在远程状态打开库时绑错）。
    static LuaValue wrapHandle(Globals g, IoFile.IoFileHandle h) {
        LuaUserdata ud = LuaUserdata.userdataOf(h, 0);
        if (g != null) {
            LuaTable.bindValue(g, ud);
            ud.setmetatable(g.ioFileMetatable);
        }
        return ud;
    }

    // liolib.c: luaL_checkudata
    static void checkfile(LuaValue arg, int narg, Varargs args) {
        if (arg instanceof LuaUserdata ud && ud.touserdata() instanceof IoFile.IoFileHandle) {
            LuaValue mt = arg.getmetatable();
            if (mt != null) {
                LuaValue name = mt.rawget(LuaString.newStr("__name"));
                if (name instanceof LuaString s && s.toJavaString().equals("FILE*")) return;
            }
        }
        if (arg.istable()) {
            LuaValue mt = arg.getmetatable();
            if (mt != null) {
                LuaValue name = mt.rawget(LuaString.newStr("__name"));
                if (name instanceof LuaString s && s.toJavaString().equals("FILE*")) return;
            }
        }
        LuaErrors.typeError(narg, args, "FILE*");
    }

    // liolib.c: tofile
    static IoFile.IoFileHandle unwrapHandle(LuaValue t) {
        if (t instanceof LuaUserdata ud && ud.touserdata() instanceof IoFile.IoFileHandle fh)
            return fh;
        return (IoFile.IoFileHandle) t.rawget(LuaString.newStr("__file")).touserdata();
    }

    // liolib.c: checkHandle
    static IoFile.IoFileHandle checkHandle(LuaValue arg, int narg, Varargs args) {
        checkfile(arg, narg, args);
        return unwrapHandle(arg);
    }

    // liolib.c: luaopen_io
    @Override
    public Varargs call(Varargs args) {
        Globals owner = args.arg(2).checkglobals();
        createmeta(owner);
        LuaTable io = new LuaTable();
        io.set("open", new ioopen());
        io.set("close", new ioclose(owner));
        io.set("flush", new ioflush(owner));
        io.set("input", new ioinput(owner));
        io.set("output", new iooutput(owner));
        io.set("type", new iotype());
        io.set("tmpfile", new iotmpfile());
        io.set("popen", new iopopen());
        io.set("read", new ioread(owner));
        io.set("write", new iowrite(owner));
        io.set("lines", new iolines(owner));

        LuaValue stdinHandle = wrapHandle(owner, new IoFile.IoFileHandle(System.in, null, "stdin", 0));
        LuaValue stdoutHandle = wrapHandle(owner, new IoFile.IoFileHandle(null, System.out, "stdout", 1));
        LuaValue stderrHandle = wrapHandle(owner, new IoFile.IoFileHandle(null, System.err, "stderr", 2));
        io.set("stdin", stdinHandle);
        io.set("stdout", stdoutHandle);
        io.set("stderr", stderrHandle);
        if (owner.STDIN.isnil()) owner.STDIN = stdinHandle;
        if (owner.STDOUT.isnil()) owner.STDOUT = stdoutHandle;
        owner.set("io", io);

        if (!owner.get("package").isnil()) owner.get("package").get("loaded").set("io", io);
        return io;
    }

    // liolib.c: f_gc
    static final class f_gc extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {

            IoFile.IoFileHandle fh = checkHandle(args.arg1(), 1, args);

            try {
                fh.close();
            } catch (Exception e) { /* ignore */ }
            return LuaValue.NONE;
        }
    }

    // liolib.c: f_tostring
    static final class f_tostring extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            if (arg instanceof LuaUserdata ud && ud.touserdata() instanceof IoFile.IoFileHandle fh) {
                if (fh.isClosed())
                    return LuaString.newStr("file (closed)");
                return LuaString.newStr("file (" + fh.name + ")");
            }
            return LuaString.newStr("file (closed)");
        }
    }

    // liolib.c: f_close
    static final class f_close extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            try {
                return checkHandle(args.arg1(), 1, args).close();
            } catch (Exception e) {
                return LuaErrors.error(e.getMessage());
            }
        }
    }

    // liolib.c: f_read
    static final class f_read extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            try {
                return checkHandle(args.arg1(), 1, args).read(args.subargs(2));
            } catch (Exception e) {
                return LuaErrors.error(e.getMessage());
            }
        }
    }

    // liolib.c: f_write
    static final class f_write extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            try {
                // liolib.c f_write: g_write(L, f, 2) —— self 占 #1，故报错序号从 2 起
                Varargs r = checkHandle(args.arg1(), 1, args).write(args.subargs(2), 2);
                if (r.arg1().isnil()) return r;
                return args.arg1();
            } catch (Exception e) {
                return LuaErrors.error(e.getMessage());
            }
        }
    }

    // liolib.c: f_flush
    static final class f_flush extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            try {
                return checkHandle(args.arg1(), 1, args).flush();
            } catch (Exception e) {
                return LuaErrors.error(e.getMessage());
            }
        }
    }

    // liolib.c: f_seek
    static final class f_seek extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            try {
                IoFile.IoFileHandle fh = checkHandle(args.arg1(), 1, args);
                String whence = args.optJavaString(2, "cur");
                int offset = args.optint(3, 0);
                return fh.seek(whence, offset);
            } catch (Exception e) {
                return LuaErrors.error(e.getMessage());
            }
        }
    }

    // liolib.c: f_setvbuf
    static final class f_setvbuf extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            IoFile.IoFileHandle fh = checkHandle(args.arg1(), 1, args);
            String mode = args.checkJavaString(2);
            if (!mode.equals("no") && !mode.equals("full") && !mode.equals("line")) {
                LuaErrors.argError(2, "invalid option");
            }
            int size = args.optint(3, IoFile.LUAL_BUFFERSIZE);
            try {
                return fh.setvbuf(mode, size);
            } catch (Exception e) {
                return LuaErrors.error(e.getMessage());
            }
        }
    }

    // liolib.c: f_lines
    static final class f_lines extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            IoFile.IoFileHandle fh = checkHandle(args.arg1(), 1, args);
            final Varargs formats = args.subargs(2);

            return new LuaFunction() {
                @Override
                public Varargs call(Varargs a) {
                    if (fh.isClosed()) return LuaErrors.error("file is already closed");
                    if (!fh.readable) return LuaErrors.error("file is not open for reading");
                    try {
                        return formats.narg() == 0 ? fh.readLine() : fh.read(formats);
                    } catch (Exception e) {
                        return LuaErrors.error(e.getMessage());
                    }
                }
            };
        }
    }

    // liolib.c: io_open
    static final class ioopen extends LuaFunction {
        private boolean validMode(String mode) {
            if (mode == null || mode.isEmpty()) return false;
            char first = mode.charAt(0);
            if (first != 'r' && first != 'w' && first != 'a') return false;
            int i = 1;
            if (i < mode.length() && mode.charAt(i) == '+') i++;
            while (i < mode.length() && mode.charAt(i) == 'b') i++;
            return i == mode.length();
        }

        @Override
        public Varargs call(Varargs args) {
            String filename = args.checkJavaString(1);
            String mode = args.optJavaString(2, "r");
            if (!validMode(mode)) LuaErrors.argError(2, "invalid mode");
            try {
                File f = BaseLib.resolveFile(filename);
                char kind = mode.charAt(0);
                boolean update = mode.contains("+");
                if (kind == 'r' && !f.exists()) throw new FileNotFoundException(filename);
                boolean readOnly = kind == 'r' || update;
                boolean append = mode.contains("a");
                boolean write = kind == 'w' || append || update;
                RandomAccessFile raf = new RandomAccessFile(f, readOnly && !write ? "r" : "rw");
                if (kind == 'w') raf.setLength(0);
                if (append) raf.seek(raf.length());
                return wrapHandle(ownerGlobals, new IoFile.IoFileHandle(raf, filename, 3, readOnly, write));
            } catch (LuaError e) {

                throw e;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "(no extra info)";
                return LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr(filename + ": " + msg), LuaInteger.valueOf(2));
            }
        }
    }

    // liolib.c: ioclose
    static final class ioclose extends LuaFunction {
        final Globals owner;

        ioclose(Globals owner) {
            this.owner = owner;
        }

        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.narg() == 0 ? owner.STDOUT : args.arg1();
            try {
                return checkHandle(arg, 1, args).close();
            } catch (Exception e) {
                return LuaErrors.error(e.getMessage());
            }
        }
    }

    // liolib.c: ioflush
    static final class ioflush extends LuaFunction {
        final Globals owner;

        ioflush(Globals owner) {
            this.owner = owner;
        }

        @Override
        public Varargs call(Varargs args) {
            LuaValue f = owner.STDOUT;
            if (f.isuserdata() || f.istable()) {
                IoFile.IoFileHandle fh = unwrapHandle(f);
                if (fh != null) {
                    if (fh.isClosed())
                        return LuaErrors.error("standard output file is closed");
                    try {
                        return fh.flush();
                    } catch (Exception e) {
                        return LuaErrors.error(e.getMessage());
                    }
                }
            }
            return LuaErrors.error("no output file");
        }
    }

    // liolib.c: ioinput
    static final class ioinput extends LuaFunction {
        final Globals owner;

        ioinput(Globals owner) {
            this.owner = owner;
        }

        @Override
        public Varargs call(Varargs args) {
            if (args.narg() == 0 || args.arg1().isnil()) return owner.STDIN;
            LuaValue arg = args.arg1();
            if (arg.isstring()) {

                String filename = arg.toJavaString();
                try {
                    File f = BaseLib.resolveFile(filename);
                    RandomAccessFile raf = new RandomAccessFile(f, "r");
                    LuaValue handle = wrapHandle(ownerGlobals, new IoFile.IoFileHandle(raf, filename, 3, true, false));
                    owner.STDIN = handle;
                } catch (Exception e) {
                    return LuaErrors.error(filename + ": " + e.getMessage());
                }
            } else {

                checkfile(arg, 1, args);
                owner.STDIN = arg;
            }
            return owner.STDIN;
        }
    }

    // liolib.c: iooutput
    static final class iooutput extends LuaFunction {
        final Globals owner;

        iooutput(Globals owner) {
            this.owner = owner;
        }

        @Override
        public Varargs call(Varargs args) {
            if (args.narg() == 0) return owner.STDOUT;
            LuaValue arg = args.arg1();
            if (arg.isstring()) {
                String filename = arg.toJavaString();
                try {
                    if ("/dev/null".equals(filename)) {
                        LuaValue handle = wrapHandle(ownerGlobals, new IoFile.IoFileHandle(null, OutputStream.nullOutputStream(), filename, 3));
                        owner.STDOUT = handle;
                        return owner.STDOUT;
                    }
                    if ("/dev/full".equals(filename)) {
                        IoFile.IoFileHandle fh = new IoFile.IoFileHandle(null, new ByteArrayOutputStream(), filename, 3);
                        fh.fullDevice = true;
                        LuaValue handle = wrapHandle(ownerGlobals, fh);
                        owner.STDOUT = handle;
                        return owner.STDOUT;
                    }
                    File f = BaseLib.resolveFile(filename);
                    RandomAccessFile raf = new RandomAccessFile(f, "rw");
                    raf.setLength(0);
                    LuaValue handle = wrapHandle(ownerGlobals, new IoFile.IoFileHandle(raf, filename, 3, true, true));
                    owner.STDOUT = handle;
                } catch (Exception e) {
                    return LuaErrors.error("error opening file '" + filename + "': " + e.getMessage());
                }
            } else {

                checkfile(arg, 1, args);
                owner.STDOUT = arg;
            }
            return owner.STDOUT;
        }
    }

    // liolib.c: iotype
    static final class iotype extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            if (arg instanceof LuaUserdata ud && ud.touserdata() instanceof IoFile.IoFileHandle fh)
                return LuaString.newStr(fh.isClosed() ? "closed file" : "file");
            return LuaValue.NIL;
        }
    }

    // liolib.c: iotmpfile
    static final class iotmpfile extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            try {
                File f = File.createTempFile("luaj", ".tmp");
                f.deleteOnExit();
                IoFile.IoFileHandle h = new IoFile.IoFileHandle(new RandomAccessFile(f, "rw"), f.getAbsolutePath(), 3, true, true);
                return wrapHandle(ownerGlobals, h);
            } catch (Exception e) {
                return LuaErrors.error(e.getMessage());
            }
        }
    }

    // liolib.c: iopopen
    static final class iopopen extends LuaFunction {
        private boolean validMode(String mode) {
            if (mode == null || mode.isEmpty()) return false;
            char first = mode.charAt(0);
            if (first != 'r' && first != 'w') return false;
            if (mode.length() == 1) return true;
            boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
            return windows && mode.length() == 2 && (mode.charAt(1) == 'b' || mode.charAt(1) == 't');
        }

        @Override
        public Varargs call(Varargs args) {

            String command = args.checkJavaString(1);
            String mode = args.optJavaString(2, "r");
            if (!validMode(mode)) LuaErrors.argError(2, "invalid mode");
            boolean readMode = mode.charAt(0) == 'r';
            try {
                boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
                ProcessBuilder pb = new ProcessBuilder(windows
                        ? new String[]{"cmd", "/c", command}
                        : new String[]{"/bin/sh", "-c", command});
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                if (readMode) {
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
                Process process = pb.start();
                if (readMode) {
                    process.getOutputStream().close();
                }
                IoFile.IoFileHandle h = readMode
                        ? new IoFile.IoFileHandle(process.getInputStream(), null, command, 3)
                        : new IoFile.IoFileHandle(null, process.getOutputStream(), command, 3);
                h.process = process;
                return wrapHandle(ownerGlobals, h);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "(no extra info)";
                return LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr(command + ": " + msg), LuaInteger.valueOf(1));
            }
        }
    }

    // liolib.c: ioread
    static final class ioread extends LuaFunction {
        final Globals owner;

        ioread(Globals owner) {
            this.owner = owner;
        }

        @Override
        public Varargs call(Varargs args) {
            LuaValue f = owner.STDIN;
            if (f.isuserdata() || f.istable()) {
                IoFile.IoFileHandle fh = unwrapHandle(f);
                if (fh != null) {
                    if (fh.isClosed()) return LuaErrors.error("standard input file is closed");
                    try {
                        return fh.read(args);
                    } catch (Exception e) {
                        return LuaErrors.error(e.getMessage());
                    }
                }
            }
            return LuaErrors.error("no input file");
        }
    }

    // liolib.c: iowrite
    static final class iowrite extends LuaFunction {
        final Globals owner;

        iowrite(Globals owner) {
            this.owner = owner;
        }

        @Override
        public Varargs call(Varargs args) {
            LuaValue f = owner.STDOUT;
            if (f.isuserdata() || f.istable()) {
                IoFile.IoFileHandle fh = unwrapHandle(f);
                if (fh != null) {
                    if (fh.isClosed())
                        return LuaErrors.error("standard output file is closed");

                    for (int i = 1, n = args.narg(); i <= n; i++) {
                        LuaValue arg = args.arg(i);
                        if (!arg.isstring() && !arg.isnumber()) {
                            // liolib.c g_write -> luaL_checklstring -> luaL_argerror：
                            //   名字由 lua_getinfo 从调用信息解析（io 表里注册名 'write'，
                            //   不是 'io.write'），且经 luaL_error 带 luaL_where(L,1) 前缀。
                            LuaErrors.typeError(i, args, "string");
                        }
                    }
                    try {
                        Varargs r = fh.write(args);
                        if (r.arg1().isnil()) return r;
                        return f;
                    } catch (Exception e) {
                        return LuaErrors.error(e.getMessage());
                    }
                }
            }
            return LuaErrors.error("no output file");
        }
    }

    // liolib.c: iolines
    static final class iolines extends LuaFunction {
        final Globals owner;

        iolines(Globals owner) {
            this.owner = owner;
        }

        @Override
        public Varargs call(Varargs args) {
            if (!args.arg1().isnil()) {
                String filename = args.checkJavaString(1);
                if (args.narg() > 251) LuaErrors.argError(2, "too many arguments");
                try {
                    RandomAccessFile raf = new RandomAccessFile(BaseLib.resolveFile(filename), "r");
                    final LuaValue handle = wrapHandle(ownerGlobals, new IoFile.IoFileHandle(raf, filename, 3, true, false));
                    final IoFile.IoFileHandle fh = unwrapHandle(handle);
                    final Varargs formats = args.subargs(2);
                    LuaFunction iter = new LuaFunction() {
                        boolean closed;

                        @Override
                        public Varargs call(Varargs a) {
                            if (closed) return LuaErrors.error("file is already closed");
                            try {
                                Varargs r = formats.narg() == 0 ? fh.readLine() : fh.read(formats);
                                if (r.arg1().isnil()) {
                                    fh.close();
                                    closed = true;
                                }
                                return r;
                            } catch (Exception e) {
                                return LuaErrors.error(e.getMessage());
                            }
                        }
                    };
                    return LuaValue.varargsOf(new LuaValue[]{iter, LuaValue.NIL, LuaValue.NIL, handle});
                } catch (Exception e) {
                    return LuaErrors.error("cannot open file '" + filename + "' (" + e.getMessage() + ")");
                }
            }
            LuaValue f = owner.STDIN;
            if (f.isuserdata() || f.istable()) {
                final IoFile.IoFileHandle fh = unwrapHandle(f);
                final Varargs formats = args.subargs(args.narg() > 0 && args.arg1().isnil() ? 2 : 1);
                if (fh != null) {
                    if (formats.narg() == 0) return fh.lines();
                    return new LuaFunction() {
                        @Override
                        public Varargs call(Varargs a) {
                            if (fh.isClosed())
                                return LuaErrors.error("file is already closed");
                            if (!fh.readable)
                                return LuaErrors.error("file is not open for reading");
                            try {
                                return fh.read(formats);
                            } catch (Exception e) {
                                return LuaErrors.error(e.getMessage());
                            }
                        }
                    };
                }
            }
            return LuaErrors.error("no input file");
        }
    }
}
