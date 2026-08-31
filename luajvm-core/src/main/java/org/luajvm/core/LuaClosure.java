// ref: lobject.h (LClosure)
// diff: C中LClosure和CClosure是两个独立结构体；Java合并为LuaClosure+LuaFunction
package org.luajvm.core;

import org.luajvm.vm.LuaCall;

public class LuaClosure extends LuaFunction {
    // lobject.h: sizeof(LClosure)  -  ClosureHeader(24) + Proto*(8) + UpVal*[1](8) = 40
    private static final long CLOSURE_HEADER_BYTES = 40L;
    private static final long UPVAL_REF_BYTES = 8L;
    // Proto  -  函数原型指针
    public final Prototype p;
    // LClosure.upvals  -  upvalue数组，共享UpVal语义（debug.upvalueid 依赖同一实例）
    public final UpVal[] upvals;

    // lfunc.c: luaF_newLclosure  -  创建带 nupvalues 个上值的新 Lua 闭包
    // C: luaC_newobj(L, LUA_VLCL, sizeLclosure(n)) + while(nupvals--) c->upvals[nupvals]=NULL
    // java diff: C用luaC_newobj走frealloc；Java用luaM_checkmemory追踪字节+allClosures列表
    public LuaClosure(Prototype p, Globals env) {
        super(LUA_VLCL | BIT_ISCOLLECTABLE);
        ownerGlobals = env;
        int nup = p != null ? p.sizeupvalues : 0;
        long closureBytes = CLOSURE_HEADER_BYTES + nup * UPVAL_REF_BYTES;
        if (env == null) throw LuaErrors.errorObject("LuaClosure requires Globals");
        LuaGC.checkMemory(env, closureBytes);
        gcColor = LuaGC.isWhite(env);
        env.gc.allClosures.add(this);
        if (p != null) p.bindGlobals(env);
        // lobject.h: luaC_checkGC
        if (env != null) env.gcCheck(64);
        this.p = p;

        this.upvals = p != null ? new UpVal[p.sizeupvalues] : new UpVal[0];
        LuaGC.commitRealloc(env, 0, closureBytes);
    }

    // java-only
    public static int managedClosureCount(Globals g) {
        return g.gc.allClosures.size();
    }

    // lgc.c: sweep  -  按 gcColor 释放不可达闭包
    // java diff: 反向索引遍历替代 Iterator（消除 ArrayList$Itr 分配）；remove(j) shift 只影响尾部
    static void sweepClosuresByColor(Globals g) {
        byte cw = LuaGC.isWhite(g);
        boolean inc = LuaGC.isIncrementalMode(g);
        for (int j = g.gc.allClosures.size() - 1; j >= 0; j--) {
            LuaClosure cl = g.gc.allClosures.get(j);
            if (LuaGC.isdead(g, cl.gcColor)) {
                int nup = cl.upvals != null ? cl.upvals.length : 0;
                long closureBytes = CLOSURE_HEADER_BYTES + nup * UPVAL_REF_BYTES;
                LuaGC.free(g, closureBytes);
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                g.gc.allClosures.remove(j);
            } else {
                // java diff: fullGC 模式此处设 G_OLD（消除 agesAfterFullGC 的 O(n) 遍历）
                if (!LuaGC.iswhite(cl.gcColor)) cl.makeWhite(cw);
                cl.gcAge = (byte) (inc ? LuaValue.G_NEW : LuaValue.G_OLD);
            }
        }
    }

    // lgc.c: sweepgen  -  G_NEW->G_SURVIVAL|white；其余前进 age，保持颜色
    static void sweepGen(Globals g, byte cw) {
        for (int j = g.gc.allClosures.size() - 1; j >= 0; j--) {
            LuaClosure cl = g.gc.allClosures.get(j);
            if (LuaGC.isdead(g, cl.gcColor)) {
                int nup = cl.upvals != null ? cl.upvals.length : 0;
                long closureBytes = CLOSURE_HEADER_BYTES + nup * UPVAL_REF_BYTES;
                LuaGC.free(g, closureBytes);
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                g.gc.allClosures.remove(j);
            } else if (cl.gcAge == LuaValue.G_NEW) {
                cl.makeWhite(cw);
                cl.gcAge = LuaValue.G_SURVIVAL;
            } else {
                switch (cl.gcAge) {
                    case LuaValue.G_SURVIVAL:
                        cl.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD0:
                        cl.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD1:
                        cl.gcAge = LuaValue.G_OLD;
                        break;
                }
            }
        }
    }

    // java-only: T.totalmem 限制下轻量紧急 sweep - no-op（闭包通常经原型/栈可达，表 sweep 已够）
    static void sweepClosuresQuick(Globals g) {
    }

    static void repropagateAll(Globals g, byte cw) {
        for (int _gi = 0, _gn = g.gc.allClosures.size(); _gi < _gn; _gi++) {
            LuaClosure c = g.gc.allClosures.get(_gi);
            if (!LuaGC.iswhite(c.gcColor)) c.makeWhite(cw);
        }
    }

    // lgc.c: objsize  -  GC 记账的近似大小
    @Override
    public int gcSize() {
        return 64 + (upvals != null ? upvals.length * 8 : 0);
    }

    // lobject.h: ClosureHeader.nupvalues
    @Override
    public int nupvalues() {
        return upvals.length;
    }

    // ldo.c: luaD_call
    // java diff: C 参数已在栈上；Java 从 Varargs 入口先压栈
    @Override
    public Varargs call(Varargs a) {
        return LuaCall.callLua(this, a);
    }

    // cl->p->source
    @Override
    public String name() {
        return p != null && p.source != null ? p.source.toJavaString() : super.name();
    }

}
