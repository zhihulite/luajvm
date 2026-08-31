package org.luajvm.android.lib;

import org.luajvm.android.api.LuaContext;
import org.luajvm.android.util.LuaBitmap;

import org.luajvm.bind.Coercion;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.io.File;
import java.util.Locale;
import org.luajvm.android.widget.LuaBitmapDrawable;
import org.luajvm.android.widget.LuaLayout;

/**
 * res 模块：从 res/ 目录读资源。
 *
 * <p><b>访问约定</b>：核心 VM 对短串键走 {@code LuaTable.fastGetShortStr}（final），
 * 子类重写 {@code get} 拦不住 ⇒ 不能用「子类懒解析」承载 {@code res.drawable.icon}
 * 这类属性访问。故约定：
 * <ul>
 *   <li>{@code res.string.name} —— 属性访问：模块加载时急切执行
 *       {@code res/string/init.lua} 与 {@code res/string/<lang>.lua}，
 *       脚本 {@code return { key = "值" }}，返回表合并为真实存储的普通表；</li>
 *   <li>{@code res.drawable("icon")} / {@code res.bitmap("icon")} /
 *       {@code res.layout("name")} / {@code res.view("name")} —— 调用访问：
 *       按名找文件（图片扩展名或 .lua），layout 返回 chunk 结果，view 经
 *       LuaLayout 装配返回视图 userdata。</li>
 * </ul>
 */
public class res extends LuaFunction {
    private static final String[] IMAGE_EXTS = {".png", ".jpg", ".gif", ".webp"};

    private final LuaContext mContext;
    private final String mLanguage;
    private Globals mGlobals;

    public res(LuaContext context) {
        mContext = context;
        mLanguage = Locale.getDefault().getLanguage();
    }

    @Override
    public Varargs call(Varargs args) {
        LuaValue env = args.arg(2);
        mGlobals = env.checkglobals();
        LuaTable module = new LuaTable();
        module.set("string", loadStringTable());
        module.set("drawable", new drawable());
        module.set("bitmap", new bitmap());
        module.set("layout", new layout());
        module.set("view", new view());
        env.set("res", module);
        LuaValue pkg = env.get("package");
        if (!pkg.isnil()) pkg.get("loaded").set("res", module);
        return LuaValue.NIL;
    }

    /**
     * 资源名不得escape出 res 子目录。
     *
     * <p>{@code res.drawable("../../../etc/passwd")} 会让 {@code getLuaPath} 拼出目录外的
     * 路径。Lua 侧本就有 {@code io.open}，这里拦不住"想读别处"，但拦得住把目录外的文件
     * 当成资源静默读进来 —— 资源查找的契约是"在 res/&lt;kind&gt; 下按名找"，越界即报错。
     */
    private String resolveInRes(String kind, String name) {
        String dir = mContext.getLuaPath("res/" + kind, "");
        String full = mContext.getLuaPath("res/" + kind, name);
        try {
            String root = new File(dir).getCanonicalPath();
            if (!root.endsWith(File.separator)) root += File.separator;
            if (!new File(full).getCanonicalPath().startsWith(root)) {
                throw LuaErrors.errorObject("res: 资源名越出 res/" + kind + " 目录: " + name);
            }
        } catch (java.io.IOException e) {
            throw LuaErrors.errorObject("res: 无法解析资源路径: " + name);
        }
        return full;
    }

    /**
     * 合并顺序：init.lua 先、语言文件后，语言文件覆盖同名键。
     */
    private LuaTable loadStringTable() {
        LuaTable table = new LuaTable();
        mergeChunk(table, mContext.getLuaPath("res/string", "init.lua"));
        mergeChunk(table, mContext.getLuaPath("res/string", mLanguage + ".lua"));
        return table;
    }

    private void mergeChunk(LuaTable into, String path) {
        if (!new File(path).exists()) return;
        LuaValue r = LuaCall.invoke(mGlobals.loadfile(path), LuaValue.NONE).arg1();
        if (!(r instanceof LuaTable t)) return;
        LuaValue k = LuaValue.NIL;
        while (true) {
            var pair = t.next(k);
            if (pair.arg1().isnil()) break;
            into.set(pair.arg1(), pair.arg(2));
            k = pair.arg1();
        }
    }

    private String findFile(String base, String... exts) {
        for (String ext : exts) {
            String candidate = base + ext;
            if (new File(candidate).exists()) return candidate;
        }
        return null;
    }

    /** 图片/脚本双落差的公共查找：图片扩展名优先，其次同名 .lua。 */
    private LuaValue loadResource(String name, boolean asBitmap) throws Exception {
        String base = resolveInRes("drawable", name);
        String path = findFile(base, IMAGE_EXTS);
        if (path != null) {
            return asBitmap
                    ? Coercion.toLua(LuaBitmap.getBitmapSync(mContext.getContext(), path))
                    : Coercion.toLua(new LuaBitmapDrawable(mContext, path));
        }
        path = base + ".lua";
        if (new File(path).exists()) return LuaCall.invoke(mGlobals.loadfile(path), LuaValue.NONE).arg1();
        return LuaValue.NIL;
    }

    private class drawable extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            try {
                return loadResource(args.arg(1).toJavaString(), false);
            } catch (LuaError e) {
                throw e;
            } catch (Exception e) {
                throw LuaErrors.errorObject(e);
            }
        }
    }

    private class bitmap extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            try {
                return loadResource(args.arg(1).toJavaString(), true);
            } catch (Exception e) {
                throw LuaErrors.errorObject(e);
            }
        }
    }

    private class layout extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            String path = resolveInRes("layout", args.arg(1).toJavaString() + ".lua");
            return LuaCall.invoke(mGlobals.loadfile(path), LuaValue.NONE).arg1();
        }
    }

    private class view extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            String path = resolveInRes("layout", args.arg(1).toJavaString() + ".lua");
            return new LuaLayout(mContext.getContext())
                    .load(LuaCall.invoke(mGlobals.loadfile(path), LuaValue.NONE).arg1(), mGlobals);
        }
    }
}
