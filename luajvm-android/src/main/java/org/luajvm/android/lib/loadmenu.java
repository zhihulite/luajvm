package org.luajvm.android.lib;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;

import org.luajvm.android.api.LuaContext;

import org.luajvm.bind.Coercion;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.util.HashMap;
import java.util.Map;

public class loadmenu extends LuaFunction {
    private final LuaContext mLuaContext;
    private final Context mContext;
    private final loadbitmap mLoadBitmap;

    public loadmenu(LuaContext context) {
        mLuaContext = context;
        mContext = context.getContext();
        mLoadBitmap = new loadbitmap(context);
    }

    @Override
    public Varargs call(Varargs args) {
        Menu menu = (Menu) args.arg1().checkuserdata(Menu.class);
        LuaValue items = args.arg(2);
        if (!items.istable()) {
            throw LuaErrors.errorObject("menu config must be a table");
        }

        Map<String, MenuItem> idMap = new HashMap<>();
        loadMenu(menu, items, idMap);

        return Coercion.toLua(idMap);
    }

    private void loadMenu(Menu parent, LuaValue items, Map<String, MenuItem> idMap) {
        for (int i = 1; i <= items.length(); i++) {
            LuaValue cfg = items.get(i);
            if (!cfg.istable()) continue;
            LuaValue subItems = cfg.get("items");
            boolean hasSub = subItems.istable();

            int itemId = cfg.get("itemId").optint(0);
            if (itemId <= 0) itemId = View.generateViewId();
            int group = cfg.get("group").optint(0);
            if (group < 0) group = Menu.NONE;
            int order = cfg.get("order").optint(Menu.NONE);
            String title = cfg.get("title").optJavaString("");
            MenuItem menuItem;
            if (hasSub) {
                SubMenu subMenu = parent.addSubMenu(group, itemId, order, title);
                menuItem = subMenu.getItem();
                loadMenu(subMenu, subItems, idMap);
            } else {
                menuItem = parent.add(group, itemId, order, title);
            }

            // 图标

            LuaValue icon = cfg.get("icon");
            if (!icon.isnil()) setIcon(menuItem, icon);

            // 非子菜单属性

            if (!hasSub) {
                LuaValue asAction = cfg.get("asAction");
                if (!asAction.isnil()) {
                    menuItem.setShowAsAction(parseActionFlags(asAction));
                }

                LuaValue click = cfg.get("click");
                if (click.isfunction()) {
                    menuItem.setOnMenuItemClickListener(item -> {
                        // 菜单分发在主线程：Lua 脚本错误裸冒泡会沿 CrashHandler 杀进程
                        try {
                            LuaCall.invoke(click, Coercion.toLua(menuItem));
                        } catch (Exception e) {
                            mLuaContext.sendError("loadmenu", e);
                        }
                        return true;
                    });
                }

                LuaValue id = cfg.get("id");
                if (id.isstring()) idMap.put(id.toJavaString(), menuItem);
            }

            // 通用属性

            if (!cfg.get("enabled").optboolean(true)) menuItem.setEnabled(false);
            if (!cfg.get("visible").optboolean(true)) menuItem.setVisible(false);
            if (cfg.get("checkable").optboolean(false)) menuItem.setCheckable(true);
            if (cfg.get("checked").optboolean(false)) menuItem.setChecked(true);
        }
    }

    private void setIcon(MenuItem item, LuaValue icon) {
        Drawable drawable = null;
        if (icon.isstring()) {
            Varargs result = LuaCall.invoke(mLoadBitmap, LuaValue.valueOf(icon.toJavaString()));
            Object obj = result.arg1().touserdata();
            if (obj instanceof Bitmap bmp) {
                drawable = new BitmapDrawable(mContext.getResources(), bmp);
            }
        } else if (icon.isuserdata()) {
            Object obj = icon.touserdata();
            if (obj instanceof Drawable d) drawable = d;
            else if (obj instanceof Bitmap bmp)
                drawable = new BitmapDrawable(mContext.getResources(), bmp);
        }
        if (drawable != null) item.setIcon(drawable);
    }

    private int parseActionFlags(LuaValue flags) {
        if (flags.isnumber()) return flags.toint();
        if (flags.isstring()) {
            int result = 0;
            for (String word : flags.toJavaString().split("\\|")) {
                result |= switch (word.trim()) {
                    case "never" -> MenuItem.SHOW_AS_ACTION_NEVER;
                    case "ifRoom" -> MenuItem.SHOW_AS_ACTION_IF_ROOM;
                    case "always" -> MenuItem.SHOW_AS_ACTION_ALWAYS;
                    case "withText" -> MenuItem.SHOW_AS_ACTION_WITH_TEXT;
                    case "collapseActionView" -> MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW;
                    default -> throw LuaErrors.errorObject("unknown showAsAction flag: " + word);
                };
            }
            return result;
        }
        throw LuaErrors.errorObject("showAsAction must be number or string, got " + flags.typeName());
    }
}
