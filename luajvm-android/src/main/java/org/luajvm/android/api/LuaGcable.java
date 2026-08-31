package org.luajvm.android.api;

/**
 * 可 GC 管理的对象接口。
 * <p>
 * 实现此接口的对象由 LuaEngine 的 GC 列表追踪，
 * 引擎销毁时统一调用 {@link #gc()} 释放资源。
 */
public interface LuaGcable {
    void gc();

    boolean isGc();
}
