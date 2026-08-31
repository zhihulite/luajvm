// java-only: 基础库 SPI —— 让 core/vm 无需反向 import lib.BaseLib
package org.luajvm.spi;

import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.io.InputStream;

/**
 * 基础库对外暴露的加载与错误位置能力。
 *
 * <p>Java 特有：C 里 {@code luaL_loadfile}/{@code luaL_where} 在 <b>lauxlib.c</b>
 * （辅助库，不是 lbaselib.c），且 {@code lua_State} 从不持有指向标准库的指针 ——
 * C 的库函数是自由函数，靠 registry 与全局表可达。Java 把实现放在 {@code lib.BaseLib}
 * 实例上（要保存 warn 状态机、cwd 等），于是 {@code Globals} 需要一个字段指向它；
 * 若字段类型直接写 {@code BaseLib}，{@code core} 就反向依赖 {@code lib}。
 *
 * <p>本接口把 {@code Globals} 需要的那 5 个能力抽出来：字段类型改为本接口后，
 * {@code core} 只依赖 {@code spi}（与 {@link Compiler}/{@link Loader}/{@link LuaConfig}
 * 同一模式），实现仍在 {@code lib.BaseLib}，装配时自行登记。
 */
public interface BaseLibrary {

    /** {@code lauxlib.c: luaL_where}  -  给定层级的 {@code "src:line: "} 前缀（无则空串）。 */
    LuaString where(int level);

    /** {@code lbaselib.c: luaB_error}  -  按 level 加位置前缀后抛出。 */
    Varargs err(Varargs args);

    /** {@code lauxlib.c: luaL_loadfile}  -  返回 (chunk) 或 (nil, errmsg)。 */
    Varargs loadFile(String filename, String mode, LuaValue env);

    /** {@code lauxlib.c: luaL_loadbufferx}  -  从流加载，返回 (chunk) 或 (nil, errmsg)。 */
    Varargs loadStream(InputStream is, String chunkname, String mode, LuaValue env);

    /** java-only：按可设置的 cwd 解析相对路径后打开（供 package 搜索器复用）。 */
    InputStream openResource(String filename);

    /** {@code lbaselib.c: luaB_tostring}  -  供宿主直接取用的 tostring 函数对象。 */
    LuaValue tostringFn();
}
