// java-only: luajava 宿主绑定的标准 Globals 工厂（兼容入口，保持公开 API 不变）
package org.luajvm.bind;

import org.luajvm.LuaStandard;
import org.luajvm.core.Globals;

/**
 * 标准 Globals 工厂（含 luajava 宿主扩展库）。
 *
 * <p>Java 特有：C 无对应。实现在装配层 {@link LuaStandard}（= {@code linit.c}）——
 * 装配是唯一同时认识"状态"与"全部库"的层，放在 {@code bind} 里会让 bind 反向依赖 vm/lib。
 * 本类保留为转发入口：{@code Platform.standardGlobals()} 是宿主（含 luajvm-android 的
 * {@code LuaEngine}）与测试的既有调用点，改名会静默破坏它们。
 */
public final class Platform {
    private Platform() {
    }

    /** 等价 {@link LuaStandard#standardGlobalsWithJava()}。 */
    public static Globals standardGlobals() {
        return LuaStandard.standardGlobalsWithJava();
    }
}
