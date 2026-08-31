package org.luajvm.android.lib;

import android.os.Build;

import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.util.LuaUtil;

import org.luajvm.bind.JavaCall;
import org.luajvm.bind.JavaLib;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Lua file 模块：文件读写、列表、属性查询。
 * Lua 侧看到的是表键（readall/list/exists/save/type/info/mkdir），内部类名与之无关。
 */
public class file extends LuaFunction {
    private static final String[] EMPTY_NAMES = new String[0];

    /**
     * 路径解析器，构造时注入。
     *
     * <p>用实例字段持有：resolver 通常是 {@code LuaEngine::findFile} 这类方法引用，
     * 强持 LuaEngine 进而持 Activity；static 持有不随 Activity 销毁释放，
     * 多宿主并存时后注册的还会覆盖前一个（互相串味）。
     */
    private final PathResolver mPathResolver;

    public file(PathResolver resolver) {
        mPathResolver = resolver;
    }

    // 已发布 API：返回 String 必然经字符集解码，显式指定 UTF-8 去掉平台默认字符集依赖。
    // 需要字节保真请走 Lua 的 file.readall（ReadAllFn 不经 String 中转）。
    public static String readAll(String path) {
        try {
            return new String(LuaUtil.readAll(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LuaConfig.logError("file.readAll", e);
        }
        return "";
    }

    public static String[] list(String path) {
        File dir = new File(path);
        if (dir.isDirectory()) {
            return Objects.requireNonNullElse(dir.list(), EMPTY_NAMES);
        }
        return EMPTY_NAMES;
    }

    public static boolean exists(String path) {
        return new File(path).exists();
    }

    public static boolean save(String path, LuaString text) {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(text.bytes());
            return true;
        } catch (Exception e) {
            LuaConfig.logError("file.save", e);
        }
        return false;
    }

    private String resolvePath(String path) {
        return mPathResolver != null ? mPathResolver.findFile(path) : path;
    }

    @Override
    public Varargs call(Varargs args) {
        LuaValue env = args.arg(2);
        env.checkglobals();
        LuaTable fileLib = new LuaTable();
        fileLib.set("readall", new ReadAllFn());
        fileLib.set("list", new ListFn());
        fileLib.set("exists", new ExistsFn());
        fileLib.set("save", new SaveFn());
        fileLib.set("type", new TypeFn());
        fileLib.set("info", new InfoFn());
        fileLib.set("mkdir", new MkdirFn());
        env.set("file", fileLib);
        if (!env.get("package").isnil()) env.get("package").get("loaded").set("file", fileLib);
        return LuaValue.NIL;
    }

    public interface PathResolver {
        String findFile(String path);
    }

    private class ReadAllFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue pathArg = args.arg1();
            byte[] content;
            try {
                content = LuaUtil.readAll(resolvePath(pathArg.toJavaString()));
            } catch (IOException e) {
                // 读失败返回 NIL（不抛错）
                LuaConfig.logError("file.readAll", e);
                return LuaValue.NIL;
            }
            // 直接用字节建 LuaString：经 String 中转会把非 UTF-8/二进制内容替换成 U+FFFD 后不可逆
            return content.length == 0 ? LuaValue.NIL : LuaValue.valueOf(content);
        }
    }

    private class ListFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue pathArg = args.arg1();
            return JavaLib.asTable(list(resolvePath(pathArg.toJavaString())), false);
        }
    }

    private class ExistsFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue pathArg = args.arg1();
            try {
                return LuaValue.valueOf(exists(resolvePath(pathArg.toJavaString())));
            } catch (Exception e) {
                return LuaValue.NIL;
            }
        }
    }

    private class SaveFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue pathArg = args.arg1();
            LuaValue textArg = args.arg(2);
            return LuaValue.valueOf(save(resolvePath(pathArg.toJavaString()), textArg.checkstring()));
        }
    }

    private class TypeFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue pathArg = args.arg1();
            File target = new File(resolvePath(pathArg.toJavaString()));
            if (!target.exists()) return LuaValue.valueOf("");
            return LuaValue.valueOf(target.isDirectory() ? "dir" : "file");
        }
    }

    private class MkdirFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue pathArg = args.arg1();
            File dir = new File(resolvePath(pathArg.toJavaString()));
            if (dir.exists()) return LuaValue.TRUE;
            return LuaValue.valueOf(dir.mkdirs());
        }
    }

    private class InfoFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue pathArg = args.arg1();
            LuaTable ret = new LuaTable();
            String path = resolvePath(pathArg.toJavaString());
            File target = new File(path);
            String name = target.getName();
            JavaCall.set(ret, "name", name);
            int dot = name.lastIndexOf('.');
            if (dot > 0) JavaCall.set(ret, "ext", name.substring(dot + 1));
            JavaCall.set(ret, "parent", target.getParent());
            JavaCall.set(ret, "read", target.canRead());
            JavaCall.set(ret, "write", target.canWrite());
            if (!target.exists()) {
                JavaCall.set(ret, "type", "");
                return ret;
            }

            JavaCall.set(ret, "type", target.isDirectory() ? "dir" : "file");
            JavaCall.set(ret, "path", target.getAbsolutePath());
            JavaCall.set(ret, "size", target.length());
            JavaCall.set(ret, "execute", target.canExecute());
            JavaCall.set(ret, "last", target.lastModified());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Path attrPath = target.toPath();
                    BasicFileAttributes attr = Files.readAttributes(attrPath, BasicFileAttributes.class);
                    JavaCall.set(ret, "create", attr.creationTime().toMillis());
                    JavaCall.set(ret, "access", attr.lastAccessTime().toMillis());
                } catch (Exception e) {
                    LuaConfig.logError("file.info", e);
                }
            }
            return ret;
        }
    }
}
