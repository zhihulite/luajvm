// ref: lbaselib.c (luaB_next)
// java-only 归属说明：FlatTFor 的 generic-for 内联需要在 VM 侧判定"迭代器就是标准
//   `next`"（C 无此判定——C 的 OP_TFORCALL 一律走通用调用，见 lvm.c: luaV_execute）。
//   本接口把"我就是
//   标准 next"这个身份声明放到 core：lib 侧的实现类挂上它，vm 侧只认接口。
package org.luajvm.core;

/**
 * java-only 标记接口：实现者是标准库的 {@code next}（或与其行为完全一致的 {@code inext}）。
 *
 * <p>由 {@code vm.FlatTFor}／{@code vm.LuaVM} 的 generic-for 内联通道用于身份 guard；
 * 挂上本接口等于承诺"语义与 {@code luaB_next} 一致"，故只应由标准库实现类实现。
 */
public interface NextMark {
}
