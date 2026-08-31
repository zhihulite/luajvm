// java-only: 调试帧附加字段的绑定访问
package org.luajvm.bind;

import org.luajvm.core.Globals.DebugFrame;
import org.luajvm.core.Globals.DebugFrame.Extras;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaValue;

import java.lang.reflect.Field;

/**
 * DebugFrame.Extras 的字段读写：调试器内部结构（core 的 debug 数据）经 Java 对象
 * 索引暴露给 Lua 侧（断点/观察器扩展位）。
 *
 * <p>从 JavaObject 拆出：通用索引分派不应认识 core 的调试内部结构，调用方以
 * 本类的静态方法作为查找链中的一个策略步骤。
 */
final class DebugFrameAccessor {
    private DebugFrameAccessor() {
    }

    /** 读 extras 的公有字段；非 DebugFrame 包装或字段不存在返回 null。 */
    static LuaValue getField(LuaValue target, LuaValue key) {
        if (!(target.touserdata() instanceof DebugFrame frame)) return null;
        Extras extras = frame.extrasIfPresent();
        if (extras == null) return null;
        try {
            Field f = extras.getClass().getField(key.toJavaString());
            return Coercion.toLua(f.get(extras));
        } catch (NoSuchFieldException e) {
            return null;
        } catch (Exception e) {
            LuaErrors.error(e);
            return null;
        }
    }

    /** 写 extras 的公有字段；非 DebugFrame 包装或字段不存在返回 false。 */
    static boolean setField(LuaValue target, LuaValue key, LuaValue value) {
        if (!(target.touserdata() instanceof DebugFrame frame)) return false;
        Extras extras = frame.extras();
        try {
            Field f = extras.getClass().getField(key.toJavaString());
            f.set(extras, Coercion.toJava(value, f.getType()));
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        } catch (Exception e) {
            LuaErrors.error(e);
            return false;
        }
    }
}
