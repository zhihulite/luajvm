// ref: loadlib.c
// diff: 不支持动态加载.so/.dll(loadlib返回absent); c_searcher仅搜索不加载; loadFile代替luaL_loadfilex; ProcessHandle代替argv[0]; InputStream探测代替fopen
package org.luajvm.lib;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.io.InputStream;
import java.nio.file.Path;

public class PackageLib extends LuaFunction {
    public static final String LUA_VERSUFFIX = "_5_5";
    public static final String LUA_PATH_VAR = "LUA_PATH";
    public static final String LUA_CPATH_VAR = "LUA_CPATH";
    public static final String LUA_PATH_SEP = ";";
    public static final String LUA_PATH_MARK = "?";
    public static final String LUA_EXEC_DIR = "!";
    public static final String LUA_IGMARK = "-";
    static final LuaString _LOADED = LuaValue.valueOf("loaded");
    static final LuaString _PRELOAD = LuaValue.valueOf("preload");
    static final LuaString _PATH = LuaValue.valueOf("path");
    static final LuaString _SEARCHPATH = LuaValue.valueOf("searchpath");
    static final LuaString _SEARCHERS = LuaValue.valueOf("searchers");
    private static final LuaString _SENTINEL = LuaValue.valueOf("\u0001");
    private static final String FILE_SEP = System.getProperty("file.separator");

    public preload_searcher preload_searcher;
    public lua_searcher lua_searcher;
    public croot_searcher croot_searcher;
    public require require;
    public LuaTable searchers;
    Globals globals;
    LuaTable package_;

    public PackageLib() {
    }

    // LUA_PATH_DEFAULT
    private static String defaultLuaPath() {
        if (isWindows()) {
            return "!\\lua\\?.lua;!\\lua\\?\\init.lua;!\\?.lua;!\\?\\init.lua;" +
                    "!\\..\\share\\lua\\5.5\\?.lua;!\\..\\share\\lua\\5.5\\?\\init.lua;" +
                    ".\\?.lua;.\\?\\init.lua";
        }
        return "/usr/local/share/lua/5.5/?.lua;/usr/local/share/lua/5.5/?/init.lua;" +
                "/usr/local/lib/lua/5.5/?.lua;/usr/local/lib/lua/5.5/?/init.lua;" +
                "./?.lua;./?/init.lua";
    }

    // LUA_CPATH_DEFAULT —— 引擎不支持动态库加载，cpath 默认为空串；
    //   空 cpath 下 C 搜索器跳过（不做实际查找、不产生 no file 消息行）
    private static String defaultLuaCPath() {
        return "";
    }

    // loadlib.c: setprogdir
    private static String setprogdir(String path) {
        if (!isWindows() || path.indexOf(LUA_EXEC_DIR) < 0) return path;
        return path.replace(LUA_EXEC_DIR, executableDirectory());
    }

    private static String executableDirectory() {
        String command = ProcessHandle.current().info().command().orElse("");
        if (!command.isEmpty()) {
            try {
                Path parent = Path.of(command).toAbsolutePath().getParent();
                if (parent != null) return parent.toString();
            } catch (Exception ignored) {
            }
        }
        return Path.of(".").toAbsolutePath().normalize().toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // loadlib.c: luaopen_package
    @Override
    public Varargs call(Varargs args) {
        LuaValue modname = args.arg1();
        LuaValue env = args.arg(2);
        globals = env.checkglobals();
        globals.set("require", require = new require());
        package_ = new LuaTable();
        LuaValue loaded = globals.registry.get(_LOADED);
        if (!loaded.istable()) {
            loaded = new LuaTable();
            globals.registry.set(_LOADED, loaded);
        }
        LuaValue preload = globals.registry.get(_PRELOAD);
        if (!preload.istable()) {
            preload = new LuaTable();
            globals.registry.set(_PRELOAD, preload);
        }
        package_.set(_LOADED, loaded);
        package_.set(_PRELOAD, preload);
        package_.set(_PATH, LuaValue.valueOf(setpath(LUA_PATH_VAR, defaultLuaPath())));
        package_.set("cpath", LuaValue.valueOf(setpath(LUA_CPATH_VAR, defaultLuaCPath())));
        package_.set(_SEARCHPATH, new searchpath());
        package_.set("config", LuaString.newStr(FILE_SEP + "\n" + LUA_PATH_SEP + "\n" +
                LUA_PATH_MARK + "\n" + LUA_EXEC_DIR + "\n" + LUA_IGMARK + "\n"));
        searchers = new LuaTable();
        searchers.set(1, preload_searcher = new preload_searcher());
        searchers.set(2, lua_searcher = new lua_searcher());
        searchers.set(3, new c_searcher());
        searchers.set(4, croot_searcher = new croot_searcher());
        package_.set(_SEARCHERS, searchers);
        package_.get(_LOADED).set("package", package_);
        env.set("package", package_);
        globals.package_ = this;
        return env;
    }

    // loadlib.c: setpath
    private String setpath(String envname, String dft) {
        String path = System.getenv(envname + LUA_VERSUFFIX);
        if (path == null) path = System.getenv(envname);
        if (path == null || noenv()) {
            return setprogdir(dft);
        }
        int dftmark = path.indexOf(LUA_PATH_SEP + LUA_PATH_SEP);
        if (dftmark < 0) {
            return setprogdir(path);
        }
        StringBuilder b = new StringBuilder();
        if (dftmark > 0) {
            b.append(path, 0, dftmark).append(LUA_PATH_SEP);
        }
        b.append(dft);
        if (dftmark < path.length() - 2) {
            b.append(LUA_PATH_SEP).append(path, dftmark + 2, path.length());
        }
        return setprogdir(b.toString());
    }

    // loadlib.c: noenv
    private boolean noenv() {
        LuaValue v = globals.registry.get("LUA_NOENV");
        return !v.isnil() && v.toboolean();
    }



    @Override
    public String toJavaString() {
        return "package";
    }

    // loadlib.c: ll_loadlib
    public static class LoadLibFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            args.checkstring(1);
            return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("dynamic libraries not enabled"), LuaValue.valueOf("absent"));
        }
    }

    // loadlib.c: ll_require
    public final class require extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            LuaString name = arg.checkstring();
            LuaValue loaded = package_.get(_LOADED);
            LuaValue result = loaded.get(name);
            if (result.toboolean()) {
                if (result == _SENTINEL)
                    LuaErrors.error("loop or previous error loading module '" + name + "'");
                return result;
            }
            LuaValue searchersValue = package_.get(_SEARCHERS);
            if (!searchersValue.istable()) {
                LuaErrors.error("'package.searchers' must be a table");
            }
            LuaTable tbl = searchersValue.checktable();

            StringBuilder sb = new StringBuilder();
            Varargs loader = null;
            for (int i = 1; ; i++) {
                LuaValue searcher = tbl.get(i);
                if (searcher.isnil()) {

                    String msg = sb.toString();
                    if (msg.endsWith("\n\t")) msg = msg.substring(0, msg.length() - 2);
                    LuaErrors.error("module '" + name + "' not found:" + msg);
                }

                loader = LuaCall.callNoYield(searcher, name);
                if (loader.isfunction(1) || loader.isuserdata(1) || loader.istable(1)) break;
                if (loader.isstring(1)) {
                    sb.append("\n\t").append(loader.toJavaString(1));
                }
            }
            loaded.set(name, _SENTINEL);
            if (loader.isuserdata(1))
                result = loader.arg1();

            else if (loader.isfunction(1))
                result = LuaCall.callNoYield(loader.arg1(), name, loader.arg(2)).arg1();
            else
                result = loader.arg1();  // 表或其他类型  -  原样返回
            if (!result.isnil())
                loaded.set(name, result);
            else if ((result = loaded.get(name)) == _SENTINEL)
                loaded.set(name, result = LuaValue.TRUE);

            return LuaValue.varargsOf(result, loader.arg(2));
        }
    }

    // loadlib.c: searcher_preload
    public class preload_searcher extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaString name = args.checkstring(1);
            LuaValue val = package_.get(_PRELOAD).get(name);
            return val.isnil() ? LuaValue.valueOf("no field package.preload['" + name + "']") : val;
        }
    }

    // loadlib.c: searcher_Lua
    public class lua_searcher extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaString name = args.checkstring(1);
            LuaValue path = package_.get(_PATH);
            if (!path.isstring()) return LuaValue.valueOf("package.path is not a string");
            LuaValue fn = package_.get(_SEARCHPATH);

            Varargs v = LuaCall.callNoYield(fn, name, path);
            if (!v.isstring(1)) return v.arg(2).tostring();  // searchpath 失败  -  返回 "no file '...'"
            LuaString filename = v.arg1().strValue();

            v = globals.baselib.loadFile(filename.toJavaString(), "bt", globals);
            if (v.arg1().isfunction()) return LuaValue.varargsOf(v.arg1(), filename);

            LuaErrors.error("error loading module '" + name + "' from file '" + filename + "':\n\t" + v.arg(2).toJavaString());
            return LuaValue.NONE;
        }
    }

    // loadlib.c: searcher_C
    public class c_searcher extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaString name = args.checkstring(1);
            LuaValue cpath = package_.get("cpath");
            if (!cpath.isstring()) return LuaValue.valueOf("package.cpath is not a string");
            // 空 cpath 不做实际查找：空串仍是一条搜索模板，照搜会产出 no file '' 消息行
            if (cpath.toJavaString().isEmpty()) return LuaValue.NIL;
            LuaValue fn = package_.get(_SEARCHPATH);
            Varargs v = LuaCall.callNoYield(fn, name, cpath);
            if (!v.isstring(1)) return v.arg(2).tostring();

            return LuaValue.valueOf("no loader for '" + v.arg1().toJavaString() + "'");
        }
    }

    // loadlib.c: searcher_Croot
    public class croot_searcher extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            String name = args.checkJavaString(1);
            int dot = name.indexOf('.');
            if (dot < 0) return LuaValue.NONE;
            String root = name.substring(0, dot);
            LuaValue cpath = package_.get("cpath");
            if (!cpath.isstring()) return LuaValue.valueOf("package.cpath is not a string");
            // 空 cpath 不做实际查找：同 searcher_C
            if (cpath.toJavaString().isEmpty()) return LuaValue.NIL;
            LuaValue fn = package_.get(_SEARCHPATH);

            Varargs v = LuaCall.callNoYield(fn,
                    LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(root), cpath, LuaValue.valueOf("."), LuaValue.valueOf(FILE_SEP)}));
            if (!v.isstring(1)) return v.arg(2).tostring();
            return LuaValue.valueOf("no module '" + name + "' in file '" + v.arg1().toJavaString() + "'");
        }
    }

    // loadlib.c: ll_searchpath
    public class searchpath extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            String name = args.checkJavaString(1);
            String path = args.checkJavaString(2);
            String sep = args.optJavaString(3, ".");
            String rep = args.optJavaString(4, FILE_SEP);

            if (!sep.isEmpty() && name.contains(sep)) {
                name = name.replace(sep, rep);
            }
            int e = -1, n = path.length();
            StringBuilder errs = null;
            while (e < n) {
                int b = e + 1;
                e = path.indexOf(';', b);
                if (e < 0) e = path.length();
                String template = path.substring(b, e);

                String filename = template.replace("?", name);
                InputStream is = globals.baselib.openResource(filename);
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception ignored) {
                    }
                    return LuaValue.valueOf(filename);
                }
                if (errs == null) errs = new StringBuilder();
                else errs.append("'\n\t");
                errs.append("no file '").append(filename);
            }

            String errMsg = errs != null ? errs + "'" : "no file";
            return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(errMsg));
        }
    }
}
