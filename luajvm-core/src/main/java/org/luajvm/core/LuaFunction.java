// ref: lobject.h (Closure)
// diff: Java用抽象类+子类代替C的三种函数变体(VLCL/VLCF/VCCL)
package org.luajvm.core;

import java.util.Iterator;

public abstract class LuaFunction extends LuaValue {

    // C：lstate.h : 函数通过所属 lua_State 取得 global_State
    // 仅函数保存状态归属；普通标量 LuaValue 不携带 Globals。
    public Globals ownerGlobals;

    // java-only: A/B 开关 - -Dluajvm.libcallonstack=false 禁用库函数 callOnStack 优化
    //   （ConcatFn/GsubFn/GmatchFn/SelectFn/SortFn/PrintFn），用于 wall-time A/B 对照。
    //   不影响既有已验证的 callOnStack 覆盖（InsertFn/ByteFn/FindFn 等）。
    public static final boolean LIB_CALLONSTACK =
            System.getProperty("luajvm.libcallonstack") == null ||
                    Boolean.parseBoolean(System.getProperty("luajvm.libcallonstack"));
    // java diff: C 的 LUA_VLCF 不用 ctb（轻量 C 函数不可收集）；Java 的 LuaFunction 也不用
    //   BIT_ISCOLLECTABLE（性能：标记太多对象），其 GC 标记经 gcRefs() + allFunctions 列表 sweep 处理
    // java-only: allFunctions 列表 - 追踪所有 LUA_VLCF LuaFunction 实例（不含 LuaClosure，
    //   LuaClosure 在 allClosures 中），O(n_functions) 直接迭代定位，免按线程栈逐槽扫描；
    //   存于 table/upvalue 中的 LuaFunction 同样须被重置颜色

    protected LuaFunction() {
        super(LUA_VLCF);
        gcColor = LuaGC.isWhite();
    }

    // java-only: 允许子类指定自己的tt_（LuaClosure/LuaCClosure 用此构造器，不加入 allFunctions）
    protected LuaFunction(int tt_) {
        super(tt_);
        gcColor = LuaGC.isWhite();
    }

    // C：lstate.c : lua_State/global_State 所有权传递
    // Java：函数注册到某个 Globals 时绑定所属状态；无状态的共享纯函数保持 null。
    public final void bindGlobals(Globals globals) {
        if (globals == null || ownerGlobals == globals) return;
        if (ownerGlobals != null) throw LuaErrors.errorObject("function belongs to another Globals");
        ownerGlobals = globals;
        gcColor = LuaGC.isWhite(globals);
        if (!(this instanceof LuaClosure)) globals.gc.allFunctions.add(this);
    }


    // lgc.c: sweep  -  按 gcColor 释放不可达的 LuaFunction
    // java diff: LuaFunction 不调用 checkMemory（无内存记账），sweep 只从 allFunctions 移除死条目
    // 注意：LuaFunction 无 BIT_ISCOLLECTABLE 但 markValue 仍标记它（tt_&0x0F=6 >= TTABLE=5），
    //   需要 sweep 重置颜色，否则下轮 markValue 跳过 BLACK 对象 -> 引用漏标
    static void sweepFunctionsByColor(Globals g) {
        byte cw = LuaGC.isWhite(g);
        boolean inc = LuaGC.isIncrementalMode(g);
        // java diff: 反向索引遍历替代 Iterator，消除 ArrayList$Itr 分配
        for (int j = g.gc.allFunctions.size() - 1; j >= 0; j--) {
            LuaFunction fn = g.gc.allFunctions.get(j);
            if (LuaGC.isdead(g, fn.gcColor)) {
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                g.gc.allFunctions.remove(j);
            } else {
                if (!LuaGC.iswhite(fn.gcColor)) fn.makeWhite(cw);
                fn.gcAge = (byte) (inc ? LuaValue.G_NEW : LuaValue.G_OLD);
            }
        }
    }

    // lgc.c: sweepgen  -  G_NEW->G_SURVIVAL|white；其余前进 age，保持颜色
    // java diff: 对齐 LuaClosure.sweepGen / LuaThread.sweepGen
    static void sweepGen(Globals g, byte cw) {
        Iterator<LuaFunction> it = g.gc.allFunctions.iterator();
        while (it.hasNext()) {
            LuaFunction fn = it.next();
            if (LuaGC.isdead(g, fn.gcColor)) {
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                it.remove();
            } else if (fn.gcAge == LuaValue.G_NEW) {
                fn.makeWhite(cw);
                fn.gcAge = LuaValue.G_SURVIVAL;
            } else {
                switch (fn.gcAge) {
                    case LuaValue.G_SURVIVAL:
                        fn.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD0:
                        fn.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD1:
                        fn.gcAge = LuaValue.G_OLD;
                        break;
                }
            }
        }
    }

    // java-only: 把非白 LuaFunction 重置为当前白色供 GC 重新传播
    // 对齐 LuaClosure.repropagateAll / LuaThread.repropagateAll
    // 仅重置非白色（BLACK/GRAY）对象；白色对象（含 dead old-white）不动，让 sweep 通过 isdead 回收。
    static void repropagateAllFunctions(Globals g, byte cw) {
        for (int _gi = 0, _gn = g.gc.allFunctions.size(); _gi < _gn; _gi++) {
            LuaFunction fn = g.gc.allFunctions.get(_gi);
            if (!LuaGC.iswhite(fn.gcColor)) fn.makeWhite(cw);
        }
    }

    // lobject.h: ttype
    @Override
    public int type() {
        return TFUNCTION;
    }

    // ltm.h: ttypename
    @Override
    public String typeName() {
        return "function";
    }

    // luaL_checktype + lua_tocfunction
    @Override
    public LuaFunction checkfunction() {
        return this;
    }

    // lobject.h: lua_toclosure
    // java-only
    public LuaClosure checkclosure() {
        return (LuaClosure) this;
    }

    // lobject.h: ClosureHeader.nupvalues
    public int nupvalues() {
        return 0;
    }

    // lua_pushfstring(cl)
    // java-only
    @Override
    public String toJavaString() {
        return "function: " + name();
    }

    // cl->p->source 或 cc->func
    // java-only
    public String name() {
        String s = getClass().getName();
        int o = Math.max(s.lastIndexOf('.'), s.lastIndexOf('$')) + 1;
        return s.substring(o);
    }

    // luaD_call / luaD_precall
    public abstract Varargs call(Varargs args);

    // java-only: 基于栈的 C 函数调用，等价于 C 的 lua_CFunction；ldo.c: precallC  -  C 在共享栈上直接调 (*f)(L)
    // Java diff: 参数从 L.stack[func+1..func+narg] 读，结果从 L.stack[L.top] 起压，
    //   并把 L.top 更新为 L.top + nresults。返回压入的结果数；返回 -1 回退 Varargs 路径
    // 子类应 override 以避免 Varargs 打包/解包开销
    public int callOnStack(LuaThread L, int func, int narg) {
        return -1;
    }

    // java-only: 持有 Lua 对象引用的子类（如 CoWrapperFn 持有 LuaThread）必须 override
    //   返回这些引用供 GC 标记；默认返回空数组（无引用，类似纯 C 函数）
    public LuaValue[] gcRefs() {
        return LuaValue.NOVALS;
    }
}

