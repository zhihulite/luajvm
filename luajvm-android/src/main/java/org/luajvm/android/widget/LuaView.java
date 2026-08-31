package org.luajvm.android.widget;

import org.luajvm.android.widget.LuaLayout;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.luajvm.android.api.LuaContext;

import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;

/**
 * Lua 视图容器。
 * <p>
 * 公共 API  -  将 Lua 表定义的布局渲染为 Android View。
 * <p>
 * view 包对外暴露的核心类之一，主模块可直接使用：
 * <pre>
 *   LuaView view = new LuaView(context, luaTable);
 *   parentLayout.addView(view);
 * </pre>
 */
@SuppressWarnings("unused")
public class LuaView extends FrameLayout {

    public LuaView(Context context) {
        super(context);
    }

    /**
     * 从 Lua 表创建视图。
     *
     * @param context Android 上下文
     * @param layout  Lua 表定义的布局
     */
    public LuaView(Context context, LuaValue layout) {
        // 委托三参构造：context 本身是 LuaContext 时用其 Globals 作 env，
        // 布局表的 on* 字符串处理器才能解析到全局函数；否则回落空表 env
        this(context, context instanceof LuaContext lc ? lc : null, layout);
    }

    /**
     * 从 Lua 表创建视图（带 LuaContext）。
     * <p>
     * 推荐用此构造器，能正确解析 Lua 上下文中的资源引用。
     *
     * @param context Android 上下文
     * @param luaCtx  Lua 上下文
     * @param layout  Lua 表定义的布局
     */
    public LuaView(Context context, LuaContext luaCtx, LuaValue layout) {
        super(context);
        addView(new LuaLayout(context)
                .load(layout, luaCtx != null ? luaCtx.getLuaState() : new LuaTable())
                .touserdata(View.class));
    }
}
