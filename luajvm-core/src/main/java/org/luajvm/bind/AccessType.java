// java-only: 索引分派缓存键
package org.luajvm.bind;

import org.luajvm.core.LuaValue;

import java.util.HashMap;

/**
 * JavaObject 查找链的访问类型缓存键：同一键在 get()/getJavaMethod()/set() 三条链上
 * 的命中类型，命中后续访直接走该类型跳过前置探测。
 *
 * <p>缓存是 JavaClass 实例的内存态（不持久化、不跨进程），键语义由本 enum 唯一定义，
 * 无编号兼容负担。集合取值/赋值不在此列：它由 JavaClass.isCollectionAccess 按类判定，
 * 不按键缓存（键空间是用户数据，按键缓存无界增长）。
 */
enum AccessType {
    /** get()/getJavaMethod() 链：公有字段。 */
    GETFIELD,
    /** get()/getJavaMethod() 链：方法（getJavaMethod 返回未绑 receiver 的原方法）。 */
    METHOD,
    /** get()/getJavaMethod() 链：内部类。 */
    INNER_CLASS,
    /** get()/getJavaMethod() 链：getter（get/isXxx 探测命中）。 */
    GETTER,
    /** set() 链：字段写入。 */
    SETFIELD,
    /** set() 链：setter（setXxx）。 */
    SETTER,
    /** set() 链：onXxx 监听器（setOnXxxListener 代理）。 */
    SETLISTENER;

    /** 读缓存；null 表示未缓存（首次访问，探测后回填）。 */
    static AccessType of(HashMap<LuaValue, AccessType> cache, LuaValue key) {
        return cache.get(key);
    }

    /** 首次访问时回填缓存类型。 */
    static void putIfAbsent(HashMap<LuaValue, AccessType> cache, LuaValue key, AccessType type) {
        if (cache.get(key) == null) cache.put(key, type);
    }
}
