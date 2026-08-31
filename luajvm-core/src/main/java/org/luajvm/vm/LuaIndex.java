// ref: lvm.c, ltm.c
// diff: instanceof+rawget 判断非 tag 系统; rawset 写入非 luaH_finishset; 无移动式 GC 无需锚定表
// diff: finishGet 的 val 在 C 是 StkId 指针（目标寄存器），Java 用 int resSlot 直写目标栈位，
// diff: 避免 JIT 缓存栈数组读取问题；luaG_typeerror 用 stackSlot（原始表位置）给错误信息
package org.luajvm.vm;

import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaGC;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Metamethod;
import org.luajvm.vm.LuaVM;

public final class LuaIndex {
    // MAXTAGLOOP
    private static final int MAXTAGLOOP = 2000;

    private LuaIndex() {
    }

    // lgc.h: luaC_barrierback - 无 VM 栈时仅能用目标表已有的所属状态
    private static void barrierback(LuaThread L, LuaValue p, LuaValue v) {
        Globals g = L != null ? L.l_G : p instanceof LuaTable table ? table.ownerGlobals : null;
        if (g != null) LuaGC.barrierback(g, p, v);
    }

    // ltm.c: luaT_callTMres(L, f, p1, p2, res)  -  调用元方法，结果写到 res 位置
    // java diff: C 用 StkId 指针，Java 用 int 栈索引 resSlot，结果直写 L.stack[resSlot] 而非返回给调用方写
    // java diff: C 的 Protect 宏设 L.top=ci.top 后不恢复；Java 调用后同样设 L.top=ci.top 维持不变量
    // java diff: 2-arg 版本免 Varargs 中间对象（C 直接在栈上操作无需打包）
    private static void callTMres2(LuaThread L, LuaValue fn, LuaValue p1, LuaValue p2, int resSlot) {
        if (L != null && L.ci != null && L.ci.isLuacode()) {
            L.top = L.ci.top;
            int func = L.top;
            LuaVM.checkStack(L, 3);
            L.stack[func] = fn;
            L.stack[func + 1] = p1;
            L.stack[func + 2] = p2;
            L.top = func + 3;
            // ltm.c:  -  isLuacode(ci) -> luaD_call (yieldable)
            LuaCall.callLua(L, func, 1);
            if (resSlot >= 0) {
                L.stack[resSlot] = L.stack[func];
            }
            L.top = L.ci.top;
            return;
        }
        // ltm.c:  -  !isLuacode(ci) -> luaD_callnoyield
        // java diff: 用 callOnStack2to1 直接栈操作，不经 Varargs 打包
        LuaValue res = LuaCall.callOnStack2to1(fn, p1, p2);
        if (resSlot >= 0) {
            L.stack[resSlot] = res;
        }
    }

    // ltm.c: luaT_callTMres  -  3-arg 版本（__newindex: t, k, v）
    // java diff: 3-arg 版本免 Varargs 中间对象
    private static void callTMres3(LuaThread L, LuaValue fn, LuaValue p1, LuaValue p2, LuaValue p3, int resSlot) {
        if (L != null && L.ci != null && L.ci.isLuacode()) {
            L.top = L.ci.top;
            int func = L.top;
            LuaVM.checkStack(L, 4);
            L.stack[func] = fn;
            L.stack[func + 1] = p1;
            L.stack[func + 2] = p2;
            L.stack[func + 3] = p3;
            L.top = func + 4;
            // ltm.c:  -  isLuacode(ci) -> luaD_call (yieldable)
            LuaCall.callLua(L, func, 1);
            if (resSlot >= 0) {
                L.stack[resSlot] = L.stack[func];
            }
            L.top = L.ci.top;
            return;
        }
        // ltm.c:  -  !isLuacode(ci) -> luaD_callnoyield
        LuaValue res = LuaCall.callOnStack3to1(fn, p1, p2, p3);
        if (resSlot >= 0) {
            L.stack[resSlot] = res;
        }
    }

    // lvm.c: luaV_finishget  -  无 L 版本，不写栈直接返回值
    public static LuaValue finishGet(LuaValue t, LuaValue k) {
        return finishGet(t, k, null, -1, -1);
    }

    // lvm.c: luaV_finishget
    // java diff: resSlot = 目标栈位置（写结果），stackSlot = 原始表位置（错误信息）
    public static LuaValue finishGet(LuaValue t, LuaValue k, LuaThread L, int resSlot, int stackSlot) {
        LuaValue originalT = t;
        for (int i = 0; i < MAXTAGLOOP; i++) {
            if (t.istable()) {
                LuaValue r = t.rawget(k);
                if (!r.isnil()) {
                    if (L != null && resSlot >= 0) L.stack[resSlot] = r;
                    return r;
                }
            }
            LuaValue h = Metamethod.INDEX.lookup(t);
            if (h == null || h.isnil()) {
                if (t.istable()) {
                    if (L != null && resSlot >= 0) L.stack[resSlot] = LuaValue.NIL;
                    return LuaValue.NIL;
                }
                if (t == originalT && L != null) {
                    return LuaErrors.typeError(L, stackSlot, t, "index");
                }
                return LuaErrors.typeError(t, "index");
            }
            if (h instanceof LuaFunction fn) {
                if (L != null && resSlot >= 0) {
                    callTMres2(L, fn, t, k, resSlot);
                    return L.stack[resSlot];
                }
                // ltm.c: luaT_callTMres  -  luaD_callnoyield(L, func, 1)
                return LuaCall.callOnStack2to1(fn, t, k);
            }
            t = h;
        }
        LuaErrors.runErrorWithInfo("'__index' chain too long; possible loop");
        return LuaValue.NIL;
    }

    // lvm.c: luaV_finishget  -  VM 快速路径版本，跳过冗余 rawget
    // java diff: C 用 tag 参数区分，Java 用 isTable 布尔（true = 快速路径已查过）
    public static LuaValue finishGetFromVM(LuaValue t, LuaValue k, LuaThread L, int resSlot, int stackSlot, boolean isTable) {
        LuaValue originalT = t;
        for (int i = 0; i < MAXTAGLOOP; i++) {
            LuaValue h;
            if (isTable) {
                // lvm.c: tag!=LUA_VNOTABLE -> t 是表，快路径已做 rawget
                h = Metamethod.INDEX.lookup(t);
                if (h == null || h.isnil()) {
                    // lvm.c: 无元方法 -> 结果为 nil
                    if (L != null && resSlot >= 0) L.stack[resSlot] = LuaValue.NIL;
                    return LuaValue.NIL;
                }
            } else {
                // lvm.c: tag==LUA_VNOTABLE -> 't' 不是表
                LuaValue tm = Metamethod.getTmByObj(L, t, Metamethod.INDEX);
                if (tm == null || tm.isnil()) {
                    if (t == originalT && L != null) {
                        return LuaErrors.typeError(L, stackSlot, t, "index");
                    }
                    return LuaErrors.typeError(t, "index");
                }
                h = tm;
            }
            if (h instanceof LuaFunction fn) {
                if (L != null && resSlot >= 0) {
                    callTMres2(L, fn, t, k, resSlot);
                    return L.stack[resSlot];
                }
                // ltm.c: luaT_callTMres  -  luaD_callnoyield(L, func, 1)
                return LuaCall.callOnStack2to1(fn, t, k);
            }
            // lvm.c: 否则尝试访问 'tm[key]'
            t = h;
            // lvm.c: luaV_fastget(t, key, s2v(val), luaH_get, tag)
            isTable = t.istable();
            if (isTable) {
                LuaValue r = t.rawget(k);
                if (!r.isnil()) {
                    if (L != null && resSlot >= 0) L.stack[resSlot] = r;
                    return r;
                }
            }
        }
        LuaErrors.runErrorWithInfo("'__index' chain too long; possible loop");
        return LuaValue.NIL;
    }

    // luaV_fastgeti + finishGet (int)
    public static LuaValue finishGeti(LuaValue t, int k) {
        return finishGeti(t, k, null, -1, -1);
    }


    // luaV_fastgeti + finishGet (long)
    public static LuaValue finishGeti(LuaValue t, long k) {
        return finishGeti(t, k, null, -1, -1);
    }

    // lvm.c: luaV_finishget
    public static LuaValue finishGeti(LuaValue t, long k, LuaThread L, int resSlot, int stackSlot) {
        if (k >= Integer.MIN_VALUE && k <= Integer.MAX_VALUE)
            return finishGetiInt(t, (int) k, L, resSlot, stackSlot);
        return finishGetiLong(t, k, L, resSlot, stackSlot);
    }


    // luaV_fastgeti + finishGeti (int)
    private static LuaValue finishGetiInt(LuaValue t, int k) {
        return finishGetiInt(t, k, null, -1, -1);
    }

    private static LuaValue finishGetiInt(LuaValue t, int k, LuaThread L, int resSlot, int stackSlot) {
        LuaValue originalT = t;
        for (int i = 0; i < MAXTAGLOOP; i++) {
            if (t instanceof LuaTable table) {
                LuaValue r = table.getInt(k);
                if (!r.isnil()) {
                    if (L != null && resSlot >= 0) L.stack[resSlot] = r;
                    return r;
                }
            }
            LuaValue h = Metamethod.INDEX.lookup(t);
            if (h == null || h.isnil()) {
                if (t.istable()) {
                    if (L != null && resSlot >= 0) L.stack[resSlot] = LuaValue.NIL;
                    return LuaValue.NIL;
                }
                if (t == originalT && L != null) {
                    return LuaErrors.typeError(L, stackSlot, t, "index");
                }
                return LuaErrors.typeError(t, "index");
            }
            if (h instanceof LuaFunction fn) {
                if (L != null && resSlot >= 0) {
                    callTMres2(L, fn, t, LuaInteger.valueOf(k), resSlot);
                    return L.stack[resSlot];
                }
                // ltm.c: luaT_callTMres  -  luaD_callnoyield(L, func, 1)
                return LuaCall.callOnStack2to1(fn, t, LuaInteger.valueOf(k));
            }
            t = h;
        }
        LuaErrors.runErrorWithInfo("'__index' chain too long; possible loop");
        return LuaValue.NIL;
    }

    // luaV_fastgeti + finishGeti (long)
    private static LuaValue finishGetiLong(LuaValue t, long k) {
        return finishGetiLong(t, k, null, -1, -1);
    }

    private static LuaValue finishGetiLong(LuaValue t, long k, LuaThread L, int resSlot, int stackSlot) {
        LuaValue originalT = t;
        for (int i = 0; i < MAXTAGLOOP; i++) {
            if (t instanceof LuaTable table) {
                LuaValue r = table.getInt(k);
                if (!r.isnil()) {
                    if (L != null && resSlot >= 0) L.stack[resSlot] = r;
                    return r;
                }
            }
            LuaValue h = Metamethod.INDEX.lookup(t);
            if (h == null || h.isnil()) {
                if (t.istable()) {
                    if (L != null && resSlot >= 0) L.stack[resSlot] = LuaValue.NIL;
                    return LuaValue.NIL;
                }
                if (t == originalT && L != null) {
                    return LuaErrors.typeError(L, stackSlot, t, "index");
                }
                return LuaErrors.typeError(t, "index");
            }
            if (h instanceof LuaFunction fn) {
                if (L != null && resSlot >= 0) {
                    callTMres2(L, fn, t, LuaInteger.valueOf(k), resSlot);
                    return L.stack[resSlot];
                }
                // ltm.c: luaT_callTMres  -  luaD_callnoyield(L, func, 1)
                return LuaCall.callOnStack2to1(fn, t, LuaInteger.valueOf(k));
            }
            t = h;
        }
        LuaErrors.runErrorWithInfo("'__index' chain too long; possible loop");
        return LuaValue.NIL;
    }

    // lvm.c: luaV_finishget  -  VM 快速路径版本，跳过冗余 rawget
    // java diff: C 用 tag 参数区分 LUA_VNOTABLE 与 tagisempty，Java 用 isTable 布尔值
    public static LuaValue finishGetiFromVM(LuaValue t, int k, LuaThread L, int resSlot, int stackSlot, boolean isTable) {
        LuaValue originalT = t;
        for (int i = 0; i < MAXTAGLOOP; i++) {
            LuaValue h;
            if (isTable) {
                // lvm.c: tag!=LUA_VNOTABLE -> t 是表，快路径已做 rawget
                h = Metamethod.INDEX.lookup(t);
                if (h == null || h.isnil()) {
                    if (L != null && resSlot >= 0) L.stack[resSlot] = LuaValue.NIL;
                    return LuaValue.NIL;
                }
            } else {
                // lvm.c: tag==LUA_VNOTABLE -> 't' 不是表
                LuaValue tm = Metamethod.getTmByObj(L, t, Metamethod.INDEX);
                if (tm == null || tm.isnil()) {
                    if (t == originalT && L != null) {
                        return LuaErrors.typeError(L, stackSlot, t, "index");
                    }
                    return LuaErrors.typeError(t, "index");
                }
                h = tm;
            }
            if (h instanceof LuaFunction fn) {
                if (L != null && resSlot >= 0) {
                    callTMres2(L, fn, t, LuaInteger.valueOf(k), resSlot);
                    return L.stack[resSlot];
                }
                return LuaCall.callOnStack2to1(fn, t, LuaInteger.valueOf(k));
            }
            // lvm.c: 否则尝试访问 'tm[key]'
            t = h;
            // lvm.c: luaV_fastget(t, key, s2v(val), luaH_get, tag)
            isTable = t.istable();
            if (isTable) {
                LuaValue r = t.rawget(LuaInteger.valueOf(k));
                if (!r.isnil()) {
                    if (L != null && resSlot >= 0) L.stack[resSlot] = r;
                    return r;
                }
            }
        }
        LuaErrors.runErrorWithInfo("'__index' chain too long; possible loop");
        return LuaValue.NIL;
    }

    // lvm.c: luaV_finishset
    public static void finishSet(LuaValue t, LuaValue k, LuaValue v) {
        finishSet(t, k, v, null, -1);
    }

    // lvm.c: luaV_finishset
    public static void finishSet(LuaValue t, LuaValue k, LuaValue v, LuaThread L) {
        finishSet(t, k, v, L, -1);
    }

    // lvm.c: luaV_finishset  -  无 hres 版本，如 testC settable
    // java diff: 先做 fastset 取 hres 编码
    public static void finishSet(LuaValue t, LuaValue k, LuaValue v, LuaThread L, int stackSlot) {
        int hres;
        if (t instanceof LuaTable table) {
            hres = table.pset(k, v);
            if (hres == LuaTable.HOK) {
                barrierback(L, table, v);
                return;
            }
        } else {
            hres = LuaTable.HNOTATABLE;
        }
        finishSet(t, k, v, L, stackSlot, hres);
    }

    // lvm.c: luaV_finishset
    // java diff: hres 编码来自 fastset，HNOTATABLE 表示非表，需重新判断
    public static void finishSet(LuaValue t, LuaValue k, LuaValue v, LuaThread L, int stackSlot, int hres) {
        for (int i = 0; i < MAXTAGLOOP; i++) {
            LuaValue tm;
            // lvm.c: hres != HNOTATABLE -> 't' 是表吗？
            if (hres != LuaTable.HNOTATABLE) {
                LuaTable h = (LuaTable) t;
                tm = Metamethod.NEWINDEX.lookup(t);
                if (tm == null) {
                    // lvm.c: 无元方法 -> luaH_finishset + luaC_barrier
                    h.finishSet(k, v, hres);
                    h.invalidateTMcache();
                    barrierback(L, h, v);
                    return;
                }
            } else {
                // lvm.c: 不是表，检查元方法
                tm = Metamethod.NEWINDEX.lookup(t);
                if (tm == null || tm.isnil()) {
                    if (L != null && stackSlot != -1) {
                        LuaErrors.typeError(L, stackSlot, t, "index");
                    } else {
                        LuaErrors.typeError(t, "index");
                    }
                    return;
                }
            }
            // lvm.c: 尝试元方法
            if (tm instanceof LuaFunction fn) {
                callTMres3(L, fn, t, k, v, -1);
                return;
            }
            // lvm.c: t = tm 后再次 luaV_fastset；内联 luaV_fastset 并保存表指针 h，防止 luaH_pset 后 t 被 GC 改变
            t = tm;
            if (t instanceof LuaTable table) {
                // java diff: C 用 Table *h = hvalue(t) 保存，Java 的 pattern variable table 已等价保存
                hres = table.pset(k, v);
                if (hres == LuaTable.HOK) {
                    // lvm.h: luaV_finishfastset
                    barrierback(L, table, v);
                    return;
                }
            } else {
                hres = LuaTable.HNOTATABLE;
            }
        }
        LuaErrors.runErrorWithInfo("'__newindex' chain too long; possible loop");
    }


    // luaV_fastseti + finishset (int)  -  无 hres 版本
    public static void finishSeti(LuaValue t, int k, LuaValue v) {
        finishSeti(t, k, v, null, -1);
    }


    // luaV_fastseti + finishset (long)
    public static void finishSeti(LuaValue t, long k, LuaValue v) {
        finishSeti(t, k, v, null);
    }

    // lvm.c: luaV_finishseti
    public static void finishSeti(LuaValue t, long k, LuaValue v, LuaThread L) {
        finishSeti(t, k, v, L, -1);
    }

    // lvm.c: luaV_finishseti  -  无 hres 版本，先做 fastset
    public static void finishSeti(LuaValue t, long k, LuaValue v, LuaThread L, int stackSlot) {
        int hres;
        if (t instanceof LuaTable table && k >= Integer.MIN_VALUE && k <= Integer.MAX_VALUE) {
            hres = table.fastSeti((int) k, v);
            if (hres == LuaTable.HOK) {
                barrierback(L, table, v);
                return;
            }
        } else if (t instanceof LuaTable) {
            hres = LuaTable.HNOTFOUND;
        } else {
            hres = LuaTable.HNOTATABLE;
        }
        finishSeti(t, k, v, L, stackSlot, hres);
    }

    // lvm.c: luaV_finishseti  -  用 fastseti 的 hres
    public static void finishSeti(LuaValue t, long k, LuaValue v, LuaThread L, int stackSlot, int hres) {
        if (k >= Integer.MIN_VALUE && k <= Integer.MAX_VALUE) {
            finishSetiInt(t, (int) k, v, L, stackSlot, hres);
            return;
        }
        finishSetiLong(t, k, v, L, stackSlot, hres);
    }


    // luaV_fastseti + finishseti (int)
    private static void finishSetiInt(LuaValue t, int k, LuaValue v) {
        finishSetiInt(t, k, v, null, -1, LuaTable.HNOTATABLE);
    }

    private static void finishSetiInt(LuaValue t, int k, LuaValue v, LuaThread L) {
        finishSetiInt(t, k, v, L, -1, LuaTable.HNOTATABLE);
    }

    // lvm.c: luaV_finishseti  -  带 hres 编码的 int 键
    private static void finishSetiInt(LuaValue t, int k, LuaValue v, LuaThread L, int stackSlot, int hres) {
        for (int i = 0; i < MAXTAGLOOP; i++) {
            LuaValue tm;
            // lvm.c: hres != HNOTATABLE -> 't' 是表吗？
            if (hres != LuaTable.HNOTATABLE) {
                LuaTable h = (LuaTable) t;
                tm = Metamethod.NEWINDEX.lookup(t);
                if (tm == null) {
                    h.finishSet(LuaInteger.valueOf(k), v, hres);
                    h.invalidateTMcache();
                    barrierback(L, h, v);
                    return;
                }
            } else {
                tm = Metamethod.NEWINDEX.lookup(t);
                if (tm == null || tm.isnil()) {
                    if (L != null && stackSlot != -1) {
                        LuaErrors.typeError(L, stackSlot, t, "index");
                    } else {
                        LuaErrors.typeError(t, "index");
                    }
                    return;
                }
            }
            if (tm instanceof LuaFunction fn) {
                callTMres3(L, fn, t, LuaInteger.valueOf(k), v, -1);
                return;
            }
            t = tm;
            if (t instanceof LuaTable table) {
                hres = table.fastSeti(k, v);
                if (hres == LuaTable.HOK) {
                    barrierback(L, table, v);
                    return;
                }
            } else {
                hres = LuaTable.HNOTATABLE;
            }
        }
        LuaErrors.runErrorWithInfo("'__newindex' chain too long; possible loop");
    }

    // luaV_fastseti + finishseti (long)
    private static void finishSetiLong(LuaValue t, long k, LuaValue v) {
        finishSetiLong(t, k, v, null, -1, LuaTable.HNOTATABLE);
    }

    // lvm.c: luaV_finishseti  -  带 hres 编码的 long 键
    private static void finishSetiLong(LuaValue t, long k, LuaValue v, LuaThread L, int stackSlot, int hres) {
        for (int i = 0; i < MAXTAGLOOP; i++) {
            LuaValue tm;
            if (hres != LuaTable.HNOTATABLE) {
                LuaTable h = (LuaTable) t;
                tm = Metamethod.NEWINDEX.lookup(t);
                if (tm == null) {
                    h.finishSet(LuaInteger.valueOf(k), v, hres);
                    h.invalidateTMcache();
                    barrierback(L, h, v);
                    return;
                }
            } else {
                tm = Metamethod.NEWINDEX.lookup(t);
                if (tm == null || tm.isnil()) {
                    if (L != null && stackSlot != -1) {
                        LuaErrors.typeError(L, stackSlot, t, "index");
                    } else {
                        LuaErrors.typeError(t, "index");
                    }
                    return;
                }
            }
            if (tm instanceof LuaFunction fn) {
                callTMres3(L, fn, t, LuaInteger.valueOf(k), v, -1);
                return;
            }
            t = tm;
            if (t instanceof LuaTable table) {
                hres = (k >= Integer.MIN_VALUE && k <= Integer.MAX_VALUE) ? table.fastSeti((int) k, v) : LuaTable.HNOTFOUND;
                if (hres == LuaTable.HOK) {
                    barrierback(L, table, v);
                    return;
                }
            } else {
                hres = LuaTable.HNOTATABLE;
            }
        }
        LuaErrors.runErrorWithInfo("'__newindex' chain too long; possible loop");
    }

    // OP_GETFIELD/OP_SETFIELD
    public static LuaValue finishGetField(LuaValue t, String k) {
        return finishGet(t, LuaString.newStr(k));
    }

    public static void finishSetField(LuaValue t, String k, LuaValue v) {
        finishSet(t, LuaString.newStr(k), v);
    }


}
