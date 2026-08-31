package org.luajvm.android.widget;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.luajvm.android.runtime.LuaLog;
import org.luajvm.android.util.LuaBitmap;
import com.bumptech.glide.Glide;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

public final class AdapterHelper {
    private AdapterHelper() {
    }

    public static void setFields(View view, LuaTable fields) {
        // 整表共用一次 JavaObject 包装：Coercion.toLua 每次新建包装与其方法缓存表，
        //   逐属性各包一次会让列表 bind 路径反复分配、方法缓存永不命中
        LuaValue viewValue = Coercion.toLua(view);
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs pair = fields.next(key);
            if (pair.isnil(1)) break;
            key = pair.arg1();
            var keyStr = key.toJavaString();
            if ("src".equalsIgnoreCase(keyStr)) {
                setHelper(view, pair.arg(2));
            } else {
                JavaCall.set(viewValue, keyStr, pair.arg(2));
            }
        }
    }

    public static void setHelper(View view, Object value) {
        try {
            if (value instanceof LuaTable table) {
                setFields(view, table);
            } else if (view instanceof TextView tv) {
                tv.setText(value instanceof CharSequence cs ? cs : String.valueOf(value));
            } else if (view instanceof ImageView iv) {
                setImage(iv, value);
            }
        } catch (Exception e) {
            // 进 LuaLog 而非只打 logcat：四个适配器的行绑定都汇到这里，只打 logcat
            //   会让绑定错误在应用内日志列表里完全不可见
            LuaLog.getInstance().addError("AdapterHelper", e);
        }
    }

    public static void setImage(ImageView imageView, Object value) {
        try {
            // 调用方（setFields）直接把 LuaTable 里的值传进来：LuaString/Lua userdata
            // 不是 java.lang.String/Bitmap，不归一的话四个分支全不命中
            if (value instanceof LuaValue lv) value = Coercion.toJava(lv, Object.class);
            switch (value) {
                case Bitmap bmp -> imageView.setImageBitmap(bmp);
                case String path -> Glide.with(imageView)
                        .load(LuaBitmap.toGlideModel(path)).into(imageView);
                case Drawable d -> imageView.setImageDrawable(d);
                case Number num -> imageView.setImageResource(num.intValue());
                case null, default -> { }
            }
        } catch (Exception e) {
            LuaLog.getInstance().addError("AdapterHelper", e);
        }
    }
}
