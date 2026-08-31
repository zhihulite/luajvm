// java-only 归属说明：FlatTFor 的 TFOR 整循环扁平化需要区分两类标准迭代器 ——
//   next（键序未指定、逐 node 扫）与 ipairs（严格按整数键 1..n、遇 nil 停）。
//   两者的扁平执行语义不同（见 vm.FlatTFor.tryRunTFor 的 ipairsMode 分支），故用两个
//   标记接口而非一个。C 无此判定（lvm.c 的 OP_TFORCALL 一律通用调用）。
package org.luajvm.core;

/**
 * java-only 标记接口：实现者是标准 {@code ipairs} 的迭代函数（lbaselib.c ipairsaux：
 * 按 {@code t[i]} 整数键序取值，{@code nil} 即停；经 {@code lua_geti} 受 {@code __index}
 * 影响 —— 故带元表的表必须由调用方回退装箱路径）。
 *
 * <p>挂上本接口等于承诺「语义与 {@code ipairsaux} 一致」，只应由标准库实现类实现。
 */
public interface IpairsMark {
}
