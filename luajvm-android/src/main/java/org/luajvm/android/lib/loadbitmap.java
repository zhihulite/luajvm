package org.luajvm.android.lib;

import android.content.Context;
import android.graphics.Bitmap;

import org.luajvm.android.api.LuaContext;
import org.luajvm.android.util.LuaBitmap;
import org.luajvm.android.runtime.LuaConfig;

import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

public class loadbitmap extends LuaFunction {
    private final Context mContext;
    private final String mLuaDir;

    public loadbitmap(LuaContext context) {
        mContext = context.getContext();
        mLuaDir = context.getLuaDir();
    }

    @Override
    public Varargs call(Varargs args) {
        String path = args.checkJavaString(1);
        try {
            if (mContext == null) {
                return LuaValue.NIL;
            }
            return LuaValue.userdataOf(loadBitmap(mContext, path));
        } catch (Exception e) {
            LuaConfig.logError("loadbitmap", e);
            return LuaValue.NIL;
        }
    }

    private Bitmap loadBitmap(Context ctx, String path) throws Exception {

        // 网络路径直接用

        if (path.startsWith("http://") || path.startsWith("https://")) {
            return LuaBitmap.getBitmapSync(ctx, path);
        }

        // 本地路径：无扩展名补 .png，相对路径拼 luaDir
        // 判定只看最后一个 '/' 之后的文件名，目录段含点（如 "a.b/icon"）不误判

        String fileName = path.substring(path.lastIndexOf('/') + 1);
        if (!fileName.contains(".")) {
            path += ".png";
        }
        if (!path.startsWith("/")) {
            path = mLuaDir + "/" + path;
        }
        return LuaBitmap.getBitmapSync(ctx, path);
    }
}
