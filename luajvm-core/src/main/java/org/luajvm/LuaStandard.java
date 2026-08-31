// ref: linit.c (luaL_openlibs / loadedlibs[])
// java-only 归属说明：C 的 linit.c 是**独立编译单元**，位于 lvm/lstate 之上——它是唯一
//   同时认识"状态"和"全部标准库"的地方，所以 lvm.c 从不 include 任何 l*lib.c。
//   本类把装配归位到独立包，与 C 的分层一致：
//     org.luajvm         装配层（本类，= linit.c）
//       ↑ lib / bind     标准库与 luajava
//       ↑ vm             解释器与调用协议
//       ↑ core           值模型与状态
//   vm/LuaPlatform 只保留 bareGlobals（= lua_newstate）与编译/加载入口，不再 import lib。
package org.luajvm;

import org.luajvm.bind.JavaLib;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaString;
import org.luajvm.core.Varargs;
import org.luajvm.lib.BaseLib;
import org.luajvm.lib.CoroutineLib;
import org.luajvm.lib.DebugLib;
import org.luajvm.lib.IoLib;
import org.luajvm.lib.MathLib;
import org.luajvm.lib.OsLib;
import org.luajvm.lib.PackageLib;
import org.luajvm.lib.StringLib;
import org.luajvm.lib.TableLib;
import org.luajvm.lib.Utf8Lib;
import org.luajvm.vm.LuaCall;
import org.luajvm.vm.LuaPlatform;

public final class LuaStandard {
    private LuaStandard() {
    }

    // linit.c: luaL_openlibs  -  loadedlibs[] 顺序即此处调用顺序（LUA_GNAME 在最前）
    // java diff: C 用 luaL_requiref(L, lib->name, lib->func, 1)；Java 直接以
    //   (modname, env) 调库对象的 call（各库自行写 package.loaded），见各库 call 实现。
    public static Globals standardGlobals() {
        Globals g = LuaPlatform.bareGlobals();

        Varargs libArgs = Varargs.of(LuaString.newStr(""), g);
        LuaCall.callLua(new BaseLib(), libArgs);
        LuaCall.callLua(new PackageLib(), libArgs);
        // java: 手动注册 _G 到 package.loaded
        g.get("package").get("loaded").set("_G", g);
        LuaCall.callLua(new MathLib(), libArgs);
        LuaCall.callLua(new StringLib(), libArgs);
        LuaCall.callLua(new TableLib(), libArgs);
        LuaCall.callLua(new IoLib(), libArgs);
        LuaCall.callLua(new OsLib(), libArgs);
        LuaCall.callLua(new CoroutineLib(), libArgs);
        LuaCall.callLua(new Utf8Lib(), libArgs);
        LuaCall.callLua(new DebugLib(), libArgs);
        // lua.c: pmain -> luaL_openlibs 后栈回到宿主帧初始 top。callLua 入口已退栈
        //   （packResultsAndPop），此处复位仅作防御 - 入口再残留槽会使 main chunk 帧
        //   抬高，cstack.lua 递归深度边界失败。
        if (g.running != null) {
            g.running.top = 1;
        }
        return g;
    }

    // java-only: 标准库 + luajava 宿主扩展（C 无对应；宿主入口 bind.Platform 转发到此）。
    //   luajava 必须在标准库之后装配：import 回退要读全局 require（PackageLib 注册），且各库的 package.loaded 条目须先就位。
    // java diff: 必须显式传 (modname, env) —— Globals.load(LuaValue) 以空参调库函数，JavaLib.call 内的 env.checkglobals() 会收到 nil。
    public static Globals standardGlobalsWithJava() {
        Globals g = standardGlobals();
        LuaCall.callLua(new JavaLib(), Varargs.of(LuaString.newStr("luajava"), g));
        return g;
    }
}
