package org.luajvm.android.api;

import java.util.Map;

/**
 * 跨宿主共享的数据：Android 上每个 Activity/Service 各有一个 {@code Globals}，
 * 想在它们之间传值只能走这里。
 *
 * <p>{@link LuaContext} 的四个角色之一。
 *
 * <p>两张表的区别：
 * <ul>
 *   <li><b>sharedData</b> —— 持久化（落 SharedPreferences），只收得下可序列化的值；</li>
 *   <li><b>globalData</b> —— 纯内存，进程内共享，随进程消失。</li>
 * </ul>
 */
public interface LuaSharedData {

    /** 进程内内存表，随进程消失。 */
    Map<String, Object> getGlobalData();

    Map<String, ?> getSharedData();

    Object getSharedData(String key);

    Object getSharedData(String key, Object defaultValue);

    /** 写入持久化共享数据；值不可序列化时返回 {@code false}。 */
    boolean setSharedData(String key, Object value);
}
