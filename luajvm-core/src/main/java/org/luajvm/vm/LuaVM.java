// ref: lvm.c
// diff: goto->while+continue | fastget/set->LuaIndex | pushclosure+barrier->new LuaClosure
// diff: luaF_newtbcupval(delta链表)->直接设置tbclist | luaH_new+resize+checkGC->new LuaTable
// diff: luaF_close->手动遍历openupval/tbclist
package org.luajvm.vm;

import org.luajvm.compiler.Opcodes;
import org.luajvm.bind.JavaObject;
import org.luajvm.core.LuaDebug;
import org.luajvm.core.BinaryOp;
import org.luajvm.core.CallInfo;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaGC;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaNumber;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Metamethod;
import org.luajvm.core.Prototype;
import org.luajvm.core.UnaryOp;
import org.luajvm.core.UpVal;
import org.luajvm.core.Varargs;
import org.luajvm.vm.FlatArith;
import org.luajvm.vm.LuaArith;
import org.luajvm.vm.LuaCall;
import org.luajvm.vm.LuaCompare;
import org.luajvm.vm.LuaConcat;
import org.luajvm.vm.LuaIndex;

import java.util.ArrayList;
import java.util.Arrays;

public final class LuaVM {
    // LUA_HOOK*
    public static final int LUA_HOOKCALL = 0;
    public static final int LUA_HOOKRET = 1;
    public static final int LUA_HOOKLINE = 2;
    public static final int LUA_HOOKCOUNT = 3;
    public static final int LUA_HOOKTAILCALL = 4;

    // -- 栈管理 --
    // ====================================================================
    // 扁平算术核差分对拍门控(编译期常量)
    // ====================================================================
    // false(默认发行)：JIT 对下方 shadow 块做死代码消除，零字节码开销、零回退。
    // true(验证构建)：每条 int/float 算术快路径除装箱结果外，再用 FlatArith 在
    //   long+byte 上算一遍影子结果逐位比对，失配即抛（确证扁平核与装箱路径数值一致）。
    static final boolean SHADOW_FLAT = Boolean.getBoolean("luajvm.shadowflat");
    // OP_SETLIST 内联优化 A/B 开关：默认 true（内联 obj2arr + 单次 barrier）；
    // false 走 setInt 双重 barrier 路径。用 -Dluajvm.setlistopt=false 关闭。
    static final boolean SETLIST_OPT =
            !"false".equalsIgnoreCase(System.getProperty("luajvm.setlistopt"));
    // ldo.c: luaD_reallocstack
    private static final int STACKERRSPACE = 200;
    // ldo.c: LUAI_MAXSTACK（默认 1000000；测试版 ltests.h 覆盖为 68000）
    // java diff: C 编译期 #define -> Java 系统属性可配置（-Dluajvm.maxstack=68000 对齐测试版）
    private static final int MAXSTACK =
            Integer.getInteger("luajvm.maxstack", 1_000_000);
    private static final int ERRORSTACKSIZE = MAXSTACK + STACKERRSPACE;
    // lvm.c: luaV_shiftl
    private static final int NBITS = 64;
    // ldo.c: luaD_hook
    private static final String[] HOOKNAMES = {"call", "return", "line", "count", "tail call"};
    // ldebug.c: changedline
    private static final int MAXIWTHABS = 128;  // 绝对行号阈值

    private LuaVM() {
    }

    // 影子对拍：把装箱结果 r 与 FlatArith 的扁平结果逐位比对。
    // 仅在 SHADOW_FLAT=true 时调用(否则整块被 DCE)。失配抛错，定位到具体操作数。
    private static void shadowCheckArith(LuaValue v1, LuaValue v2, BinaryOp op, LuaValue r) {
        // 仅对 int/float 数值快路径对拍；非数值(走元方法)的 r 由装箱路径负责，跳过。
        int t1 = v1.tt_, t2 = v2.tt_;
        boolean num1 = t1 == LuaValue.LUA_VNUMINT || t1 == LuaValue.LUA_VNUMFLT;
        boolean num2 = t2 == LuaValue.LUA_VNUMINT || t2 == LuaValue.LUA_VNUMFLT;
        if (!num1 || !num2) return;
        long f1 = t1 == LuaValue.LUA_VNUMINT ? ((LuaInteger) v1).v : FlatArith.encFlt(((LuaFloat) v1).v);
        byte ft1 = t1 == LuaValue.LUA_VNUMINT ? FlatArith.T_INT : FlatArith.T_FLT;
        long f2 = t2 == LuaValue.LUA_VNUMINT ? ((LuaInteger) v2).v : FlatArith.encFlt(((LuaFloat) v2).v);
        byte ft2 = t2 == LuaValue.LUA_VNUMINT ? FlatArith.T_INT : FlatArith.T_FLT;
        long[] outv = new long[1];
        byte[] outt = new byte[1];
        boolean ok = FlatArith.arith(op, f1, ft1, f2, ft2, outv, outt, 0);
        // flat core 是保守子集：位运算含浮点等情形不覆盖，跳过对拍（由装箱路径负责）
        if (!ok) return;
        // 逐位比对：整数比 long 位，浮点比 raw bits(含 NaN 位型/+/-0)。
        boolean rInt = r.tt_ == LuaValue.LUA_VNUMINT;
        boolean fInt = outt[0] == FlatArith.T_INT;
        if (rInt != fInt) throw new IllegalStateException("SHADOW_FLAT: tag mismatch op=" + op
                + " boxed=" + (rInt ? "int" : "flt") + " flat=" + (fInt ? "int" : "flt"));
        if (rInt) {
            long rb = ((LuaInteger) r).v;
            if (rb != outv[0]) throw new IllegalStateException("SHADOW_FLAT: int mismatch op=" + op
                    + " boxed=" + rb + " flat=" + outv[0]);
        } else {
            long rb = Double.doubleToRawLongBits(((LuaFloat) r).v);
            if (rb != outv[0])
                throw new IllegalStateException("SHADOW_FLAT: flt raw-bits mismatch op=" + op
                        + " boxed=" + rb + " flat=" + outv[0]);
        }
    }

    // -- 辅助方法 --

    // lvm.c:  -  savestate(L,ci) = savepc(ci) + L.top = ci.top
    private static void savestate(LuaThread L, CallInfo ci, int pc) {
        ci.savedpc = pc;
        L.top = ci.top;
    }

    // ldo.c: correctstack
    private static void correctstack(LuaThread L, LuaValue[] oldStack) {
        if (L.openupval != null) {
            for (int _gi = 0, _gn = L.openupval.size(); _gi < _gn; _gi++) {
                UpVal uv = L.openupval.get(_gi);
                uv.rebindStack(oldStack, L.stack);
            }
        }
        for (CallInfo ci = L.ci; ci != null; ci = ci.previous) {
            if (ci.isLua()) {
                ci.trap = true;
            }
        }
    }

    // ldo.c: luaD_reallocstack
    public static int reallocStack(LuaThread L, int newsize, int raiseerror) {
        LuaValue[] oldStack = L.stack;
        if (oldStack == null) {
            // java diff: 线程已被GC回收（closeFromCollector设stack=null），不应再reallocStack
            if (raiseerror != 0)
                throw new RuntimeException("attempt to realloc stack of freed thread");
            return 0;
        }
        int oldsize = L.stack_last;
        try {
            LuaValue[] newStack = new LuaValue[newsize];
            System.arraycopy(oldStack, 0, newStack, 0, Math.min(oldStack.length, newsize));
            if (oldsize < newsize) {
                Arrays.fill(newStack, oldsize, newsize, LuaValue.NIL);
            }
            L.stack = newStack;
            L.stack_last = newsize;
            correctstack(L, oldStack);
            return 1;
        } catch (OutOfMemoryError oom) {
            if (raiseerror != 0) {
                throw oom;
            }
            return 0;
        }
    }

    // ldo.c: luaD_growstack
    public static int growStack(LuaThread L, int n, int raiseerror) {
        int size = L.stack_last;
        if (size > MAXSTACK) {
            if (raiseerror != 0) {
                errErr();
            }
            return 0;
        } else if (n < MAXSTACK) {
            int newsize = size + (size >> 1);
            int needed = L.top + n;
            if (newsize > MAXSTACK) {
                newsize = MAXSTACK;
            }
            if (newsize < needed) {
                newsize = needed;
            }
            if (newsize <= MAXSTACK) {
                return reallocStack(L, newsize, raiseerror);
            }
        }
        reallocStack(L, ERRORSTACKSIZE, raiseerror);
        if (raiseerror != 0) {
            LuaErrors.runErrorWithInfo("stack overflow");
        }
        return 0;
    }

    // ldo.h: luaD_checkstack
    // java diff: C 的宿主 API lua_checkstack 还会前推 ci->top（保证 C 函数帧内可用空间）；
    //   Java 的宿主走 Varargs 模型，没有"C 帧 top"概念，故只 grow 不动 ci.top。
    public static void checkStack(LuaThread L, int n) {
        if (L.stack_last - L.top <= n) {
            growStack(L, n, 1);
        }
    }

    // ldo.c: luaD_shrinkstack  -  溢出时恢复栈大小
    // java diff: C 在此末尾无条件 luaE_shrinkCI(L)；Java 只在 GC 路径（shrinkStackForGc）
    //   收缩空闲 CallInfo 链（空闲 CI 可复用，收缩无语义影响）。
    public static void shrinkStack(LuaThread L) {
        int inuse = stackInUse(L);
        // ldo.c: luaD_shrinkstack  -  溢出恢复收缩回最大正常大小。
        //   java diff: xpcall 帧 ci.top 残留可恰超 MAXSTACK（cstack.lua:172/173），故 inuse
        //   恰超限且 L.top<=MAXSTACK 时回 MAXSTACK；其余场景保持 C 原公式（cstack.lua:185）
        if (inuse > MAXSTACK) {
            if (inuse <= MAXSTACK + 20 && L.top <= MAXSTACK) {
                reallocStack(L, MAXSTACK, 0);
            }
            return;
        }
        int max = (inuse > MAXSTACK / 3) ? MAXSTACK : inuse * 3;
        if (inuse <= MAXSTACK && L.stack_last > max) {
            int nsize = (inuse > MAXSTACK / 2) ? MAXSTACK : inuse * 2;
            reallocStack(L, nsize, 0);
        }
    }

    /**
     * lgc.c: traversethread 在 atomic 相位调的 luaD_shrinkstack（GC 路径专用）。
     * 仅用 C 的原公式 + shrinkCI，不含 {@link #shrinkStack} 的溢出恢复分支（pcall 错误恢复语义）。
     * 缺失则数组容量与空闲 CallInfo 节点滞留峰值：每协程深递归一次各留一份。
     */
    public static void shrinkStackForGc(LuaThread L) {
        if (L == null || L.stack == null) return;
        int inuse = stackInUse(L);
        int max = (inuse > MAXSTACK / 3) ? MAXSTACK : inuse * 3;
        if (inuse <= MAXSTACK && L.stack_last > max) {
            int nsize = (inuse > MAXSTACK / 2) ? MAXSTACK : inuse * 2;
            reallocStack(L, nsize, 0);
        }
        shrinkCI(L);
    }

    /**
     * lstate.c: luaE_shrinkCI  -  把空闲 CallInfo 链隔一个删一个（每轮减半）。
     *
     * <p>java diff: C 用 luaM_free 显式释放；Java 断链即可，JVM 回收。
     * 同样不做 GC 记账（C 的 luaM_free 会减 GCdebt，但 Java 的 CallInfo 不计入
     * Lua 记账口径 - 它是 java-only 的帧复用结构）。
     */
    private static void shrinkCI(LuaThread L) {
        CallInfo ci = L.ci == null ? null : L.ci.next;   // 第一个空闲节点
        if (ci == null) return;
        CallInfo next;
        while ((next = ci.next) != null) {               // 有两个以上空闲？
            CallInfo next2 = next.next;
            ci.next = next2;                             // 摘掉 next
            L.nci--;
            if (next2 == null) break;
            next2.previous = ci;
            ci = next2;
        }
    }

    // ldo.c: stackinuse
    private static int stackInUse(LuaThread L) {
        CallInfo ci = L.ci;
        int res = L.top;
        while (ci != null) {
            if (ci.top > res) res = ci.top;
            ci = ci.previous;
        }
        // ldo.c:  -  res = lim - stack + 1（"part of stack in use"）+ 最小下限：
        //   C 有 +1 且 res < LUA_MINSTACK 时取 20；缺一则收缩后容量差 1-2 槽，
        //   cstack.lua:172 stack recovery LIM 阶段 assert checkstack(20) 恰差 1 槽溢出。
        res = res + 1;
        if (res < 20) res = 20;  // LUA_MINSTACK
        return res;
    }

    // ldo.c: luaD_errerr
    private static void errErr() {
        LuaErrors.runErrorWithInfo("error in error handling");
    }

    // lvm.c: luaV_fitsN
    private static Long fitsLong(double d) {
        if (Double.isNaN(d)) return null;
        if (d < (double) Long.MIN_VALUE || d >= (double) Long.MAX_VALUE) return null;
        return (long) d;
    }

    // lvm.c: luaV_tonumber_ // java diff: C 用 luaG_forerror；Java 拆 forNumber/forError
    private static double forNumber(LuaValue v, String what) {
        if (v instanceof LuaNumber n) return n.todouble();
        if (v instanceof LuaString s) {
            LuaNumber n = s.scannumber();
            if (n != null) return n.todouble();
        }
        forError(v, what);
        return 0;  // 不可达
    }

    // ==================================================================
    // lobject.h / lstate.h  -  C 宏的 Java 方法
    // ==================================================================

    // ldebug.c: luaG_forerror
    private static void forError(LuaValue v, String what) {
        LuaErrors.forError(v, what);
    }

    private static long shiftl(long x, long y) {
        if (y < 0) {
            if (y <= -NBITS) return 0;
            return x >>> (int) (-y);
        } else {
            if (y >= NBITS) return 0;
            return x << (int) y;
        }
    }

    // java-only: C 用 tonumber+ttisnumber；Java 用 instanceof 检查
    private static boolean hasNumericForm(LuaValue v) {
        if (v instanceof LuaNumber) return true;
        if (v instanceof LuaString s) return s.scannumber() != null;
        return false;
    }

    // java-only: C 用 luaV_tointeger；Java 返回 Long 或 null
    // lvm.c: tointegerns  -  仅处理整数/浮点；字符串不转换（对齐 C 行为）
    private static Long arithToLongOrNull(LuaValue v) {
        if (v instanceof LuaInteger i) return i.v;
        if (v instanceof LuaFloat f) {
            double d = f.todouble();
            if (Double.isInfinite(d) || Double.isNaN(d)) return null;
            if (d != Math.floor(d)) return null;
            if (d >= 9223372036854775808.0 || d < -9223372036854775808.0) return null;
            return (long) d;
        }
        return null;
    }



    // ==================================================================
    // ltm.c  -  元方法调用函数（一比一复刻）
    // ==================================================================

    // int 事件 -> Metamethod 枚举（供 Metamethod.getTmByObj）
    static Metamethod tmsEnum(int event) {
        return switch (event) {
            case 0 -> Metamethod.INDEX;
            case 1 -> Metamethod.NEWINDEX;
            case 2 -> Metamethod.GC;
            case 3 -> Metamethod.MODE;
            case 4 -> Metamethod.LEN;
            case 5 -> Metamethod.EQ;
            case 6 -> Metamethod.ADD;
            case 7 -> Metamethod.SUB;
            case 8 -> Metamethod.MUL;
            case 9 -> Metamethod.MOD;
            case 10 -> Metamethod.POW;
            case 11 -> Metamethod.DIV;
            case 12 -> Metamethod.IDIV;
            case 13 -> Metamethod.BAND;
            case 14 -> Metamethod.BOR;
            case 15 -> Metamethod.BXOR;
            case 16 -> Metamethod.SHL;
            case 17 -> Metamethod.SHR;
            case 18 -> Metamethod.UNM;
            case 19 -> Metamethod.BNOT;
            case 20 -> Metamethod.LT;
            case 21 -> Metamethod.LE;
            case 22 -> Metamethod.CONCAT;
            case 23 -> Metamethod.CALL;
            default -> Metamethod.INDEX;
        };
    }

    // lstate.h: savestack(L,p)  -  java diff: Java 栈不搬迁；同一性
    static int savestack(LuaThread L, int slot) {
        return slot;
    }

    // lstate.h: restorestack(L,n)  -  java diff: Java 栈不搬迁；同一性
    static int restorestack(LuaThread L, int n) {
        return n;
    }

    // lobject.h: setobj2s(L,dest,src)  -  设置栈槽位
    static void setobj2s(LuaThread L, int dest, LuaValue src) {
        L.stack[dest] = src;
    }

    // lobject.h: setobjs2s(L,dest,src)  -  same as setobj2s
    static void setobjs2s(LuaThread L, int dest, int src) {
        L.stack[dest] = L.stack[src];
    }

    // lobject.h: s2v(slot)  -  栈槽位取值
    public static LuaValue s2v(int slot, LuaThread L) {
        return L.stack[slot];
    }

    // lobject.h: ttypetag(val)  -  取值的类型标签
    static int typeTag(LuaValue val) {
        return val.type();
    }

    // ltm.c: luaT_callTMres
    // java diff: resSlot (int) 替代 StkId res；返回 int 标签（C 返回 lu_byte）
    static int callTMres(LuaThread L, CallInfo ci, int resSlot, LuaValue f, LuaValue p1, LuaValue p2) {
        int result = savestack(L, resSlot);
        L.top = ci.top;  // java diff: ensure L.top is at ci.top before pushing args
        int func = L.top;
        checkStack(L, 3);
        setobj2s(L, func, f);
        setobj2s(L, func + 1, p1);
        setobj2s(L, func + 2, p2);
        L.top = func + 3;
        // ltm.c:  -  isLuacode(ci) -> luaD_call（可 yield）；否则 luaD_callnoyield
        // java diff: C 检查 isLuacode；Java 用 callLua（可 yield），因 callTMres 仅
        // 从 luaV_execute 调用（总是 Lua 代码），isLuacode 恒为真
        LuaCall.callLua(L, func, 1);
        resSlot = restorestack(L, result);
        L.top--;  // --L->top.p
        setobj2s(L, resSlot, s2v(func, L));
        return typeTag(s2v(resSlot, L));
    }

    // ltm.c: callbinTM
    public static int callbinTM(LuaThread L, CallInfo ci, int resSlot, LuaValue p1, LuaValue p2, int event) {
        LuaValue tm = Metamethod.getTmByObj(L, p1, tmsEnum(event));  // try first operand
        if (tm == null)
            tm = Metamethod.getTmByObj(L, p2, tmsEnum(event));  // try second operand
        if (tm == null)
            return -1;  // tag method not found
        else  // call tag method and return the tag of the result
            return callTMres(L, ci, resSlot, tm, p1, p2);
    }

    // ltm.c: luaT_trybinTM
    // java diff: slot1/slot2 模拟 C 的 const TValue *p1/p2 指针
    static void tryBinTM(LuaThread L, CallInfo ci, int resSlot,
                         int slot1, LuaValue p1, int slot2, LuaValue p2, int event) {
        if (callbinTM(L, ci, resSlot, p1, p2, event) < 0) {
            switch (event) {
                case 13, 14, 15, 16, 17, 19: {  // TM_BAND..TM_SHR, TM_BNOT
                    if (p1 instanceof LuaNumber && p2 instanceof LuaNumber)
                        LuaErrors.toIntError(L, slot1, p1, slot2, p2);
                    else
                        LuaErrors.opIntError(L, slot1, p1, slot2, p2, "perform bitwise operation on");
                    break;  // java diff: C has FALLTHROUGH; Java needs explicit break
                }
                default:
                    LuaErrors.opIntError(L, slot1, p1, slot2, p2, "perform arithmetic on");
            }
        }
    }

    // ltm.c: luaT_trybinassocTM
    static void tryBinAssocTM(LuaThread L, CallInfo ci, int resSlot,
                              int slot1, LuaValue p1, int slot2, LuaValue p2, int flip, int event) {
        if (flip != 0)
            tryBinTM(L, ci, resSlot, slot2, p2, slot1, p1, event);
        else
            tryBinTM(L, ci, resSlot, slot1, p1, slot2, p2, event);
    }


    // -- 调试钩子系统 --

    // ltm.c: luaT_trybiniTM
    static void tryBiniTM(LuaThread L, CallInfo ci, int resSlot,
                          int slot1, LuaValue p1, long i2, int flip, int event) {
        LuaValue aux = LuaInteger.valueOf(i2);
        tryBinAssocTM(L, ci, resSlot, slot1, p1, -1, aux, flip, event);
    }

    // ltm.c: luaT_callorderTM
    // java diff: 返回 Boolean/null 而非 int (-1/0/1)
    // java diff: C 用 tagisfalse(tag) 检查结果；Java 从 resSlot 读值
    static Boolean callOrderTM(LuaThread L, CallInfo ci, LuaValue p1, LuaValue p2, int event) {
        int resSlot = L.top;  // save before callTMres modifies L.top
        int tag = callbinTM(L, ci, resSlot, p1, p2, event);
        if (tag >= 0)
            return s2v(resSlot, L).toboolean();
        LuaErrors.orderError(p1, p2);
        return null;
    }

    // lobject.h: tagisfalse  -  标签为 LUA_TFALSE 或 LUA_TNIL
    static boolean tagisfalse(int tag) {
        // java diff: Java type() 不区分 true/false，标签只能可靠判 nil；
        // false 值由调用方直接读值判定
        return tag == LuaValue.TNIL;
    }




    // lvm.c: luaF_getlocalname
    private static String getLocalName(Prototype p, int localNumber, int pc) {
        if (p == null || p.locvars == null) return null;
        for (int i = 0; i < p.locvars.length; i++) {
            Prototype.LocVar lv = p.locvars[i];
            if (lv == null) break;
            if (lv.startpc > pc) break;
            if (pc < lv.endpc) {
                localNumber--;
                if (localNumber == 0) return lv.varname != null ? lv.varname.toJavaString() : "?";
            }
        }
        return null;
    }

    public static void callHook(LuaThread L, int event, int line, int ftransfer, int ntransfer) {
        // ldebug.c: luaD_hook 的 ldb->hook 读取。java diff: 经 Globals.resolveHook
        //   解开 ldblib 的 HOOKF 间接（装配时由 DebugLib 登记），使 vm 不依赖 lib。
        LuaValue hook = L.l_G != null ? L.l_G.resolveHook(L) : L.hook;
        if (hook == null || hook.isnil() || L.allowhook == 0) return;
        CallInfo ci = L.ci;
        int savedTop = L.top;
        int savedCiTop = ci.top;
        L.ftransfer = ftransfer;
        L.ntransfer = ntransfer;
        // C: if (isLua(ci) && L->top.p < ci->top.p) L->top.p = ci->top.p;
        if (ci.isLua() && L.top < ci.top) L.top = ci.top;
        checkStack(L, 20 /* LUA_MINSTACK */);
        if (ci.top < L.top + 20) ci.top = L.top + 20;
        L.allowhook = 0;
        ci.callstatus |= CallInfo.CIST_HOOKED;
        // C: hookf  -  hook(event_name, line)
        try {
            LuaValue eventName = LuaString.newStr(HOOKNAMES[event]);
            int funcSlot = L.top;
            L.stack[funcSlot] = hook;
            L.stack[funcSlot + 1] = eventName;
            if (line >= 0) {
                L.stack[funcSlot + 2] = LuaInteger.valueOf(line);
                L.top = funcSlot + 3;
            } else {
                L.stack[funcSlot + 2] = LuaValue.NIL;
                L.top = funcSlot + 3;
            }
            LuaCall.callLua(L, funcSlot, 0);
        } finally {
            L.allowhook = 1;
            ci.top = savedCiTop;
            L.top = savedTop;
            ci.callstatus &= ~CallInfo.CIST_HOOKED;
        }
    }

    // ldo.c: luaD_hookcall
    static void hookCall(LuaThread L, CallInfo ci) {
        L.oldpc = 0;  // L->oldpc = 0
        if ((L.hookmask & LuaThread.LUA_MASKCALL) != 0) {
            int event = (ci.callstatus & CallInfo.CIST_TAIL) != 0
                    ? LUA_HOOKTAILCALL : LUA_HOOKCALL;
            LuaClosure cl = CallInfo.ciFunc(L, ci);
            if (cl != null && cl.p != null) {
                ci.savedpc = ci.savedpc + 1;  // 钩子假定 pc 已递增
                callHook(L, event, -1, 1, cl.p.numparams);
                ci.savedpc = ci.savedpc - 1;  // 修正 pc
            }
        }
    }

    // ldo.c: rethook
    public static void rethook(LuaThread L, CallInfo ci, int nres) {
        if ((L.hookmask & LuaThread.LUA_MASKRET) != 0) {
            int firstres = L.top - nres;
            int delta = 0;
            if (ci.isLua()) {
                LuaClosure cl = CallInfo.ciFunc(L, ci);
                if (cl != null && cl.p != null && (cl.p.flag & Prototype.PF_VAHID) != 0) {
                    delta = ci.nextraargs + cl.p.numparams + 1;
                }
            }
            ci.func += delta;
            int ftransfer = firstres - ci.func;
            callHook(L, LUA_HOOKRET, -1, ftransfer, nres);
            ci.func -= delta;
        }
        // C: if (isLua(ci = ci->previous)) L->oldpc = currentpc(ci);
        CallInfo prev = ci.previous;
        if (prev != null && prev.isLua()) {
            LuaClosure prevCl = CallInfo.ciFunc(L, prev);
            if (prevCl != null && prevCl.p != null) {
                L.oldpc = CallInfo.currentpc(prev);
            }
        }
    }

    // ldebug.c: luaG_traceexec
    // java-only: savedpc保留下一条指令索引，npci=pc为当前指令索引(对照C的pcRel)

    // ldebug.c: luaG_tracecall
    static int traceCall(LuaThread L) {
        CallInfo ci = L.ci;
        if (L.allowhook == 0) {
            ci.trap = false;
            return 0;
        }
        LuaClosure cl = CallInfo.ciFunc(L, ci);
        if (cl == null) return L.hookmask != 0 ? 1 : 0;
        Prototype p = cl.p;
        ci.trap = true;
        if (ci.savedpc == 0) {  // ci->u.l.savedpc == p->code
            if (p != null && p.isVararg()) {  // isvararg(p) = PF_VAHID | PF_VATAB
                return 0;  // vararg函数：关闭trap，VARARGPREP后再调用hook
            } else if ((ci.callstatus & CallInfo.CIST_HOOKYIELD) == 0) {
                hookCall(L, ci);
            }
        }
        return 1;
    }

    // ldebug.c: luaG_traceexec
    // java diff（有意分叉，两处）：① 不支持 hook 内 yield —— C 末尾判 LUA_YIELD 时置
    //   CIST_HOOKYIELD 并 luaD_throw 使 hook 可挂起协程；Java hook 经 callHook 同步调用
    //   （真实 Java 栈帧）无法挂起，故本方法不设该位（只读/清），hook 里 yield 按
    //   "跨 C 调用边界"报错。② C 的 Protect 展开含 updatetrap(ci)（元方法/hook 里
    //   debug.sethook 即时生效）；Java 由调用点按返回值驱动 trap 更新，同帧即时性等价，
    //   跨帧等 settraps 下一次分派（setHookState 已对整条 CI 链设置）。
    static int traceExec(LuaThread L, int pc) {
        CallInfo ci = L.ci;
        if (L.allowhook == 0) {
            ci.trap = false;
            return 0;
        }
        int mask = L.hookmask;
        LuaClosure cl = CallInfo.ciFunc(L, ci);
        if (cl == null) return 0;
        Prototype p = cl.p;

        if ((mask & (LuaThread.LUA_MASKLINE | LuaThread.LUA_MASKCOUNT)) == 0) {
            ci.trap = false;
            return 0;
        }
        int npci = pc;  // pcRel(pc, p)
        ci.savedpc = pc + 1;
        // 计数 hook
        boolean counthook = false;
        if ((mask & LuaThread.LUA_MASKCOUNT) != 0 && --L.hookcount == 0) {
            counthook = true;
            L.hookcount = L.basehookcount;
        } else if ((mask & LuaThread.LUA_MASKLINE) == 0) {
            return 1;
        }

        if ((ci.callstatus & CallInfo.CIST_HOOKYIELD) != 0) {
            ci.callstatus &= ~CallInfo.CIST_HOOKYIELD;
            return 1;
        }
        if (counthook) {
            callHook(L, LUA_HOOKCOUNT, -1, 0, 0);
        }

        if ((mask & LuaThread.LUA_MASKLINE) != 0) {
            int oldpc = (L.oldpc >= 0 && L.oldpc < p.sizecode) ? L.oldpc : 0;
            if (npci <= oldpc || changedline(p, oldpc, npci)) {
                int newline = LuaDebug.getFuncLinePub(p, npci);
                callHook(L, LUA_HOOKLINE, newline, 0, 0);
            }
            L.oldpc = npci;
        }
        return 1;
    }

    private static boolean changedline(Prototype p, int oldpc, int newpc) {
        if (p.lineinfo == null || p.lineinfo.length == 0) return false;
        if (newpc - oldpc < MAXIWTHABS / 2) {
            int delta = 0;
            int pc = oldpc;
            for (; ; ) {
                int lineinfo = p.lineinfo[++pc];
                if (lineinfo == 0x80) break;  // ABSLINEINFO: C ls_byte=-0x80, Java unsigned 0x80
                if (lineinfo >= 128) lineinfo -= 256;
                delta += lineinfo;
                if (pc == newpc) return delta != 0;
            }
        }
        return LuaDebug.getFuncLinePub(p, oldpc) != LuaDebug.getFuncLinePub(p, newpc);
    }

    // -- 主解释器循环 --
    // Lua-Lua调用通过切换CallInfo+continue实现，不递归
    // goto startfunc->continue startfunc | goto returning->continue returning | goto ret->内联到RETURN0/RETURN1
    // lvm.c: luaV_execute

    // -- OP_GETVARG 的独立承载方法 --
    // ltm.c: luaT_getvararg。低频且体积大，从 execute 抽出以保持 code_length < 8000。
    // 返回该索引/键对应的 vararg 值（execute 内 stack[ra] = 结果）。
    private static LuaValue opGetvarg(LuaThread L, CallInfo ci, LuaValue varargTable, LuaValue key) {
        if (varargTable instanceof LuaTable table) {
            return table.rawget(key);
        }
        int nextra = Math.max(0, ci.nextraargs);
        long index = Long.MIN_VALUE;
        if (key instanceof LuaInteger integer) {
            index = integer.v;
        } else if (key instanceof LuaFloat number) {
            double value = number.todouble();
            if (!Double.isNaN(value)
                    && !Double.isInfinite(value)
                    && value == Math.rint(value)
                    && value >= Long.MIN_VALUE
                    && value <= Long.MAX_VALUE) {
                index = (long) value;
            }
        }
        if (index >= 1 && index <= nextra) {
            int varargSlot = ci.func - nextra + (int) index - 1;
            return L.stack[varargSlot];
        } else if (key instanceof LuaString string
                && "n".equals(string.toJavaString())) {
            return LuaInteger.valueOf(nextra);
        } else {
            return LuaValue.NIL;
        }
    }

    // -- OP_NEWTABLE 的独立承载方法 --
    // lvm.c: OP_NEWTABLE。冷路径（表构造，非算术/调用热点）且含 vB/vC 位解码 + 紧急 GC 修正，
    //   从 execute 抽出以保持 code_length < 8000（C2 HugeMethodLimit），同 opGetvarg 先例。
    // java diff: C 的 pc++ 跳过 EXTRAARG 在两个分支里各写一次；此处统一由调用方做
    //   （k 位与否都要跳过同一个槽），helper 只读 nextInst 取扩展位。
    private static LuaTable opNewtable(LuaThread L, int inst, int nextInst, int ra) {
        int vB = (inst >>> 16) & 0x3F;
        int vC = (inst >>> 22) & 0x3FF;
        int kBit = ((inst >>> 15) & 1);
        int b = vB;  // log2(哈希大小)+1
        int c = vC;
        if (b > 0) b = 1 << (b - 1);
        if (kBit != 0) {
            c += ((nextInst >>> 7) & 0x1FFFFFF) * (Opcodes.MAXARG_vC + 1);
        }
        // lvm.c: L->top.p = ra + 1; 紧急 GC 时修正 top
        //   [顺序要紧]必须在 new LuaTable / checkGC 之前设置：两者都可能触发紧急 GC，
        //   而 GC 的栈扫描以 L.top 为界；漏设会让 ra 之上的活值落在界外被判死。
        L.top = ra + 1;
        LuaTable t = new LuaTable(L.l_G, c, b);
        // lvm.c: checkGC(L, ra + 1)
        LuaGC.checkGC(L.l_G, 128);
        return t;
    }

    // -- OP_VARARG 的独立承载方法 --
    // lvm.c: OP_VARARG -> luaT_getvarargs。低频（仅 vararg 函数体内出现）且体积大，
    //   从 execute 抽出以保持 code_length < 8000（C2 HugeMethodLimit），同 opGetvarg 先例。
    // [栈重绑契约]内部可能 checkStack 触发 growStack 换掉 L.stack 数组，故返回**当前**
    //   stack 引用，调用方必须用返回值覆盖自己的局部 stack（漏掉即写到旧数组 = 丢值）。
    private static LuaValue[] opVararg(LuaThread L, CallInfo ci, LuaValue[] stack,
                                       int ra, int inst) {
        int C = (inst >>> 24) & 0xFF;
        int B = (inst >>> 16) & 0xFF;
        int kBit = ((inst >>> 15) & 1);
        int n = C - 1;  // -1=LUA_MULTRET
        int vatab = kBit != 0 ? B : -1;  // vararg table 寄存器，-1=无
        LuaTable htab = (vatab >= 0 && stack[ci.func + vatab + 1] instanceof LuaTable tab) ? tab : null;
        int nargs = getnumargs(L, ci, htab);  // ltm.c:
        int touse;
        if (n < 0) {
            // LUA_MULTRET
            touse = nargs;
            int needed = ra + nargs - L.top;
            if (needed > 0) {
                checkStack(L, needed);
                stack = L.stack;
            }
            L.top = ra + nargs;
        } else {
            touse = Math.min(nargs, n);
            if (touse > 0) {
                int needed = ra + touse - L.top;
                if (needed > 0) {
                    checkStack(L, needed);
                    stack = L.stack;
                }
            }
        }
        if (htab == null) {
            // ltm.c: : 无变参表，从栈取
            int varargStart = ci.func - nargs;
            for (int i = 0; i < touse; i++) {
                stack[ra + i] = stack[varargStart + i];
            }
        } else {
            // ltm.c: : 从变参表取
            // java diff: 用 getInt 避免 LuaInteger 分配
            for (int i = 0; i < touse; i++) {
                stack[ra + i] = htab.getInt(i + 1);
            }
        }

        for (int i = touse; i < (n < 0 ? touse : n); i++) {
            stack[ra + i] = LuaValue.NIL;
        }
        return stack;
    }

    public static void execute(LuaThread L, CallInfo ci) {

        LuaClosure cl;
        LuaValue[] k;
        int[] code;  // java only: 局部指令数组引用，对齐 C 的 const Instruction *pc 直接解引用
        LuaValue[] stack;  // java only: 局部栈引用优化，避免每次L.stack字段访问
        int base;
        int pc;
        int trap;

        startfunc:
        while (true) {
            // java only: 消费 GC 置的待办收缩位。此处是安全点：下面重读 L.stack、returning
            //   循环里重算 base，换数组不留悬空引用（GC 不能在 atomic 相位直接换运行线程的
            //   数组：stack 缓存在本方法局部量，三处 checkGC 后都不刷新）。见 LuaThread.pendingStackShrink。
            if (L.pendingStackShrink) {
                L.pendingStackShrink = false;
                shrinkStackForGc(L);
            }
            stack = L.stack;  // java only: 栈可能realloc，每次进入startfunc刷新
            trap = L.hookmask;
            returning:
            while (true) {
                // java only: continue returning 从被调帧回到本帧时不经过 startfunc 入口，
                // 被调函数（或其嵌套调用）内部 checkStack/growStack 可能已 realloc L.stack，
                // 必须在此刷新，否则本帧后续指令（OP_SETTABLE/OP_GETTABLE 等）会用旧数组读到 nil/垃圾。
                stack = L.stack;
                cl = CallInfo.ciFuncLua(L, ci);
                k = cl.p.k;
                code = cl.p.code;  // java only: 提取指令数组，减少 fetch 循环的字段链访问
                pc = ci.savedpc;

                if (trap != 0) {
                    trap = traceCall(L);
                    stack = L.stack;
                }  // java only: hook可能触发realloc
                base = ci.func + 1;


                for (; ; ) {
                    // lvm.c: vmfetch
                    if (trap != 0) {
                        trap = traceExec(L, pc);
                        base = ci.func + 1;  // hook可能改变栈
                        stack = L.stack;  // java only: hook可能触发realloc
                    }
                    int inst = code[pc++];
                    int op = inst & 0x7F;
                    int A = (inst >>> 7) & 0xFF;
                    int ra = base + A;

                    switch (op) {
                        // -- OP_MOVE --
                        case Opcodes.OP_MOVE -> {
                            int B = (inst >>> 16) & 0xFF;
                            stack[ra] = stack[base + B];
                        }
                        // -- OP_LOADI --
                        case Opcodes.OP_LOADI -> {
                            stack[ra] = LuaInteger.valueOf(((inst >>> 15) & 0x1FFFF) - 0xFFFF);
                        }
                        // -- OP_LOADF --
                        case Opcodes.OP_LOADF -> {
                            stack[ra] = LuaFloat.valueOf((double) ((inst >>> 15) & 0x1FFFF) - 0xFFFF);
                        }
                        // -- OP_LOADK --
                        case Opcodes.OP_LOADK -> {
                            stack[ra] = k[(inst >>> 15) & 0x1FFFF];
                        }
                        // -- OP_LOADKX --
                        case Opcodes.OP_LOADKX -> {
                            stack[ra] = k[(code[pc++] >>> 7) & 0x1FFFFFF];
                        }
                        // -- OP_LOADFALSE --
                        case Opcodes.OP_LOADFALSE -> stack[ra] = LuaValue.FALSE;
                        // -- OP_LFALSESKIP --
                        case Opcodes.OP_LFALSESKIP -> {
                            stack[ra] = LuaValue.FALSE;
                            pc++;
                        }
                        // -- OP_LOADTRUE --
                        case Opcodes.OP_LOADTRUE -> stack[ra] = LuaValue.TRUE;
                        // -- OP_LOADNIL --
                        case Opcodes.OP_LOADNIL -> {
                            int B = (inst >>> 16) & 0xFF;
                            for (int i = A; i <= A + B; i++) stack[base + i] = LuaValue.NIL;
                        }
                        // -- OP_GETUPVAL --
                        case Opcodes.OP_GETUPVAL -> {
                            int B = (inst >>> 16) & 0xFF;
                            stack[ra] = cl.upvals[B].get();
                        }
                        // -- OP_SETUPVAL --
                        // lvm.c:  -  OP_SETUPVAL: 设置上值并 luaC_barrier
                        case Opcodes.OP_SETUPVAL -> {
                            int B = (inst >>> 16) & 0xFF;
                            UpVal uv = cl.upvals[B];
                            LuaValue val = stack[ra];

                            uv.set(val);
                            // lvm.c: OP_SETUPVAL  -  luaC_barrier(L, uv, s2v(ra))
                            // java diff: UpVal 无 gcColor，以所属闭包 gcColor 作代理——BLACK 写新白值需
                            //   forward barrier（WHITE 冗余，值随闭包标记）；否则每次 OP_SETUPVAL
                            //   置 GRAY 强制下次 fullGC repropagateAll（needRepropagate 优化关键）
                            if (!uv.upisopen() && LuaGC.isblackGC(cl)) LuaGC.barrier(L.l_G, val);
                        }

                        // -- OP_GETTABUP --
                        // lvm.c:  -  luaV_fastget: inline luaH_getshortstr
                        case Opcodes.OP_GETTABUP -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            stack = opGettabup(L, ci, pc, stack, ra, cl.upvals[B].get(), (LuaString) k[C], -(B + 2));
                        }
                        // -- OP_GETTABLE --
                        // lvm.c:  -  luaV_fastget: integer fast track, else luaH_pget
                        case Opcodes.OP_GETTABLE -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            stack = opGettable(L, ci, pc, stack, ra, base, B, C, kBit, k);
                        }
                        // -- OP_GETI --
                        // lvm.c:  -  luaV_fastgeti: inline array access, hash fallback
                        case Opcodes.OP_GETI -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            stack = opGeti(L, ci, pc, stack, ra, base, B, C);
                        }
                        // -- OP_GETFIELD --
                        // lvm.c:  -  luaV_fastget: inline luaH_getshortstr
                        case Opcodes.OP_GETFIELD -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            stack = opGetfield(L, ci, pc, stack, ra, base, B, (LuaString) k[C]);
                        }

                        // -- OP_SETTABUP --
                        // lvm.c:  -  luaV_fastset快速路径: 直接luaH_psetshortstr，命中则跳过finishset
                        case Opcodes.OP_SETTABUP -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            // lvm.c: : TString *key = tsvalue(rb); 键必须是短字符串
                            stack = opSetShortStr(L, ci, pc, stack, cl.upvals[A].get(),
                                    (LuaString) k[B], kBit != 0 ? k[C] : stack[base + C], -(A + 2));
                        }
                        // -- OP_SETTABLE --
                        // lvm.c:  -  luaV_fastset快速路径
                        case Opcodes.OP_SETTABLE -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            stack = opSettable(L, ci, pc, stack, ra, base, B, C, kBit, k);
                        }
                        // -- OP_SETI --
                        // lvm.c:  -  luaV_fastseti快速路径: 直接数组/哈希设置，命中则跳过finishseti
                        case Opcodes.OP_SETI -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            stack = opSeti(L, ci, pc, stack, ra, base, B, C, kBit, k);
                        }
                        // -- OP_SETFIELD --
                        // lvm.c:  -  luaV_fastset快速路径: 直接luaH_psetshortstr，命中则跳过finishset
                        case Opcodes.OP_SETFIELD -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            // lvm.c: : TString *key = tsvalue(rb); 键必须是短字符串
                            stack = opSetShortStr(L, ci, pc, stack, stack[ra],
                                    (LuaString) k[B], kBit != 0 ? k[C] : stack[base + C], ra);
                        }

                        // -- OP_NEWTABLE --
                        case Opcodes.OP_NEWTABLE -> {
                            // 抽出到 opNewtable（体积治理）。EXTRAARG 槽无论 k 位都要跳过 ⇒ pc++ 留在此处。
                            stack[ra] = opNewtable(L, inst, code[pc], ra);
                            pc++;
                        }
                        // -- OP_SELF --
                        // lvm.c:  -  luaV_fastget: inline luaH_getshortstr
                        case Opcodes.OP_SELF -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            LuaValue t = stack[base + B];
                            stack[ra + 1] = t;
                            // lvm.c: : TString *key = tsvalue(rc); 键必须是短字符串
                            LuaString ks = (LuaString) k[C];
                            // ltable.h: luaV_fastget  -  luaH_Hgetshortstr (ltable.c)
                            LuaTable table = t instanceof LuaTable tbl ? tbl : null;
                            boolean isTable = table != null;
                            if (isTable) {
                                LuaValue v = table.fastGetShortStr(ks);
                                if (v != null) {
                                    stack[ra] = v;
                                    break;
                                }
                            }
                            LuaValue javaResult = tryJavaGet(L, ci, pc, t, ks);
                            if (javaResult != null) {
                                // Java 监听器/Getter 可能重入 Lua 并扩容栈；不能继续写旧数组。
                                stack = L.stack;
                                stack[ra] = javaResult;
                                break;
                            }
                            // lvm.c: : luaV_finishget(L, rb, rc, ra, tag)
                            ci.savedpc = pc;
                            L.top = ci.top;
                            LuaValue res = LuaIndex.finishGetFromVM(t, ks, L, ra, base + B, isTable);
                            stack = L.stack;
                            stack[ra] = res;
                        }

                        // -- 算术/位运算 --
                        // lvm.c: 快速路径成功时 pc++ 跳过 OP_MMBIN；失败时 pc 不递增，下轮执行 OP_MMBIN
                        // java diff: Java 的 inst=code[pc++] 已递增（C 中 vmfetch 已 pc++），成功时也需额外 pc++ 跳过 MMBIN
                        // lvm.c:  -  op_arithI (Java 方法 op_arithI_add)
                        case Opcodes.OP_ADDI -> {
                            int B = (inst >>> 16) & 0xFF;
                            int sC = ((inst >>> 24) & 0xFF) - 127;
                            if (opArithIAdd(stack, ra, stack[base + B], sC)) pc++;
                        }
                        // lvm.c:  -  op_arithK: k[C] 恒为 LuaNumber
                        case Opcodes.OP_ADDK -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            if (opArith(stack, ra, stack[base + B], k[C], BinaryOp.ADD)) pc++;
                        }
                        // lvm.c:  -  op_arithK
                        case Opcodes.OP_SUBK -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            if (opArith(stack, ra, stack[base + B], k[C], BinaryOp.SUB)) pc++;
                        }
                        // lvm.c:  -  op_arithK
                        case Opcodes.OP_MULK -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            if (opArith(stack, ra, stack[base + B], k[C], BinaryOp.MUL)) pc++;
                        }
                        // lvm.c: : savestate(L, ci); 除零情形
                        case Opcodes.OP_MODK -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            savestate(L, ci, pc);
                            if (opArithSlow(stack, ra, stack[base + B], k[C], BinaryOp.MOD)) pc++;
                        }
                        // lvm.c: : op_arithfK（无 savestate  -  POW 永不报错）
                        case Opcodes.OP_POWK -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            if (opArithSlow(stack, ra, stack[base + B], k[C], BinaryOp.POW)) pc++;
                        }
                        // lvm.c: : op_arithfK（无 savestate  -  DIV 永不报错）
                        case Opcodes.OP_DIVK -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            if (opArithSlow(stack, ra, stack[base + B], k[C], BinaryOp.DIV)) pc++;
                        }
                        // lvm.c: : savestate(L, ci); 除零情形
                        case Opcodes.OP_IDIVK -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            savestate(L, ci, pc);
                            if (opArithSlow(stack, ra, stack[base + B], k[C], BinaryOp.IDIV))
                                pc++;
                        }
                        // lvm.c:  -  op_bitwiseK (Java 方法 op_bitwiseK)
                        case Opcodes.OP_BANDK -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            if (opBitwiseK(L, ci, pc, stack, ra, stack[base + B], k[C], ((LuaInteger) k[C]).v, BinaryOp.BAND))
                                pc++;
                        }
                        case Opcodes.OP_BORK -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            if (opBitwiseK(L, ci, pc, stack, ra, stack[base + B], k[C], ((LuaInteger) k[C]).v, BinaryOp.BOR))
                                pc++;
                        }
                        case Opcodes.OP_BXORK -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            if (opBitwiseK(L, ci, pc, stack, ra, stack[base + B], k[C], ((LuaInteger) k[C]).v, BinaryOp.BXOR))
                                pc++;
                        }
                        case Opcodes.OP_SHLI -> {
                            int B = (inst >>> 16) & 0xFF;
                            int sC = ((inst >>> 24) & 0xFF) - 127;
                            long ic = sC;
                            Long ib = arithToLongOrNull(stack[base + B]);
                            if (ib != null) {
                                LuaValue r = LuaInteger.valueOf(shiftl(ic, ib));
                                // shadow 对拍: SHLI = shiftLeft(ic, ib)，op=SHL，v1=ic(立即数)，v2=寄存器
                                if (SHADOW_FLAT)
                                    shadowCheckArith(LuaInteger.valueOf(ic), stack[base + B], BinaryOp.SHL, r);
                                stack[ra] = r;
                                pc++;
                            }
                        }
                        case Opcodes.OP_SHRI -> {
                            int B = (inst >>> 16) & 0xFF;
                            int sC = ((inst >>> 24) & 0xFF) - 127;
                            Long ib = arithToLongOrNull(stack[base + B]);
                            if (ib != null) {
                                LuaValue r = LuaInteger.valueOf(shiftl(ib, -sC));
                                // shadow 对拍: SHRI = shiftLeft(ib, -sC)，op=SHR，v2=sC(正数，FlatArith 内部取负)
                                if (SHADOW_FLAT)
                                    shadowCheckArith(stack[base + B], LuaInteger.valueOf((long) sC), BinaryOp.SHR, r);
                                stack[ra] = r;
                                pc++;
                            }
                        }
                        // lvm.c:  -  op_arith: 寄存器/常量通用（Java 方法 op_arith）
                        case Opcodes.OP_ADD -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opArith(stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.ADD))
                                pc++;
                        }
                        case Opcodes.OP_SUB -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opArith(stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.SUB))
                                pc++;
                        }
                        case Opcodes.OP_MUL -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opArith(stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.MUL))
                                pc++;
                        }
                        // lvm.c: : savestate(L, ci); 除零情形
                        case Opcodes.OP_MOD -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            savestate(L, ci, pc);
                            if (opArithSlow(stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.MOD))
                                pc++;
                        }
                        // lvm.c: : op_arithf（无 savestate  -  POW 永不报错）
                        case Opcodes.OP_POW -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opArithSlow(stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.POW))
                                pc++;
                        }
                        // lvm.c: : op_arithf（无 savestate  -  DIV 永不报错）
                        case Opcodes.OP_DIV -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opArithSlow(stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.DIV))
                                pc++;
                        }
                        // lvm.c: : savestate(L, ci); 除零情形
                        case Opcodes.OP_IDIV -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            savestate(L, ci, pc);
                            if (opArithSlow(stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.IDIV))
                                pc++;
                        }
                        // lvm.c:  -  op_bitwise: inline integer fast path
                        case Opcodes.OP_BAND -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opBitwise(L, ci, pc, stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.BAND))
                                pc++;
                        }
                        case Opcodes.OP_BOR -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opBitwise(L, ci, pc, stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.BOR))
                                pc++;
                        }
                        case Opcodes.OP_BXOR -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opBitwise(L, ci, pc, stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.BXOR))
                                pc++;
                        }
                        // lvm.c:  -  op_bitwise: SHL/SHR 内联整数快路径
                        case Opcodes.OP_SHL -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opBitwise(L, ci, pc, stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.SHL))
                                pc++;
                        }
                        case Opcodes.OP_SHR -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opBitwise(L, ci, pc, stack, ra, stack[base + B], kBit != 0 ? k[C] : stack[base + C], BinaryOp.SHR))
                                pc++;
                        }

                        // -- 元方法回退 --
                        case Opcodes.OP_MMBIN -> {
                            int C = (inst >>> 24) & 0xFF;
                            int B = (inst >>> 16) & 0xFF;
                            int piInst = code[pc - 2];
                            int resultA = (piInst >>> 7) & 0xFF;
                            LuaValue raVal = stack[ra];
                            LuaValue rb = stack[base + B];
                            ci.savedpc = pc;
                            L.top = ci.top;
                            L.top = ci.top;
                            stack = L.stack;
                            tryBinTM(L, ci, base + resultA, ra, raVal, base + B, rb, C);
                            // java only: tryBinTM 内部 callTMres 可能 checkStack/growStack realloc L.stack，
                            //   调用后必须刷新，否则下一条指令用旧数组读到脏值（首次字符串算术触发
                            //   realloc 时第二个操作数错位 - literals.lua:309 locale 测试失败的根因）。
                            stack = L.stack;
                        }
                        case Opcodes.OP_MMBINI -> {
                            int C = (inst >>> 24) & 0xFF;
                            int B = (inst >>> 16) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            int sB = B - Opcodes.OFFSET_sC;
                            int piInst = code[pc - 2];
                            int resultA = (piInst >>> 7) & 0xFF;
                            LuaValue raVal = stack[ra];
                            ci.savedpc = pc;
                            L.top = ci.top;
                            L.top = ci.top;
                            stack = L.stack;
                            tryBiniTM(L, ci, base + resultA, ra, raVal, sB, kBit, C);
                            stack = L.stack;  // java only: tryBiniTM 可能 realloc，调用后刷新
                        }
                        case Opcodes.OP_MMBINK -> {
                            int C = (inst >>> 24) & 0xFF;
                            int B = (inst >>> 16) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            int piInst = code[pc - 2];
                            int resultA = (piInst >>> 7) & 0xFF;
                            LuaValue raVal = stack[ra];
                            LuaValue kb = k[B];
                            ci.savedpc = pc;
                            L.top = ci.top;
                            L.top = ci.top;
                            stack = L.stack;
                            tryBinAssocTM(L, ci, base + resultA, ra, raVal, -1, kb, kBit, C);
                            stack = L.stack;  // java only: tryBinAssocTM 可能 realloc，调用后刷新
                        }

                        // -- 一元运算 --
                        // lvm.c:  -  op_arith (unary): inline integer/float fast paths
                        case Opcodes.OP_UNM -> {
                            int B = (inst >>> 16) & 0xFF;
                            LuaValue v = stack[base + B];
                            LuaValue r;
                            if (v.tt_ == LuaValue.LUA_VNUMINT) {
                                r = LuaInteger.valueOf(-((LuaInteger) v).v);
                            } else if (v.tt_ == LuaValue.LUA_VNUMFLT) {
                                r = LuaFloat.valueOf(-((LuaFloat) v).v);
                            } else {
                                r = LuaArith.apply(UnaryOp.UNM, v);
                            }
                            if (r != null) stack[ra] = r;
                            else {
                                ci.savedpc = pc;
                                L.top = ci.top;
                                L.top = ci.top;
                                stack = L.stack;  // java only: tryBinTM可能触发realloc
                                tryBinTM(L, ci, ra, base + B, v, base + B, v, 18);  // TM_UNM
                                stack = L.stack;  // java only: 调用后刷新（tryBinTM 内部可能 realloc）
                            }
                        }
                        // lvm.c:  -  op_arith (bnot): inline integer fast path
                        case Opcodes.OP_BNOT -> {
                            int B = (inst >>> 16) & 0xFF;
                            LuaValue v = stack[base + B];
                            if (v.tt_ == LuaValue.LUA_VNUMINT) {
                                stack[ra] = LuaInteger.valueOf(~((LuaInteger) v).v);
                            } else {
                                LuaValue r = LuaArith.apply(UnaryOp.BNOT, v);
                                if (r != null) stack[ra] = r;
                                else {
                                    ci.savedpc = pc;
                                    L.top = ci.top;
                                    L.top = ci.top;
                                    stack = L.stack;  // java only: tryBinTM可能触发realloc
                                    tryBinTM(L, ci, ra, base + B, v, base + B, v, 19);  // TM_BNOT
                                    stack = L.stack;  // java only: 调用后刷新（tryBinTM 内部可能 realloc）
                                }
                            }
                        }
                        // lvm.c: OP_NOT  -  l_isfalse内联
                        case Opcodes.OP_NOT -> {
                            int B = (inst >>> 16) & 0xFF;
                            LuaValue v = stack[base + B];
                            // lobject.h: l_isfalse(o) = ttisnil(o) || ttisfalse(o)
                            stack[ra] = (v.tt_ == LuaValue.LUA_VNIL || v.tt_ == LuaValue.LUA_VFALSE) ? LuaValue.TRUE : LuaValue.FALSE;
                        }
                        // lvm.c: OP_LEN -> Protect(luaV_objlen(L, ra, vRB(i)))
                        case Opcodes.OP_LEN -> {
                            int B = (inst >>> 16) & 0xFF;
                            ci.savedpc = pc;
                            L.top = ci.top;
                            objlen(L, ci, ra, base + B, stack[base + B]);
                            stack = L.stack;  // java only: objlen 内 callTMres 可能触发 realloc
                        }

                        // -- OP_CONCAT --
                        // lvm.c: ProtectNT  -  仅 savepc，不 savestate
                        case Opcodes.OP_CONCAT -> {
                            int B = (inst >>> 16) & 0xFF;
                            L.top = ra + B;
                            ci.savedpc = pc;
                            stack[ra] = concatSharedStack(L, ci, ra, B);
                            stack = L.stack;  // java only: concat可能触发realloc
                            // lvm.c: checkGC(L, L->top.p)
                            LuaGC.checkGC(L.l_G, 64);
                        }

                        // -- OP_CLOSE --
                        // OP_CLOSE: Protect(luaF_close(L, ra, LUA_OK, 1))
                        // java diff: C 中 luaF_close 内 __close 出错时 luaD_throw 直接 longjmp 回外层 rawrunprotected；
                        // Java 中 callclosemethod catch 错误、closeUpvals 返回错误对象，故 OP_CLOSE 必须检查并抛出
                        case Opcodes.OP_CLOSE -> {
                            ci.savedpc = pc;
                            L.top = ci.top;
                            LuaValue closeErr = closeUpvalsYieldable(L, ra);
                            stack = L.stack;
                            if (closeErr != null) LuaErrors.error(closeErr);
                        }
                        case Opcodes.OP_TBC -> {
                            ci.savedpc = pc;
                            L.top = ci.top;  // halfProtect: savestate
                            LuaValue val = stack[ra];
                            // lfunc.c:  -  if (l_isfalse(s2v(level))) return;
                            if (val == null || !val.toboolean()) break;
                            // lfunc.c:  -  const TValue *tm = luaT_gettmbyobj(L, s2v(level), TM_CLOSE)
                            LuaValue closeMm = Metamethod.getTmByObj(L, val, Metamethod.CLOSE);
                            if (closeMm == null) {
                                // lfunc.c:  -  取变量名用于 luaL_error 消息
                                int idx = ra - (base - 1);  // slot relative to ci.func
                                String vname = LuaDebug.findLocalName(L, L.ci, idx);
                                if (vname == null) vname = "?";
                                LuaErrors.runErrorWithInfo("variable '" + vname + "' got a non-closable value");
                            }

                            newTbcUpval(L, ra);
                            L.tbclist = ra;
                        }

                        // -- OP_JMP --
                        case Opcodes.OP_JMP -> {
                            int off = ((inst >>> 7) & 0x1FFFFFF) - 0xFFFFFF;
                            pc += off;
                        }

                        // -- 比较 --
                        // lvm.c: op_order  -  内联整数/浮点快速路径，对齐 C 宏
                        case Opcodes.OP_EQ -> {
                            int B = (inst >>> 16) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opOrderEQ(L, ci, pc, stack[ra], stack[base + B]) == (kBit == 0))
                                pc++;
                        }
                        case Opcodes.OP_LT -> {
                            int B = (inst >>> 16) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opOrderLT(L, ci, pc, stack[ra], stack[base + B]) == (kBit == 0))
                                pc++;
                        }
                        case Opcodes.OP_LE -> {
                            int B = (inst >>> 16) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opOrderLE(L, ci, pc, stack[ra], stack[base + B]) == (kBit == 0))
                                pc++;
                        }
                        case Opcodes.OP_EQK -> {
                            int B = (inst >>> 16) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            if (opEqK(stack[ra], k[B]) == (kBit == 0)) pc++;
                        }
                        case Opcodes.OP_EQI -> {
                            int B = (inst >>> 16) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            int im = B - Opcodes.OFFSET_sC;
                            LuaValue raVal = stack[ra];
                            boolean cond;
                            if (raVal instanceof LuaInteger li) {
                                cond = li.v == im;
                            } else if (raVal instanceof LuaFloat lf) {
                                cond = lf.v == (double) im;
                            } else {
                                cond = false;
                            }
                            if (cond == (kBit == 0)) pc++;
                        }
                        // lvm.c: OP_LTI/LEI/GTI/GEI。四条低频路径共用逐字段等价 helper，
                        //   减小 execute 的 C2/ART 编译体积；比较方向、元方法与 pc/top 状态不变。
                        case Opcodes.OP_LTI, Opcodes.OP_LEI, Opcodes.OP_GTI, Opcodes.OP_GEI -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            int im = B - Opcodes.OFFSET_sC;
                            boolean gt = op == Opcodes.OP_GTI || op == Opcodes.OP_GEI;
                            boolean le = op == Opcodes.OP_LEI || op == Opcodes.OP_GEI;
                            boolean cond = opOrderICold(L, ci, pc, stack[ra], im, gt, le, C);
                            if (cond == (kBit == 0)) pc++;
                        }

                        // -- TEST/TESTSET --
                        // lvm.c: OP_TEST  -  l_isfalse内联
                        case Opcodes.OP_TEST -> {
                            int kBit = ((inst >>> 15) & 1);
                            LuaValue v = stack[ra];
                            boolean cond = !(v.tt_ == LuaValue.LUA_VNIL || v.tt_ == LuaValue.LUA_VFALSE);
                            if (cond == (kBit == 0)) pc++;
                        }
                        // lvm.c: OP_TESTSET  -  l_isfalse内联
                        case Opcodes.OP_TESTSET -> {
                            int B = (inst >>> 16) & 0xFF;
                            int kBit = ((inst >>> 15) & 1);
                            LuaValue v = stack[base + B];
                            boolean cond = !(v.tt_ == LuaValue.LUA_VNIL || v.tt_ == LuaValue.LUA_VFALSE);
                            if (cond == (kBit == 0)) {
                                pc++;
                            } else stack[ra] = v;
                        }

                        // -- OP_CALL --
                        // lvm.c: savepc(ci)  -  NOT savestate, OP_CALL manages L.top
                        // ldo.c: ccall + luaD_precall  -  C 中 l_sinline，此处为 LuaClosure 内联
                        case Opcodes.OP_CALL -> {
                            // java diff: 刷新 stack 局部变量 - 上一条指令（尤其 OP_GET*/OP_SET* 含
                            //   metamethod 重入）可能已触发 L.stack realloc，不刷新则 funcVal stale
                            stack = L.stack;
                            int B = (inst >>> 16) & 0xFF;
                            int nresults = ((inst >>> 24) & 0xFF) - 1;
                            if (B != 0) L.top = ra + B;

                            ci.savedpc = pc;
                            // ldo.c: luaD_precall  -  Lua 调 Lua 不追踪 nCcalls（仅 luaD_call
                            //   的 C 函数/__call 递增；对齐 C 到栈满才溢出，calls.lua:113 /
                            //   cstack.lua:172 依赖）。Java precallLua+continue 共享循环不耗 Java 栈
                            // ldo.c: luaD_precall LUA_VLCL 情形  -  precallLua（C 中 l_sinline）
                            int func = ra;
                            LuaValue funcVal = stack[func];
                            // ldo.c: switch(ttypetag)  -  标签分派，非 instanceof
                            if (funcVal.tt_ == (LuaValue.LUA_VLCL | LuaValue.BIT_ISCOLLECTABLE)) {
                                LuaClosure lc = (LuaClosure) funcVal;
                                ci = precallLua(L, func, nresults, lc);
                                continue startfunc;
                            } else {
                                // C 函数或 __call 元方法  -  回退到 precall
                                // ldo.c: OP_CALL 调 C 函数不动 nCcalls（见 LuaCall.precallC），
                                //   故此处无递减配对
                                CallInfo newci = LuaCall.precall(L, func, nresults);
                                stack = L.stack;  // java only: precall可能触发realloc
                                if (newci == null) {
                                    // C 函数已执行完毕
                                    trap = ci.trap ? 1 : L.hookmask;
                                } else {
                                    // Lua __call：切换 ci 后继续（Lua 不追踪 nCcalls）
                                    ci = newci;
                                    continue startfunc;
                                }
                            }
                        }

                        // -- OP_TAILCALL --
                        // lvm.c: savepc(ci)  -  NOT savestate, OP_TAILCALL manages L.top
                        // ldo.c: luaD_pretailcall  -  C 中 l_sinline，此处内联 LuaClosure 情形
                        case Opcodes.OP_TAILCALL -> {
                            int B = (inst >>> 16) & 0xFF;
                            int nparams1 = (inst >>> 24) & 0xFF;
                            int delta = (nparams1 != 0) ? ci.nextraargs + nparams1 : 0;
                            if (B != 0) L.top = ra + B;
                            else B = L.top - (ra);
                            ci.savedpc = pc;
                            if (((inst >>> 15) & 1) != 0) {
                                if (L.openupval != null) {
                                    for (int j = L.openupval.size() - 1; j >= 0; j--) {
                                        UpVal uv = L.openupval.get(j);
                                        if (uv.upisopen() && uv.slot() >= base) {
                                            uv.closeUpval();
                                            L.openupval.remove(j);
                                        }
                                    }
                                }
                            }
                            // ldo.c: luaD_pretailcall LUA_VLCL 情形  -  内联（C 中 l_sinline）
                            int func = ra;
                            LuaValue funcVal = stack[func];
                            // ldo.c: switch(ttypetag)  -  标签分派，非 instanceof
                            if (funcVal.tt_ == (LuaValue.LUA_VLCL | LuaValue.BIT_ISCOLLECTABLE)) {
                                // ldo.c: luaD_pretailcall LUA_VLCL case  -  抽为独立方法（对齐 C 独立函数），
                                // execute 被 JIT 后热方法会重新内联，不损失性能
                                pretailcallLua(L, ci, (LuaClosure) funcVal, func, B, delta);
                                continue startfunc;
                            } else {
                                // C 函数或 __call 元方法  -  回退到 preTailcall
                                int n = LuaCall.preTailcall(L, ci, func, B, delta);
                                stack = L.stack;  // java only: preTailcall可能触发realloc
                                if (n < 0) {
                                    // Lua 尾调用：不递增不递减 nCcalls（复用当前帧）
                                    continue startfunc;
                                } else {
                                    // C 函数尾调用：走 poscall+return 路径（nCcalls 未增故不减）
                                    ci.func -= delta;
                                    LuaCall.poscall(L, ci, n);
                                    trap = ci.trap ? 1 : L.hookmask;
                                    if ((ci.callstatus & CallInfo.CIST_FRESH) != 0) return;
                                    else {
                                        ci = ci.previous;
                                        continue returning;
                                    }
                                }
                            }
                        }

                        // -- OP_RETURN --
                        case Opcodes.OP_RETURN -> {
                            int B = (inst >>> 16) & 0xFF;
                            int nparams1 = (inst >>> 24) & 0xFF;
                            int n = B - 1;
                            if (n < 0) n = L.top - (ra);
                            // lvm.c: savepc(ci)  -  NOT savestate, OP_RETURN manages L.top
                            ci.savedpc = pc;
                            if (((inst >>> 15) & 1) != 0) {
                                ci.nres = n;
                                if (L.top < ci.top) L.top = ci.top;
                                // lfunc.c: luaF_close
                                LuaValue closeErr = closeUpvals(L, base, null, true, true);
                                stack = L.stack;  // java only: close可能触发realloc
                                if (closeErr != null) LuaErrors.error(closeErr);
                            }
                            if (nparams1 != 0) ci.func -= ci.nextraargs + nparams1;
                            L.top = ra + n;
                            LuaCall.poscall(L, ci, n);
                            // Lua 调 Lua 不追踪 nCcalls（对齐 C）
                            trap = ci.trap ? 1 : L.hookmask;
                            if ((ci.callstatus & CallInfo.CIST_FRESH) != 0) return;
                            else {
                                ci = ci.previous;
                                continue returning;
                            }
                        }

                        // -- OP_RETURN0 --
                        // lvm.c: savepc(ci)  -  NOT savestate
                        case Opcodes.OP_RETURN0 -> {
                            if (L.hookmask != 0) {
                                L.top = ra;
                                ci.savedpc = pc;
                                LuaCall.poscall(L, ci, 0);
                                trap = 1;
                            } else {
                                int nres = CallInfo.getNResults(ci.callstatus);
                                L.ci = ci.previous;
                                L.top = base - 1;
                                for (; nres > 0; nres--) {
                                    stack[L.top++] = LuaValue.NIL;
                                }
                            }
                            // Lua 调 Lua 不追踪 nCcalls（对齐 C）
                            if ((ci.callstatus & CallInfo.CIST_FRESH) != 0) return;
                            else {
                                ci = ci.previous;
                                continue returning;
                            }
                        }

                        // -- OP_RETURN1 --
                        // lvm.c: savepc(ci)  -  NOT savestate
                        case Opcodes.OP_RETURN1 -> {
                            if (L.hookmask != 0) {
                                L.top = ra + 1;
                                ci.savedpc = pc;
                                LuaCall.poscall(L, ci, 1);
                                trap = 1;
                            } else {
                                int nres = CallInfo.getNResults(ci.callstatus);
                                L.ci = ci.previous;
                                if (nres == 0) {
                                    L.top = base - 1;
                                } else {
                                    stack[base - 1] = stack[ra];
                                    L.top = base;
                                    for (; nres > 1; nres--) {
                                        stack[L.top++] = LuaValue.NIL;
                                    }
                                }
                            }
                            // Lua 调 Lua 不追踪 nCcalls（对齐 C）
                            if ((ci.callstatus & CallInfo.CIST_FRESH) != 0) return;
                            else {
                                ci = ci.previous;
                                continue returning;
                            }
                        }

                        // -- OP_FORLOOP -- lvm.c
                        // lvm.c: 整数循环内联(for performance)，浮点循环走 floatforloop(lvm.c)
                        case Opcodes.OP_FORLOOP -> {
                            int sBx = (inst >>> 15) & 0x1FFFF;
                            LuaValue step = stack[ra + 1];
                            // lvm.c: ttisinteger  -  标签检查，非 instanceof
                            if (step.tt_ == LuaValue.LUA_VNUMINT) {
                                LuaInteger is = (LuaInteger) step;
                                long count = ((LuaInteger) stack[ra]).v;
                                if (Long.compareUnsigned(count, 0) > 0) {
                                    long iv = ((LuaInteger) stack[ra + 2]).v;
                                    // java-only: 计数槽 ra 与控制变量 ra+2 每轮均新建 LuaInteger
                                    //   （分代 GC 下短命小对象近乎免费）。控制变量不可原地改：
                                    //   t[i]=v/local x=i 按引用别名，原地改会破坏语义。
                                    stack[ra] = LuaInteger.valueOf(count - 1);
                                    stack[ra + 2] = LuaInteger.valueOf(iv + is.v);
                                    pc -= sBx;
                                }
                            } else {
                                // lvm.c: else if (floatforloop(ra)) pc -= GETARG_Bx(i);
                                if (floatforloop(L, ra)) pc -= sBx;
                            }
                        }
                        // -- OP_FORPREP -- lvm.c
                        // lvm.c: if (forprep(L, ra)) pc += GETARG_Bx(i) + 1;  -  单次函数调用
                        case Opcodes.OP_FORPREP -> {
                            ci.savedpc = pc;
                            L.top = ci.top;
                            int sBx = (inst >>> 15) & 0x1FFFF;
                            // java-only: 整数数值 for 整循环扁平化（FlatIFor）。trap==0 保证无
                            //   debug hook（hook 需逐指令事件）。返回 -1 未接管时原样走装箱；
                            //   中途除零 bail 返回续跑 pc（状态已在引擎内写回并保存）。
                            if (trap == 0 && cl != null) {
                                int npc = FlatIFor.tryRun(L, cl, pc - 1, base, ra, code, k);
                                if (npc >= 0) {
                                    stack = L.stack;
                                    pc = npc;
                                    break;
                                }
                            }
                            if (forprep(L, ra) != 0) {
                                pc += sBx + 1;  // skip the loop
                            }
                            stack = L.stack;  // java only: forError/scannumber 理论上不 realloc，防御性刷新
                        }

                        // OP_TFORPREP: luaF_newtbcupval
                        // java-only: 编译器 forlist 把 closing 放 ra+2、ctrl 放 ra+3，与 C 相反
                        // OP_TFORPREP 交换 ra+2/ra+3，使 closing 在 ra+2（可标记 tbc）、ctrl 在 ra+3
                        case Opcodes.OP_TFORPREP -> {
                            int sBx = (inst >>> 15) & 0x1FFFF;
                            LuaValue temp = stack[ra + 3];
                            stack[ra + 3] = stack[ra + 2];
                            stack[ra + 2] = temp;
                            int tbcIdx = ra + 2;
                            LuaValue tbcVal = stack[tbcIdx];
                            boolean hasTbc = tbcVal != null && tbcVal.toboolean();
                            if (hasTbc) {
                                // lfunc.c: checkclosemth  -  值必须有 close 方法
                                LuaValue closeMm = Metamethod.getTmByObj(L, tbcVal, Metamethod.CLOSE);
                                if (closeMm == null) {
                                    // lvm.c: luaV_execute —— halfProtect 含 savepc（错误带 chunk:line）；
                                    // 隐藏控制变量在 C 里名为 '(for state)'（findLocalName
                                    // 对带括号的内部 locvar 名返回 null）
                                    int idx = tbcIdx - (base - 1);
                                    String vname = LuaDebug.findLocalName(L, L.ci, idx);
                                    if (vname == null) vname = "(for state)";
                                    LuaErrors.runErrorWithInfo("variable '" + vname + "' got a non-closable value");
                                }
                                // lvm.c:OP_TFORPREP -> luaF_newtbcupval
                                newTbcUpval(L, tbcIdx);
                                L.tbclist = tbcIdx;
                            }
                            // java-only: TFOR 扁平化  -  无 tbc 且无 hook 时，尝试整段 pairs 循环提取到
                            // JVM 局部变量（零装箱：值直读 array_numVals/node.value_num，body 在 long[] 跑）。
                            // 命中：一次跑完整循环，pc+=sBx+2 跳过 TFORCALL+TFORLOOP。
                            // 未命中/bail：pc+=sBx 走正常 TFORCALL 路径（bail 时已设 ctrl=lastKey 续跑）。
                            if (trap == 0 && !hasTbc
                                    && FlatTFor.tryRunTFor(L, cl, pc - 1, pc + sBx, base, ra, code, k)) {
                                pc += sBx + 2;  // 跳过 TFORCALL + TFORLOOP
                            } else {
                                pc += sBx;
                            }
                        }
                        // OP_TFORCALL: 调用迭代器 f(s,ctrl)
                        // lvm.c:  -  ProtectNT(luaD_call(L, ra+3, C))
                        // java-only: ctrl 在 ra+3（交换后），调用基址 ra+3，结果放 ra+3 起
                        // java diff: C 递归调用 luaV_execute；Java 用切 CallInfo+continue startfunc 避免递归
                        case Opcodes.OP_TFORCALL -> {
                            int C = (inst >>> 24) & 0xFF;
                            LuaValue func = stack[ra];
                            LuaValue state = stack[ra + 1];
                            // java-only fast path: 内联 next(t,k) 迭代器，跳过 C 函数调用机制
                            //   (precallC/callOnStack/poscall)。pairs() 是最常见 TFOR 迭代，next()
                            //   每轮的调用机制开销远大于迭代逻辑。
                            //   通过 NextFn 类型识别标准 next；每个 Globals 有独立函数对象。
                            //   trap==0 保证无 debug hook - hook 需 precallC 触发的 call/return 事件。
                            //   nextEntryOnStack 直写 ra+3/ra+4；nr==0 写 NIL 让 TFORLOOP 退出。
                            // java diff: 快路径逻辑在 FlatTFor.inlineNext（小而热的静态方法，利于 C2 内联）
                            if (trap == 0 && FlatTFor.INLINE_NEXT
                                    && FlatTFor.inlineNext(func, state, stack, ra)) {
                                // k,v 已在 ra+3,ra+4（或 NIL 在 ra+3）；下一指令 TFORLOOP 检查 ra+3。
                            } else {
                                LuaValue ctrl = stack[ra + 3];
                                stack[ra + 5] = ctrl;
                                stack[ra + 4] = state;
                                stack[ra + 3] = func;
                                L.top = ra + 3 + 3;
                                // lvm.c: ProtectNT  -  仅 savepc，不 savestate
                                ci.savedpc = pc;
                                // java diff: C 用 luaD_call 递归调 luaV_execute；Java 用 precall +
                                //   continue startfunc 避免 Java 方法递归（返回时 OP_RETURN 见无
                                //   CIST_FRESH -> ci=ci.previous 恢复）。
                                // ldo.c: OP_TFORCALL 的迭代器调用不动 nCcalls（同 OP_CALL），
                                //   故此处既不 ++ 也不 --
                                CallInfo newci = LuaCall.precall(L, ra + 3, C);
                                stack = L.stack;  // java only: precall可能触发realloc
                                if (newci != null) {
                                    // Lua 函数：切换 ci 并用 continue（避免递归）
                                    ci = newci;
                                    continue startfunc;
                                }
                                // C 函数已由 precallC 完成
                                base = ci.func + 1;
                            }
                        }
                        // OP_TFORLOOP: 首个结果非 nil 则跳回循环体
                        // java-only: 首个结果在 ra+3（调用基址 ra+3），ctrl 也在 ra+3
                        case Opcodes.OP_TFORLOOP -> {
                            int sBx = (inst >>> 15) & 0x1FFFF;
                            LuaValue ctrl = stack[ra + 3];
                            if (ctrl != null && !ctrl.isnil()) {
                                // lvm.c:OP_TFORLOOP 仅检查 ra+3；ra+2 保留隐式关闭对象。
                                pc -= sBx;
                            }
                        }

                        // -- OP_SETLIST --
                        case Opcodes.OP_SETLIST -> {
                            int vB = (inst >>> 16) & 0x3F;
                            int vC = (inst >>> 22) & 0x3FF;
                            int kBit = ((inst >>> 15) & 1);
                            int n = vB;
                            int last = vC;
                            LuaTable t = (LuaTable) stack[ra];
                            if (n == 0) {
                                n = L.top - (ra) - 1;
                            } else {
                                // lvm.c: L->top.p = ci->top.p; 紧急 GC 时修正 top
                                L.top = ci.top;
                            }
                            if (kBit != 0) {
                                last += ((code[pc] >>> 7) & 0x1FFFFFF) * (Opcodes.MAXARG_vC + 1);
                                pc++;
                            }
                            last += n;
                            // lvm.c: ；当 last > h->asize，把数组调整到精确大小
                            if (last > t.diagnosticArraySize()) {
                                t.resizeArray(last);  // ltable.c: luaH_resizearray
                            }
                            // lvm.c: : 对 n>0; obj2arr(h,last-1,val) + 每元素 luaC_barrierback
                            // java diff: 内联 obj2arr（fval2arr 直写，无 barrier）+ 单次 barrierback，
                            // 对齐 C 的无 barrier 宏语义。
                            // resize 后所有键 last-n+1..last 命中数组部分（asize>=last）
                            if (SETLIST_OPT) opSetlist(L, t, stack, ra, n, last);
                            else opSetlistOld(L, t, stack, ra, n, last);
                        }

                        // OP_CLOSURE: pushclosure
                        case Opcodes.OP_CLOSURE -> {
                            int Bx = (inst >>> 15) & 0x1FFFF;
                            Prototype cp = cl.p.p[Bx];
                            // lvm.c: checkGC(L, ra + 1)  -  紧急 GC 时修正 top
                            L.top = ra + 1;
                            LuaClosure ncl = pushclosure(L, cp, cl, base);
                            stack[ra] = ncl;
                            // lvm.c: checkGC(L, ra + 1)
                            LuaGC.checkGC(L.l_G, 64);
                        }

                        // OP_VARARG: luaT_getvarargs
                        case Opcodes.OP_VARARG -> {
                            // 抽出到 opVararg（体积治理，见其注释）。
                            // [必须回写 stack]helper 内 checkStack 可能换掉 L.stack 数组。
                            stack = opVararg(L, ci, stack, ra, inst);
                        }

                        case Opcodes.OP_GETVARG -> {
                            int B = (inst >>> 16) & 0xFF;
                            int C = (inst >>> 24) & 0xFF;
                            stack[ra] = opGetvarg(L, ci, stack[base + B], stack[base + C]);
                        }

                        // -- OP_ERRNNIL --
                        case Opcodes.OP_ERRNNIL -> {
                            int Bx = (inst >>> 15) & 0x1FFFF;
                            if (!stack[ra].isnil()) {
                                String name = (Bx > 0 && Bx - 1 < k.length && k[Bx - 1] instanceof LuaString s)
                                        ? s.toJavaString() : "?";
                                LuaErrors.error("global '" + name + "' already defined");
                            }
                        }

                        // OP_VARARGPREP: adjustVarargs/buildhiddenargs
                        case Opcodes.OP_VARARGPREP -> {
                            // lvm.c: ProtectNT(luaT_adjustvarargs(L, ci, cl->p))
                            adjustVarargs(L, ci, cl.p);
                            stack = L.stack;  // java only: checkStack 可能触发 realloc
                            base = ci.func + 1;
                            // OP_VARARGPREP: ProtectNT 更新 trap
                            trap = ci.trap ? 1 : 0;
                            if (trap != 0) {
                                hookCall(L, ci);
                                L.oldpc = 1;  // 下一条指令视为新行
                            }
                        }

                        // -- OP_EXTRAARG --
                        case Opcodes.OP_EXTRAARG -> {
                            // 被 LOADKX/NEWTABLE/SETLIST 消费，不应单独执行
                        }

                        default -> LuaErrors.error("unimplemented opcode: " + op);
                    }

                }
            }
        }
    }

    // -- 共享栈辅助方法 --

    // lvm.c: luaV_concat  -  在共享栈上连接值
    // java diff: C 用 copy2buff + luaS_newlstr；Java 用 byte[] + System.arraycopy 直拼
    // java-only: 整数直写 concat 的 A/B 开关（-Dluajvm.concatintfast=false 走通用 concat）
    private static final boolean CONCAT_INT_FAST =
            !"false".equalsIgnoreCase(System.getProperty("luajvm.concatintfast"));

    /**
     * java-only: {@code str..int} / {@code int..str} / {@code int..int} 的直写拼接。
     *
     * <p>不命中（任一侧是浮点、表、需元方法等）返回 {@code null}，调用方走原路径。
     * 浮点不走本路径：{@code tostringbuffFloat} 含"读回校验精度 + 整数样式补 .0"两步语义。
     */
    private static LuaValue concatIntFast(LuaValue a, LuaValue b) {
        boolean ai = a instanceof LuaInteger, bi = b instanceof LuaInteger;
        if (!ai && !bi) return null;
        // 另一侧必须是短串或整数（长串走原路径：长度上限判断与 createLngStrObj 记账在那边）
        int la, lb;
        if (ai) {
            la = LuaString.digitLen(((LuaInteger) a).v);
        } else if (a instanceof LuaString sa && sa.shrlen == sa.contents.length && sa.shrlen <= 40) {
            la = sa.shrlen;
        } else {
            return null;
        }
        if (bi) {
            lb = LuaString.digitLen(((LuaInteger) b).v);
        } else if (b instanceof LuaString sb && sb.shrlen == sb.contents.length && sb.shrlen <= 40) {
            lb = sb.shrlen;
        } else {
            return null;
        }
        byte[] bc = new byte[la + lb];
        if (ai) {
            LuaString.digitsInto(bc, 0, ((LuaInteger) a).v);
        } else {
            LuaString sa = (LuaString) a;
            System.arraycopy(sa.contents, 0, bc, 0, la);
        }
        if (bi) {
            LuaString.digitsInto(bc, la, ((LuaInteger) b).v);
        } else {
            LuaString sb = (LuaString) b;
            System.arraycopy(sb.contents, 0, bc, la, lb);
        }
        return LuaString.valueOfOwned(bc);
    }

    private static LuaValue concatSharedStack(LuaThread L, CallInfo ci, int start, int total) {
        if (total == 1) {
            return L.stack[start];
        }
        if (total == 2) {
            LuaValue a = L.stack[start];
            LuaValue b = L.stack[start + 1];
            // java-only: 整数侧直写数字位，跳过中间数字串的驻留。
            //   中间串产生后立刻是垃圾、Lua 侧不可观察；结果串照旧 valueOfOwned 驻留，
            //   故 rawequal/表键身份保真不变。
            if (CONCAT_INT_FAST) {
                LuaValue r = concatIntFast(a, b);
                if (r != null) return r;
            }
            LuaString sa = LuaConcat.toConcatString(a);
            LuaString sb = LuaConcat.toConcatString(b);
            if (sa != null && sb != null) {
                // lvm.c: isemptystr optimizations
                if (sb.shrlen == 0) return sa;
                if (sa.shrlen == 0) return sb;
                // lvm.c: luaV_concat —— 'string length overflow'（int 溢出会以
                // NegativeArraySizeException 露出 Java 异常而非 Lua 错误）
                if (sa.shrlen > Integer.MAX_VALUE - sb.shrlen)
                    LuaErrors.runErrorWithInfo("string length overflow");
                // lvm.c: copy2buff + luaS_newlstr/luaS_createlngstrobj
                byte[] bc = new byte[sa.shrlen + sb.shrlen];
                System.arraycopy(sa.contents, 0, bc, 0, sa.shrlen);
                System.arraycopy(sb.contents, 0, bc, sa.shrlen, sb.shrlen);
                return LuaString.valueOfOwned(bc);
            }
            // ltm.c: luaT_tryconcatTM  -  栈上 callbinTM（避免 Varargs 分配）
            // ltm.c: callbinTM(L, s2v(p1), s2v(p1+1), p1, TM_CONCAT)
            int tag = callbinTM(L, ci, start, a, b, Metamethod.CONCAT.ordinal());
            if (tag < 0) LuaErrors.concatError(a, b);
            return L.stack[start];
        }
        // java-only: 直接在 L.stack 上工作（无 vals[] 分配），对齐 C 的 concat
        // （C 直接操作 StkId p = L->top - total）。callbinTM 可能 realloc L.stack，
        // 故每次 callbinTM 后刷新局部 'stack'
        LuaValue[] stack = L.stack;
        int n = total;
        while (n > 1) {
            LuaValue left = stack[start + n - 2];
            LuaValue right = stack[start + n - 1];
            if ((left instanceof LuaString || left instanceof LuaNumber) &&
                    (right instanceof LuaString || right instanceof LuaNumber)) {
                // lvm.c: 汇总总长度并复制字符串
                int runStart = n - 2;
                while (runStart > 0 && (stack[start + runStart - 1] instanceof LuaString || stack[start + runStart - 1] instanceof LuaNumber))
                    runStart--;
                // lvm.c: copy2buff  -  direct byte copy (avoids StringBuilder+String+getBytes)
                // java diff: 缓存 strValue()，免 LuaNumber 的双重分配
                int count = n - runStart;
                LuaString[] strs = new LuaString[count];
                int totalLen = 0;
                for (int i = 0; i < count; i++) {
                    strs[i] = stack[start + runStart + i].strValue();
                    if (strs[i].shrlen > Integer.MAX_VALUE - totalLen)
                        LuaErrors.runErrorWithInfo("string length overflow");
                    totalLen += strs[i].shrlen;
                }
                byte[] bc = new byte[totalLen];
                int off = 0;
                for (int i = 0; i < count; i++) {
                    System.arraycopy(strs[i].contents, 0, bc, off, strs[i].shrlen);
                    off += strs[i].shrlen;
                }
                stack[start + runStart] = LuaString.valueOfOwned(bc, totalLen);
                n = runStart + 1;
            } else {
                // ltm.c: luaT_tryconcatTM  -  栈上 callbinTM
                // java-only: callbinTM 把结果写到 stack[start]。n > 2 时保存/恢复
                // stack[start]，使后续字符串连接运行看到原值
                LuaValue savedStart = (n > 2) ? stack[start] : null;
                int tag = callbinTM(L, ci, start, left, right, Metamethod.CONCAT.ordinal());
                if (tag < 0) LuaErrors.concatError(left, right);
                stack = L.stack;  // java only: callbinTM 可能触发 realloc
                stack[start + n - 2] = stack[start];
                if (n > 2) stack[start] = savedStart;
                n--;
            }
        }
        return stack[start];
    }

    // lvm.c: luaV_objlen  -  求长度运算 (#)，含 metamethod 回退；按 C 边界抽取为独立函数
    // （对齐 lvm.c: Protect(luaV_objlen(L, ra, vRB(i)))），使 OP_LEN 恢复为单次函数调用。
    // 调用方已置 ci.savedpc/L.top；本方法内 callTMres 可能触发 realloc，返回后由调用方刷新
    private static void objlen(LuaThread L, CallInfo ci, int ra, int rbSlot, LuaValue rb) {
        LuaValue[] stack = L.stack;
        // lvm.c: switch(ttypetag(rb))
        if (rb.istable()) {
            // LUA_VTABLE: fasttm(metatable, TM_LEN)
            LuaValue mt = rb.getmetatable();
            LuaValue mm = (mt != null) ? mt.rawget(LuaValue.LEN) : LuaValue.NIL;
            if (mm.isnil()) {
                stack[ra] = LuaInteger.valueOf(rb.rawlen());
                return;
            }
            callTMres(L, ci, ra, mm, rb, rb);
        } else if (rb instanceof LuaString) {
            // LUA_VSHRSTR / LUA_VLNGSTR: 原始长度
            stack[ra] = LuaInteger.valueOf(rb.rawlen());
        } else {
            // default: 试 metamethod，否则类型错误
            LuaValue mm = Metamethod.getTmByObj(L, rb, Metamethod.LEN);
            if (mm == null) LuaErrors.typeError(L, rbSlot, rb, "get length of");
            else callTMres(L, ci, ra, mm, rb, rb);
        }
    }

    // lvm.c: forprep  -  准备数值 for 循环，返回 1 表示跳过循环体、0 表示进入循环
    // C 中为独立函数 static int forprep(lua_State*, StkId ra)；此处按 C 边界抽取，
    // 使 luaV_execute (OP_FORPREP) 恢复为单次函数调用（对齐 lvm.c if(forprep(L,ra))）
    static int forprep(LuaThread L, int ra) {
        LuaValue[] stack = L.stack;
        LuaValue init = stack[ra];
        LuaValue limit = stack[ra + 1];
        LuaValue step = stack[ra + 2];
        // lvm.c: ttisinteger  -  标签检查，非 instanceof
        if (init.tt_ == LuaValue.LUA_VNUMINT && step.tt_ == LuaValue.LUA_VNUMINT) {
            long iv = ((LuaInteger) init).v;
            long is = ((LuaInteger) step).v;
            // lvm.c: forprep —— luaG_runerror 自动带 chunk:line
            if (is == 0) LuaErrors.runErrorWithInfo("'for' step is zero");
            long lv;
            if (limit instanceof LuaInteger li) {
                lv = li.v;
            } else {
                Long intBox = null;
                double flim = 0;
                boolean hasFlim = false;
                if (limit instanceof LuaFloat lf) {
                    double f = lf.todouble();
                    double r = Math.floor(f);
                    if (f != r && is < 0) r += 1;
                    intBox = fitsLong(r);
                    if (intBox == null) {
                        flim = f;
                        hasFlim = true;
                    }
                } else if (limit instanceof LuaString ls) {
                    LuaNumber nn = ls.scannumber();
                    if (nn == null) forError(limit, "limit");
                    else if (nn instanceof LuaInteger li2) intBox = li2.v;
                    else {
                        double f = nn.todouble();
                        double r = Math.floor(f);
                        if (f != r && is < 0) r += 1;
                        intBox = fitsLong(r);
                        if (intBox == null) {
                            flim = f;
                            hasFlim = true;
                        }
                    }
                } else {
                    forError(limit, "limit");
                    intBox = 0L; // 不可达
                }
                if (intBox != null) {
                    lv = intBox;
                } else {
                    if (flim > 0) {
                        if (is < 0) return 1;
                        lv = Long.MAX_VALUE;
                    } else {
                        if (is > 0) return 1;
                        lv = Long.MIN_VALUE;
                    }
                }
            }
            boolean skip = (is > 0) ? (iv > lv) : (iv < lv);
            if (!skip) {
                long count;
                if (is > 0) {
                    count = lv - iv;
                    if (is != 1) count = Long.divideUnsigned(count, is);
                } else {
                    count = iv - lv;
                    count = Long.divideUnsigned(count, (-(is + 1)) + 1);
                }
                stack[ra] = LuaInteger.valueOf(count);
                stack[ra + 1] = step;
                stack[ra + 2] = init;
                return 0;
            } else {
                return 1;
            }
        } else {
            // lvm.c: forprep —— 按 limit/step/init 顺序转换与报错
            double lim = forNumber(limit, "limit");
            double is = forNumber(step, "step");
            double iv = forNumber(init, "initial value");
            if (is == 0) LuaErrors.runErrorWithInfo("'for' step is zero");
            if ((is > 0 && lim < iv) || (is < 0 && iv < lim)) {
                return 1;
            } else {
                stack[ra] = LuaFloat.valueOf(lim);
                stack[ra + 1] = LuaFloat.valueOf(is);
                stack[ra + 2] = LuaFloat.valueOf(iv);
                return 0;
            }
        }
    }

    // lvm.c: floatforloop  -  执行浮点数值 for 循环的一步，返回 true 表示继续循环
    // C 中为独立函数 static int floatforloop(lua_State *L, StkId ra)；按 C 边界抽取
    // （整数分支仍内联在 OP_FORLOOP，对齐 C 注释 "integer case is written inline for performance"）
    private static boolean floatforloop(LuaThread L, int ra) {
        LuaValue[] stack = L.stack;
        double s = stack[ra + 1].todouble();
        double lim = stack[ra].todouble();
        double id = stack[ra + 2].todouble();
        id += s;
        if ((s > 0 && id <= lim) || (s < 0 && lim <= id)) {
            stack[ra + 2] = LuaFloat.valueOf(id);
            return true;
        }
        return false;
    }

    // ltm.c: luaT_adjustvarargs  -  调整 vararg 函数的栈布局
    // java diff: checkStack 可能 realloc，调用方须在返回后刷新 stack = L.stack
    private static void adjustVarargs(LuaThread L, CallInfo ci, Prototype p) {
        LuaValue[] stack = L.stack;
        int totalargs = L.top - ci.func - 1;
        int nfixparams = p.numparams;
        int nextra = totalargs - nfixparams;
        if (nextra < 0) nextra = 0;
        if ((p.flag & Prototype.PF_VATAB) != 0) {
            // PF_VATAB 模式
            // ltm.c: createvarargtab
            LuaTable t = new LuaTable(L.l_G, nextra, 1);
            // ltm.c: createvarargtab  -  luaS_new(L, "n")
            t.setEntry(LuaString.newStr("n"), LuaInteger.valueOf(nextra));
            for (int j = 0; j < nextra; j++) {
                t.setInt(j + 1, stack[ci.func + 1 + nfixparams + j]);
            }
            stack[ci.func + 1 + nfixparams] = t;
        } else {
            // PF_VAHID 模式
            // ltm.c: buildhiddenargs
            ci.nextraargs = nextra;
            int needed = p.maxstacksize + 1 + nfixparams + 1;
            checkStack(L, needed);
            stack = L.stack;  // java only: checkStack 可能触发 realloc
            stack[L.top++] = stack[ci.func];
            for (int j = 1; j <= nfixparams; j++) {
                stack[L.top++] = stack[ci.func + j];
                stack[ci.func + j] = LuaValue.NIL;  // 便于 GC
            }
            // buildhiddenargs 后 func 已移动
            ci.func += totalargs + 1;
            ci.top += totalargs + 1;
            stack[ci.func + nfixparams + 1] = LuaValue.NIL;
        }
    }

    // ldo.c: luaD_precall LUA_VLCL case  -  精确对齐 C 的 Lua 闭包预调用
    // C 中 luaD_precall 是独立函数，返回新 CallInfo（NULL 表示 C 函数已执行完）；
    // 此处抽出 LuaClosure 快速路径与 OP_CALL/OP_TFORCALL 内联版本等价（execute 被 JIT 后会重新内联）。
    // 返回新建的 CallInfo；调用方随后 ci=newci; continue startfunc（startfunc 会刷新 stack 引用）。
    static CallInfo precallLua(LuaThread L, int func, int nresults, LuaClosure lc) {
        Prototype p = lc.p;
        int narg = L.top - func - 1;
        int nfixparams = p.numparams;
        int fsize = p.maxstacksize;
        int needed = func + 1 + fsize - L.top;
        if (needed > 0) checkStack(L, needed);
        LuaValue[] stack = L.stack;  // java only: checkStack 可能触发 realloc
        CallInfo newci = L.extendCI();
        if (newci == null) {
            // ldo.c: next_ci luaB_error=1 -> luaM_error（ltests 分配受限）
            LuaErrors.error("not enough memory");
        }
        newci.func = func;
        newci.callstatus = nresults + 1;
        newci.top = func + 1 + fsize;
        newci.savedpc = 0;
        for (; narg < nfixparams; narg++) {
            stack[L.top++] = LuaValue.NIL;
        }
        return newci;
    }

    // ldo.c: luaD_pretailcall  -  LUA_VLCL case（对齐 C 独立函数，从 OP_TAILCALL 抽出）
    // 复用当前 ci 帧（尾调用），移动实参到 ci.func、补 nil、设 CIST_TAIL
    private static void pretailcallLua(LuaThread L, CallInfo ci, LuaClosure lc, int func, int B, int delta) {
        LuaValue[] stack = L.stack;
        Prototype p = lc.p;
        int fsize = p.maxstacksize;
        int nfixparams = p.numparams;
        int needed = func + 1 + fsize - L.top;
        if (needed > 0) {
            checkStack(L, needed);
            stack = L.stack;
        }
        ci.func -= delta;
        for (int i = 0; i < B; i++) {
            stack[ci.func + i] = stack[func + i];
        }
        func = ci.func;
        for (; B <= nfixparams; B++) {
            stack[func + B] = LuaValue.NIL;
        }
        ci.top = func + 1 + fsize;
        CallInfo.savepc(ci, 0);
        ci.callstatus |= CallInfo.CIST_TAIL;
        L.top = func + B;
    }

    // lvm.c: pushclosure
    // java diff: 省略 C 的 luaF_initupvals 空位兜底（C 对 upvals[i]==NULL 现建闭合 nil UpVal）。
    //   语义上父闭包的 upvals 必已初始化（递归到入口 chunk），null 不可达；
    //   Java 的 LuaClosure 构造器也不预置 NIL（数组默认 null）。
    private static LuaClosure pushclosure(LuaThread L, Prototype p, LuaClosure parent, int base) {
        Globals g = L.l_G;
        LuaClosure ncl = new LuaClosure(p, g);
        for (int i = 0; i < p.sizeupvalues; i++) {
            Prototype.Upvaldesc uv = p.upvalues[i];
            if (uv.instack) {
                UpVal box = findOrCreateOpenUpval(L, base + uv.idx);
                ncl.upvals[i] = box;
            } else {
                ncl.upvals[i] = parent.upvals[uv.idx];
            }
        }
        return ncl;
    }

    // lfunc.c: luaF_findupval  -  在给定栈层级查找或创建打开的上值
    // C: 遍历双向链表（openupval 按栈位置降序），uplevel(p)==level 则复用，否则 newupval 插入
    // java diff: ArrayList 线性搜索替代链表；不保证降序但功能等价（closeUpvals 反向遍历）
    // java diff: C 的 newupval 维护 twups 链表(L->twups=G(L)->twups)；Java 不需要（ArrayList 本身标识线程有开放 upvalue）
    private static UpVal findOrCreateOpenUpval(LuaThread L, int idx) {
        if (L.openupval != null) {
            for (int _gi = 0, _gn = L.openupval.size(); _gi < _gn; _gi++) {
                UpVal uv = L.openupval.get(_gi);
                if (uv.upisopen() && uv.slot() == idx) return uv;
            }
        } else {
            L.openupval = new ArrayList<>();
        }
        UpVal uv = new UpVal(L.stack, idx);
        L.openupval.add(uv);
        return uv;
    }

    // lfunc.c: luaF_newtbcupval  -  把变量插入 tbclist
    // C: 维护 delta 链表(StkId.tbclist.delta)，处理 MAXDELTA(USHRT_MAX) 限制
    // java diff: 用 L.tbclist 整数 + UpVal.tbc 标记替代 delta 链表；不处理 MAXDELTA 限制
    //    （Java 的 int 范围远大于 unsigned short，不需要 dummy node 分片）
    private static void newTbcUpval(LuaThread L, int ra) {
        UpVal uv = findOrCreateOpenUpval(L, ra);
        if (uv.tbc == 0) {
            uv.tbc = 1;
        }
    }

    // lfunc.c: luaF_close  -  关闭到 level 为止的所有上值与待关闭变量
    // C: luaF_closeupval(L, level) + 遍历 tbclist + prepcallclosemth
    // java diff: C 中 luaF_close 内 __close 出错时 luaD_throw 直接 longjmp 回 luaD_closeprotected；
    //    Java 返回错误对象让调用者抛出，closeUpvals 内部重试循环对齐 C 的 luaD_closeprotected
    // java diff: C 的 prepcallclosemth 按 status 设置 L.top 和 errobj；Java 在 closeUpvals 中内联处理
    // lfunc.c: luaF_close(L, level, LUA_OK, 1)  -  OP_CLOSE 调用
    public static LuaValue closeUpvals(LuaThread L, int level) {
        return closeUpvals(L, level, null, false, false);
    }

    // lfunc.c: luaF_close with yy=1（可 yield）
    public static LuaValue closeUpvalsYieldable(LuaThread L, int level) {
        return closeUpvals(L, level, null, false, true);
    }

    public static LuaValue closeUpvalsAtTop(LuaThread L, int level) {
        return closeUpvals(L, level, null, true, false);
    }

    public static LuaValue closeUpvals(LuaThread L, int level, LuaValue err) {
        return closeUpvals(L, level, err, false, false);
    }

    public static LuaValue closeUpvals(LuaThread L, int level, LuaValue err, boolean yieldable) {
        return closeUpvals(L, level, err, false, yieldable);
    }

    // lfunc.c: luaF_close  -  yy 控制 __close 是否可 yield
    // ldo.c: luaD_closeprotected  -  每次 __close 调用由 rawrunprotected 保护
    private static LuaValue closeUpvals(LuaThread L, int level, LuaValue err, boolean closeTop, boolean yieldable) {
        if (L.openupval == null) {
            return err;
        }
        // ldo.c: luaD_closeprotected  -  持续关闭上值直到无更多错误
        CallInfo oldCi = L.ci;
        byte oldAllowhook = L.allowhook;
        int savedTop = L.top;
        int savedNCcalls = L.nCcalls;
        int savedNny = L.nny;
        // java-only: 保存 __close 出错时的 LuaError.savedStack，供后续 traceback
        ArrayList<Globals.DebugFrame> closeSavedStack = null;
        for (; ; ) {
            boolean hadError = false;
            for (int j = L.openupval.size() - 1; j >= 0; j--) {
                UpVal uv = L.openupval.get(j);
                if (!uv.upisopen() || uv.slot() < level) continue;
                if (uv.tbc != 0) {
                    LuaValue tbcVal = uv.get();
                    uv.tbc = 0;
                    if (tbcVal != null && tbcVal.toboolean()) {
                        if (!closeTop) {
                            L.top = uv.slot() + 1 + (err != null ? 1 : 0);
                        }
                        try {
                            err = callclosemethod(L, tbcVal, err, yieldable);
                        } catch (LuaError e) {
                            // java diff: CloseSelf 必须传播到 runCoroutine，不能在此捕获
                            if (e instanceof LuaThread.CloseSelf) throw e;
                            // ldo.c: luaD_closeprotected  -  发生 luaL_error；恢复保存状态并重试
                            // ldo.c: luaD_rawrunprotected  -  恢复 nCcalls/nny
                            L.nCcalls = savedNCcalls;
                            L.nny = savedNny;
                            L.ci = oldCi;
                            L.allowhook = oldAllowhook;
                            err = e.luaError != null ? e.luaError : LuaString.newStr(e.getMessage());
                            // java-only: 保存 LuaError 的 savedStack，供后续 traceback
                            // java-only: 惰性快照 - 确保从 throwCi 快照后再读取
                            e.ensureSnapshot();
                            if (e.savedStack != null) closeSavedStack = e.savedStack;
                            hadError = true;
                            break;
                        }
                    }
                }
                uv.closeUpval();
                L.openupval.remove(j);
            }
            if (!hadError) {
                L.top = savedTop;
                // java-only: 把保存的 savedStack 存到 L.closeSavedStack，供 LuaValue.error 使用
                L.closeSavedStack = closeSavedStack;
                return err;
            }
            // hadError: 重新循环，已标记 tbc=0 的变量不会再被关闭
        }
    }

    // callclosemethod: lfunc.c
    // C: luaT_gettmbyobj(L, obj, TM_CLOSE) -> push tm, obj, [err] -> luaD_call/luaD_callnoyield
    // java diff: C 中 luaD_callnoyield 出错时 luaD_throw 直接 longjmp 回外层 rawrunprotected；
    // Java 中不 catch 错误，让 LuaError 自然传播到 closeUpvals 的重试循环
    // java diff: C 不检查 tm 是否为 nil，直接放到栈上调用；tm 为 nil 时 luaD_callnoyield 触发
    // "attempt to call a nil value" 错误。Java 也必须调用 nil 值以触发相同错误。
    // lfunc.c: yy=1 -> luaD_call (yieldable), yy=0 -> luaD_callnoyield
    private static LuaValue callclosemethod(LuaThread L, LuaValue obj, LuaValue err, boolean yieldable) {
        // lfunc.c:  -  const TValue *tm = luaT_gettmbyobj(L, obj, TM_CLOSE)
        LuaValue closeMm = Metamethod.getTmByObj(L, obj, Metamethod.CLOSE);
        if (closeMm == null)
            closeMm = LuaValue.NIL;  // nil will trigger "attempt to call a nil value"
        // lfunc.c: callclosemethod；正常关闭仅传 luaK_self，异常关闭才追加错误对象。
        if (yieldable) {
            if (err == null) {
                LuaCall.invoke(closeMm, obj);
            } else {
                LuaCall.invoke(closeMm, Varargs.of(obj, err));
            }
        } else {
            if (err == null) {
                LuaCall.invokeNoYield(closeMm, obj);
            } else {
                LuaCall.invokeNoYield(closeMm, Varargs.of(obj, err));
            }
        }
        return err;
    }

    // -- 关闭线程帧（协程关闭用） --


    // luaE_resetthread + luaD_closeprotected
    // java-only: catch(CloseSelf) 替代 setjmp/longjmp
    public static LuaValue resetThread(Globals globals, LuaThread thread, LuaValue err) {
        if (thread != null && thread.stack != null) {
            LuaThread prevRunning = globals != null ? globals.running : null;
            int callerNCcalls = prevRunning != null ? prevRunning.nCcalls : 0;
            try {
                if (globals != null) globals.running = thread;
                thread.nCcalls = callerNCcalls;
                // resetCI: yield 时 ci 指向 yield 帧，关闭前必须重置，否则 CallInfo 链损坏
                thread.ci = thread.base_ci;
                // 重置 top: yield 时 top 指向 yield 点，关闭前重置到 base_ci.top
                thread.top = thread.base_ci.top;
                // luaD_closeprotected: err 作为初始错误状态传入 luaF_close
                err = closeUpvals(thread, 0, err);
            } catch (LuaThread.CloseSelf cs) {
                // java diff: C 中 luaD_throwbaselevel 的 longjmp 跳过所有
                // 中间 setjmp 点直接回到 runCoroutine 的 rawrunprotected。
                // Java 中 CloseSelf 必须传播到 runCoroutine，不能在此捕获
                throw cs;
            } finally {
                if (globals != null) globals.running = prevRunning;
            }
        }
        return err;
    }


    // -- 调试名称解析 --

    public static DebugName resolveFrameName(Globals.DebugFrame frame) {
        if (frame == null) return null;

        Globals.DebugFrame.Extras ex = frame.extrasIfPresent();
        if (ex != null && ex.name != null) {
            return new DebugName(ex.name, ex.namewhat);
        }

        return null;
    }

    // ltm.c: getnumargs
    private static int getnumargs(LuaThread L, CallInfo ci, LuaTable h) {
        if (h == null)
            return ci.nextraargs;
        else {
            // ltm.c: luaH_getshortstr(h, luaS_new(L, "n"), &res)
            // java diff: 不缓存 "n" 字符串  -  C 每次都调 luaS_new
            LuaValue res = h.fastGetShortStr(LuaString.newStr("n"));
            if (!(res instanceof LuaInteger))
                LuaErrors.runError("vararg table has no proper 'n'");
            long n = ((LuaInteger) res).v;
            // ltm.c: : l_castS2U(ivalue(&res)) > cast_uint(INT_MAX/2)
            // java diff: long 有符号，故 n<0 单独检查（C 用无符号转换）
            if (n < 0 || n > Integer.MAX_VALUE / 2)
                LuaErrors.runError("vararg table has no proper 'n'");
            return (int) n;
        }
    }

    // lvm.c: op_arith  -  仅内联 ADD/SUB/MUL 快路径。
    // java diff: C 用宏内联全部算术；Java 拆为 op_arith（ADD/SUB/MUL）+ opArithSlow
    //   （MOD/DIV/IDIV/POW 走 LuaArith.apply），紧凑方法利于 JIT 内联。
    private static boolean opArith(LuaValue[] stack, int ra, LuaValue v1, LuaValue v2, BinaryOp op) {
        int tt1 = v1.tt_, tt2 = v2.tt_;
        LuaValue r;
        if (tt1 == LuaValue.LUA_VNUMINT && tt2 == LuaValue.LUA_VNUMINT) {
            long a = ((LuaInteger) v1).v, b = ((LuaInteger) v2).v;
            r = LuaInteger.valueOf(op == BinaryOp.ADD ? a + b : op == BinaryOp.SUB ? a - b : a * b);
        } else if ((tt1 == LuaValue.LUA_VNUMINT || tt1 == LuaValue.LUA_VNUMFLT) &&
                (tt2 == LuaValue.LUA_VNUMINT || tt2 == LuaValue.LUA_VNUMFLT)) {
            double a = v1.todouble(), b = v2.todouble();
            r = LuaFloat.valueOf(op == BinaryOp.ADD ? a + b : op == BinaryOp.SUB ? a - b : a * b);
        } else {
            r = LuaArith.apply(op, v1, v2);
        }
        if (r != null) {
            // 影子对拍：SHADOW_FLAT=false 时整块被 DCE，零开销零回退。
            if (SHADOW_FLAT) shadowCheckArith(v1, v2, op, r);
            stack[ra] = r;
            return true;
        }
        return false;
    }

    // =======================================================================
    // java-only: C 的 op_arith/op_arithK/op_bitwise*/op_order* 宏（lvm.c）在 Java 抽为同名方法，
    //   使 luaV_execute 主循环字节码 < C2 HugeMethodLimit(8000) 从而可被 JIT 编译。
    //   返回 true => 调用方 pc++ 跳过 OP_MMBIN；LuaArith.apply 不调元方法/不触发栈 realloc，故直写 stack[ra] 安全。
    // =======================================================================

    // lvm.c: MOD/IDIV/DIV/POW 的 op_arith/op_arithf  -  委托给 LuaArith.apply。
    // MOD/IDIV: 调用前调用方必须 savestate（/0 经 runErrorWithInfo 报错，用预存的 ci.savedpc 取行信息）。
    // DIV/POW: 无需 savestate（op_arithf，无 luaL_error  -  /0 得 inf/nan）。
    // 保持小方法  -  JIT 先内联它，再内联热门的 LuaArith.apply。
    private static boolean opArithSlow(LuaValue[] stack, int ra, LuaValue v1, LuaValue v2, BinaryOp op) {
        LuaValue r = LuaArith.apply(op, v1, v2);
        if (r != null) {
            if (SHADOW_FLAT) shadowCheckArith(v1, v2, op, r);
            stack[ra] = r;
            return true;
        }
        return false;
    }

    // lvm.c: op_arithI  -  立即数算术 (ADDI)，sC 已解码为有符号立即数
    private static boolean opArithIAdd(LuaValue[] stack, int ra, LuaValue v1, int sC) {
        int tt1 = v1.tt_;
        if (tt1 == LuaValue.LUA_VNUMINT) {
            LuaValue r = LuaInteger.valueOf(((LuaInteger) v1).v + sC);
            // shadow 对拍: ADDI int 快路径对拍（SHADOW_FLAT=false 时 DCE，零开销）
            if (SHADOW_FLAT) shadowCheckArith(v1, LuaInteger.valueOf((long) sC), BinaryOp.ADD, r);
            stack[ra] = r;
            return true;
        } else if (tt1 == LuaValue.LUA_VNUMFLT) {
            LuaValue r = LuaFloat.valueOf(((LuaFloat) v1).v + sC);
            if (SHADOW_FLAT) shadowCheckArith(v1, LuaInteger.valueOf((long) sC), BinaryOp.ADD, r);
            stack[ra] = r;
            return true;
        }
        LuaValue r = LuaArith.apply(BinaryOp.ADD, v1, LuaInteger.valueOf((long) sC));
        if (r != null) {
            if (SHADOW_FLAT) shadowCheckArith(v1, LuaInteger.valueOf((long) sC), BinaryOp.ADD, r);
            stack[ra] = r;
            return true;
        }
        return false;
    }

    // lvm.c: op_bitwiseK  -  k[C] 恒为 LuaInteger（C 用 ivalue(v2)），内联 int 快速路径
    private static boolean opBitwiseK(LuaThread L, CallInfo ci, int pc, LuaValue[] stack, int ra,
                                      LuaValue v1, LuaValue kc, long i2, BinaryOp op) {
        if (v1.tt_ == LuaValue.LUA_VNUMINT) {
            long a = ((LuaInteger) v1).v;
            LuaValue r = LuaInteger.valueOf(op == BinaryOp.BAND ? a & i2 : op == BinaryOp.BOR ? a | i2 : a ^ i2);
            // shadow 对拍: 位运算 K 形式 int 快路径对拍
            if (SHADOW_FLAT) shadowCheckArith(v1, kc, op, r);
            stack[ra] = r;
            return true;
        }
        ci.savedpc = pc;
        L.top = ci.top;
        LuaValue r = LuaArith.apply(op, v1, kc);
        if (r != null) {
            if (SHADOW_FLAT) shadowCheckArith(v1, kc, op, r);
            stack[ra] = r;
            return true;
        }
        return false;
    }

    // lvm.c: op_bitwise  -  寄存器/常量通用位运算，内联 int 快速路径
    private static boolean opBitwise(LuaThread L, CallInfo ci, int pc, LuaValue[] stack, int ra,
                                     LuaValue v1, LuaValue v2, BinaryOp op) {
        if (v1.tt_ == LuaValue.LUA_VNUMINT && v2.tt_ == LuaValue.LUA_VNUMINT) {
            long a = ((LuaInteger) v1).v, b = ((LuaInteger) v2).v;
            long res = switch (op) {
                case BAND -> a & b;
                case BOR -> a | b;
                case BXOR -> a ^ b;
                case SHL -> LuaArith.shiftLeft(a, b);
                case SHR -> LuaArith.shiftLeft(a, -b);
                default -> 0L;
            };
            LuaValue r = LuaInteger.valueOf(res);
            // shadow 对拍: 位运算寄存器形式 int 快路径对拍
            if (SHADOW_FLAT) shadowCheckArith(v1, v2, op, r);
            stack[ra] = r;
            return true;
        }
        ci.savedpc = pc;
        L.top = ci.top;
        LuaValue r = LuaArith.apply(op, v1, v2);
        if (r != null) {
            if (SHADOW_FLAT) shadowCheckArith(v1, v2, op, r);
            stack[ra] = r;
            return true;
        }
        return false;
    }

    // lvm.c: op_order  -  寄存器比较（EQ 用 raweq/元方法，LT/LE 用序比较），内联 int/float 快速路径
    // 返回 cond；调用方据 (cond != kBit) 决定 pc++。慢路径可能触发元方法 realloc，
    // 但结果仅用于 pc 判断、不写 stack，故调用方不必刷新 stack。
    private static boolean opOrderEQ(LuaThread L, CallInfo ci, int pc, LuaValue raVal, LuaValue rbVal) {
        if (raVal instanceof LuaInteger ai && rbVal instanceof LuaInteger bi) return ai.v == bi.v;
        if (raVal instanceof LuaInteger ai && rbVal instanceof LuaFloat bf)
            return LuaCompare.intEqFloat(ai.v, bf.v);
        if (raVal instanceof LuaFloat af && rbVal instanceof LuaInteger bi)
            return LuaCompare.floatEqInt(af.v, bi.v);
        if (raVal instanceof LuaNumber && rbVal instanceof LuaNumber)
            return raVal.todouble() == rbVal.todouble();
        if (raVal.raweq(rbVal)) return true;
        ci.savedpc = pc;
        L.top = ci.top;
        return LuaCompare.equalObj(L, raVal, rbVal);
    }

    private static boolean opOrderLT(LuaThread L, CallInfo ci, int pc, LuaValue raVal, LuaValue rbVal) {
        if (raVal instanceof LuaInteger ai && rbVal instanceof LuaInteger bi) return ai.v < bi.v;
        if (raVal instanceof LuaInteger ai && rbVal instanceof LuaFloat bf)
            return LuaCompare.ltIntFloat(ai.v, bf.v);
        if (raVal instanceof LuaFloat af && rbVal instanceof LuaInteger bi)
            return LuaCompare.ltFloatInt(af.v, bi.v);
        if (raVal instanceof LuaNumber && rbVal instanceof LuaNumber)
            return raVal.todouble() < rbVal.todouble();
        if (raVal instanceof LuaString sa && rbVal instanceof LuaString sb)
            return sa.lStrcmp(sb) < 0;
        ci.savedpc = pc;
        L.top = ci.top;
        return LuaCompare.lessThan(L, raVal, rbVal);
    }

    private static boolean opOrderLE(LuaThread L, CallInfo ci, int pc, LuaValue raVal, LuaValue rbVal) {
        if (raVal instanceof LuaInteger ai && rbVal instanceof LuaInteger bi) return ai.v <= bi.v;
        if (raVal instanceof LuaInteger ai && rbVal instanceof LuaFloat bf)
            return LuaCompare.leIntFloat(ai.v, bf.v);
        if (raVal instanceof LuaFloat af && rbVal instanceof LuaInteger bi)
            return LuaCompare.leFloatInt(af.v, bi.v);
        if (raVal instanceof LuaNumber && rbVal instanceof LuaNumber)
            return raVal.todouble() <= rbVal.todouble();
        if (raVal instanceof LuaString sa && rbVal instanceof LuaString sb)
            return sa.lStrcmp(sb) <= 0;
        ci.savedpc = pc;
        L.top = ci.top;
        return LuaCompare.lessEqual(L, raVal, rbVal);
    }

    // lvm.c: op_eqK  -  R[A] 与 K[B] 相等比较（EQK），K 恒为可比较常量，无元方法慢路径
    private static boolean opEqK(LuaValue raVal, LuaValue rbVal) {
        if (raVal instanceof LuaInteger ai && rbVal instanceof LuaInteger bi) return ai.v == bi.v;
        if (raVal instanceof LuaInteger ai && rbVal instanceof LuaFloat bf)
            return LuaCompare.intEqFloat(ai.v, bf.v);
        if (raVal instanceof LuaFloat af && rbVal instanceof LuaInteger bi)
            return LuaCompare.floatEqInt(af.v, bi.v);
        if (raVal instanceof LuaNumber && rbVal instanceof LuaNumber)
            return raVal.todouble() == rbVal.todouble();
        return raVal.raweq(rbVal);
    }

    // lvm.c: op_orderI  -  R[A] 与立即数 im 序比较（LTI/LEI/GTI/GEI）
    // gt=true 为 im<R[A] 方向（GTI/GEI）；le=true 为 <= 比较（LEI/GEI）。
    // 返回 int：0/1=cond，-1=需慢路径（调用方按 C 用 imv 走 lessThan/lessequal）
    private static int opOrderI(LuaValue raVal, int im, boolean gt, boolean le, int C) {
        if (C == 0 && raVal instanceof LuaInteger li) {
            boolean cond = gt ? (le ? im <= li.v : im < li.v) : (le ? li.v <= im : li.v < im);
            return cond ? 1 : 0;
        } else if (raVal instanceof LuaFloat lf) {
            double d = lf.v, imd = im;
            boolean cond = gt ? (le ? imd <= d : imd < d) : (le ? d <= imd : d < imd);
            return cond ? 1 : 0;
        }
        return -1;
    }

    // lvm.c: OP_LTI/LEI/GTI/GEI 慢路径；从 execute 抽离，不改变任何 Lua 可见状态。
    private static boolean opOrderICold(LuaThread L, CallInfo ci, int pc, LuaValue raVal,
                                        int im, boolean gt, boolean le, int C) {
        int r = opOrderI(raVal, im, gt, le, C);
        if (r >= 0) return r != 0;
        LuaValue imv = C != 0 ? LuaFloat.valueOf((double) im) : LuaInteger.valueOf(im);
        ci.savedpc = pc;
        L.top = ci.top;
        if (gt) {
            return le ? LuaCompare.lessEqual(L, imv, raVal) : LuaCompare.lessThan(L, imv, raVal);
        }
        return le ? LuaCompare.lessEqual(L, raVal, imv) : LuaCompare.lessThan(L, raVal, imv);
    }

    /**
     * C：lvm.c : luaV_finishget
     * Java 宿主优化：默认 Java 元表的转发函数只调用 JavaObject.get，直接完成这次访问，
     * 省去一次元方法 CallInfo 和栈帧。元表、集合和转发函数身份只要有一项被改写，就返回
     * 空值并继续走完整的 Lua 元方法协议；空值结果本身由 JavaObject.get 统一表示为 NIL。
     */
    private static LuaValue tryJavaGet(LuaThread L, CallInfo ci, int pc, LuaValue t, LuaValue key) {
        if (!(t instanceof JavaObject object)) return null;
        // 有调试 hook 时必须回到完整元方法调用，保留 call/return/line 的可观察帧。
        // C 栈接近上限时同样交给 precallC 做统一溢出检查。
        if (L.hookmask != 0 || L.nCcalls >= Globals.LUAI_MAXCCALLS - 1) return null;
        // 构造器返回的 userdata 可能尚未写入表而没有所属状态；第一次进入 VM 时补绑定，
        // 使类级元表查找与 Lua 栈所属 Globals 一致，同时让后续 GC 能看到该对象。
        if (object.owner() == null) LuaTable.bindValue(L.l_G, object);
        if (object.hasDefaultJavaIndex()) {
            // C：lvm.c : savestate
            // getter 可能进入 Java 代码并通过宿主代理回调 Lua；先保存当前帧，
            // 与未命中时的 luaV_finishget 保护路径保持相同的 pc/top 不变量。
            ci.savedpc = pc;
            L.top = ci.top;
            return object.get(key);
        }
        return null;
    }

    /**
     * C：lvm.c : luaV_finishset
     * Java 宿主优化：仅对未改写的默认 Java __newindex 转发表直接调用 set；其他对象继续
     * 经过 LuaIndex 的元方法链，保持集合和用户自定义元表的可观察行为。
     */
    private static boolean tryJavaSet(LuaThread L, CallInfo ci, int pc, LuaValue t,
                                      LuaValue key, LuaValue value) {
        if (!(t instanceof JavaObject object)) return false;
        if (L.hookmask != 0 || L.nCcalls >= Globals.LUAI_MAXCCALLS - 1) return false;
        if (object.owner() == null) LuaTable.bindValue(L.l_G, object);
        if (object.hasDefaultJavaNewIndex()) {
            // C：lvm.c : savestate
            // setter 与 onXxx 监听器都可能重入 Lua；状态保存必须先于 Java 调用。
            ci.savedpc = pc;
            L.top = ci.top;
            object.set(key, value);
            return true;
        }
        return false;
    }

    // lvm.c: OP_GETTABLE  -  luaV_fastget 内联整数/短串快速路径，否则 luaV_finishget。
    // 慢路径 finishGet 可能触发元方法 realloc，故返回刷新后的 stack 供调用方同步。
    private static LuaValue[] opGettable(LuaThread L, CallInfo ci, int pc, LuaValue[] stack, int ra,
                                         int base, int B, int C, int kBit, LuaValue[] k) {
        LuaValue t = stack[base + B];
        LuaValue key = kBit != 0 ? k[C] : stack[base + C];
        int fastPath = 0;
        if (t instanceof LuaTable table) {
            if (key instanceof LuaInteger ki) {
                long kl = ki.tolong();
                if (kl >= 1 && kl <= Integer.MAX_VALUE) {
                    fastPath = 1;
                    int u = (int) kl - 1;
                    if (table.array_tags != null && u < table.array_tags.length) {
                        // java diff: 内联 T_INT 快速路径（同 op_geti），免 farr2val 方法调用 + switch
                        byte tag = table.array_tags[u];
                        if (tag == FlatArith.T_INT) {
                            stack[ra] = LuaInteger.valueOf(table.array_numVals[u]);
                            return stack;
                        }
                        if (tag != 0) {
                            stack[ra] = LuaTable.farr2val(table.array_tags, table.array_numVals, table.array_refs, u);
                            return stack;
                        }
                    }
                }
                if (kl >= Integer.MIN_VALUE && kl <= Integer.MAX_VALUE) {
                    fastPath = 1;
                    LuaValue r = table.fastGetiHash((int) kl);
                    if (r != null) {
                        stack[ra] = r;
                        return stack;
                    }
                }
            } else if (key instanceof LuaString ks && ks.tt_ == LuaValue.LUA_VSHRSTR) {
                fastPath = 1;
                LuaValue v = table.fastGetShortStr(ks);
                if (v != null) {
                    stack[ra] = v;
                    return stack;
                }
            }
        } else {
            fastPath = -1;
        }
        LuaValue javaResult = tryJavaGet(L, ci, pc, t, key);
        if (javaResult != null) {
            // Java 绑定可重入 Lua，回调可能替换 L.stack。
            stack = L.stack;
            stack[ra] = javaResult;
            return stack;
        }
        ci.savedpc = pc;
        L.top = ci.top;
        if (fastPath != 0) {
            LuaValue res = LuaIndex.finishGetFromVM(t, key, L, ra, base + B, fastPath > 0);
            stack = L.stack;
            stack[ra] = res;
        } else {
            LuaValue res = LuaIndex.finishGet(t, key, L, ra, base + B);
            stack = L.stack;
            stack[ra] = res;
        }
        return stack;
    }

    // lvm.c: OP_GETI  -  luaV_fastgeti 内联数组/哈希，否则 luaV_finishgeti_fromVM。
    // 慢路径可能触发元方法 realloc，故返回刷新后的 stack。
    private static LuaValue[] opGeti(LuaThread L, CallInfo ci, int pc, LuaValue[] stack, int ra,
                                     int base, int B, int C) {
        LuaValue t = stack[base + B];
        LuaTable table = t instanceof LuaTable tbl ? tbl : null;
        boolean isTable = table != null;
        if (isTable) {
            int u = C - 1;
            if (u >= 0 && table.array_tags != null && u < table.array_tags.length) {
                // java diff: 内联 T_INT 快速路径 - 整数数组元素是最常见 case，
                // 直接 LuaInteger.valueOf(numVals[u]) 免 farr2val 方法调用 + switch 分派。
                // 其余 tag 走 farr2val 统一路径（语义等价，C 的 farr2val 宏本就是内联）。
                byte tag = table.array_tags[u];
                if (tag == FlatArith.T_INT) {
                    stack[ra] = LuaInteger.valueOf(table.array_numVals[u]);
                    return stack;
                }
                if (tag != 0) {
                    stack[ra] = LuaTable.farr2val(table.array_tags, table.array_numVals, table.array_refs, u);
                    return stack;
                }
            } else {
                LuaValue r = table.fastGetiHash(C);
                if (r != null) {
                    stack[ra] = r;
                    return stack;
                }
            }
        }
        LuaValue javaResult = tryJavaGet(L, ci, pc, t, LuaInteger.valueOf(C));
        if (javaResult != null) {
            stack = L.stack;
            stack[ra] = javaResult;
            return stack;
        }
        ci.savedpc = pc;
        L.top = ci.top;
        LuaValue res = LuaIndex.finishGetiFromVM(t, C, L, ra, base + B, isTable);
        stack = L.stack;
        stack[ra] = res;
        return stack;
    }

    // lvm.c: OP_GETFIELD  -  luaV_fastget 内联 luaH_getshortstr，否则 luaV_finishget_fromVM。
    // 慢路径可能触发元方法 realloc，故返回刷新后的 stack。
    private static LuaValue[] opGetfield(LuaThread L, CallInfo ci, int pc, LuaValue[] stack, int ra,
                                         int base, int B, LuaString ks) {
        LuaValue t = stack[base + B];
        LuaTable table = t instanceof LuaTable tbl ? tbl : null;
        boolean isTable = table != null;
        if (isTable) {
            LuaValue v = table.fastGetShortStr(ks);
            if (v != null) {
                stack[ra] = v;
                return stack;
            }
        }
        LuaValue javaResult = tryJavaGet(L, ci, pc, t, ks);
        if (javaResult != null) {
            stack = L.stack;
            stack[ra] = javaResult;
            return stack;
        }
        ci.savedpc = pc;
        L.top = ci.top;
        LuaValue res = LuaIndex.finishGetFromVM(t, ks, L, ra, base + B, isTable);
        stack = L.stack;
        stack[ra] = res;
        return stack;
    }

    // lvm.c: OP_GETTABUP  -  luaV_fastget 内联 luaH_getshortstr，否则 luaV_finishget。
    // t 取自 upvalue，slot 传入 -(B+2)（对齐 C 的 upvalue 引用）。慢路径可能 realloc，返回刷新后的 stack。
    private static LuaValue[] opGettabup(LuaThread L, CallInfo ci, int pc, LuaValue[] stack, int ra,
                                         LuaValue t, LuaString ks, int slot) {
        LuaTable table = t instanceof LuaTable tbl ? tbl : null;
        boolean isTable = table != null;
        if (isTable) {
            LuaValue v = table.fastGetShortStr(ks);
            if (v != null) {
                stack[ra] = v;
                return stack;
            }
        }
        LuaValue javaResult = tryJavaGet(L, ci, pc, t, ks);
        if (javaResult != null) {
            stack = L.stack;
            stack[ra] = javaResult;
            return stack;
        }
        ci.savedpc = pc;
        L.top = ci.top;
        LuaValue res = LuaIndex.finishGetFromVM(t, ks, L, ra, slot, isTable);
        stack = L.stack;
        stack[ra] = res;
        return stack;
    }

    // lvm.c: OP_SETTABUP/OP_SETFIELD  -  luaV_fastset 短字符串快速路径，
    // 否则 finishset。共享方法：SETTABUP 传 upvalue 与 slot=-(A+2)，SETFIELD 传 R[A] 与 slot=ra。
    // 慢路径 finishset 可能触发元方法 realloc，故返回刷新后的 stack。
    private static LuaValue[] opSetShortStr(LuaThread L, CallInfo ci, int pc, LuaValue[] stack,
                                            LuaValue t, LuaString ks, LuaValue val, int slot) {
        if (t instanceof LuaTable table) {
            table.bindGlobals(L.l_G);
            LuaTable.bindValue(L.l_G, val);
            int hres = table.fastSetShortStr(ks, val);
            if (hres == LuaTable.HOK) {
                LuaGC.barrierback(L.l_G, table, val);
                return stack;
            }
            ci.savedpc = pc;
            L.top = ci.top;
            LuaIndex.finishSet(t, ks, val, L, slot, hres);
            stack = L.stack;
        } else {
            if (tryJavaSet(L, ci, pc, t, ks, val)) return L.stack;
            ci.savedpc = pc;
            L.top = ci.top;
            LuaIndex.finishSet(t, ks, val, L, slot);
            stack = L.stack;
        }
        return stack;
    }

    // lvm.c: OP_SETTABLE  -  luaV_fastset/luaV_fastseti 内联，否则 luaV_finishset。
    // 慢路径 finishset 可能触发元方法 realloc，故返回刷新后的 stack。
    private static LuaValue[] opSettable(LuaThread L, CallInfo ci, int pc, LuaValue[] stack, int ra,
                                         int base, int B, int C, int kBit, LuaValue[] k) {
        LuaValue t = stack[ra];
        LuaValue key = stack[base + B];
        if (key == null) key = LuaValue.NIL;
        LuaValue val = kBit != 0 ? k[C] : stack[base + C];
        if (val == null) val = LuaValue.NIL;
        if (t instanceof LuaTable table) {
            table.bindGlobals(L.l_G);
            LuaTable.bindValue(L.l_G, key);
            LuaTable.bindValue(L.l_G, val);
            int hres;
            if (key instanceof LuaInteger ki) {
                long kl = ki.tolong();
                if (kl >= 1 && kl <= Integer.MAX_VALUE) {
                    int u = (int) kl - 1;
                    if (table.array_tags != null && u < table.array_tags.length) {
                        LuaValue mt = table.metatable;
                        if (mt == null || !(mt instanceof LuaTable mtt) || (mtt.flags & LuaTable.MASK_NEWINDEX) != 0 || table.array_tags[u] != 0) {
                            if (val.isnil()) {
                                LuaTable.clearArrSlot(table.array_tags, table.array_numVals, table.array_refs, u);
                                if ((int) kl == table.lenhint) {
                                    while (table.lenhint > 0 && table.array_tags[table.lenhint - 1] == 0)
                                        table.lenhint--;
                                }
                            } else {
                                LuaTable.fval2arr(table.array_tags, table.array_numVals, table.array_refs, u, val);
                                if ((int) kl > table.lenhint) table.lenhint = (int) kl;
                            }
                            LuaGC.barrierback(L.l_G, table, val);
                            return stack;
                        }
                        hres = ~u;
                    } else {
                        hres = table.fastSeti((int) kl, val);
                    }
                } else if (kl >= Integer.MIN_VALUE && kl <= Integer.MAX_VALUE) {
                    hres = table.fastSeti((int) kl, val);
                } else {
                    hres = table.pset(key, val);
                }
            } else {
                hres = table.pset(key, val);
            }
            if (hres == LuaTable.HOK) {
                LuaGC.barrierback(L.l_G, table, val);
                return stack;
            }
            ci.savedpc = pc;
            L.top = ci.top;
            LuaIndex.finishSet(t, key, val, L, ra, hres);
            stack = L.stack;
        } else {
            if (tryJavaSet(L, ci, pc, t, key, val)) return L.stack;
            ci.savedpc = pc;
            L.top = ci.top;
            LuaIndex.finishSet(t, key, val, L, ra);
            stack = L.stack;
        }
        return stack;
    }

    // lvm.c: OP_SETLIST 批量写入  -  obj2arr(h,last-1,val) + luaC_barrierback
    // java diff: 内联 obj2arr（fval2arr 直写 sidecar 无 barrier）+ 单次 barrierback，与 C 逐位对齐
    // 前提：last <= t.asize()（resizeArray 已扩容），键全命中数组段；setInt 兜底
    private static void opSetlist(LuaThread L, LuaTable t, LuaValue[] stack, int ra, int n, int last) {
        byte[] atags = t.array_tags;
        long[] anums = t.array_numVals;
        Object[] arefs = t.array_refs;
        int hint = t.lenhint;
        for (int i = 1; i <= n; i++) {
            LuaValue val = stack[ra + i];
            int key = last - n + i;
            int u = key - 1;
            // ltable.h: obj2arr(h,k,val): *getArrTag=val->tt_; *getArrVal=val->value_
            if (u >= 0 && atags != null && u < atags.length) {
                if (val.isnil()) {
                    // ltable.h: fval2arr sets tag=LUA_VNIL (tagisempty=true)
                    LuaTable.clearArrSlot(atags, anums, arefs, u);
                    if (key == hint) {
                        while (hint > 0 && atags[hint - 1] == 0) hint--;
                    }
                } else {
                    LuaTable.fval2arr(atags, anums, arefs, u, val);
                    if (key > hint) hint = key;
                }
                // lvm.c: luaC_barrierback(L, obj2gco(h), val)
                LuaGC.barrierback(L.l_G, t, val);
            } else {
                // 哈希段兜底（resize 后正常不触发，仅防御边界意外）
                t.setInt(key, val);
                LuaGC.barrierback(L.l_G, t, val);
                // 表数组可能被 setInt->newKey->rehash 重分配，刷新本地引用
                atags = t.array_tags;
                anums = t.array_numVals;
                arefs = t.array_refs;
            }
        }
        t.lenhint = hint;
    }

    // OP_SETLIST 回退路径（-Dluajvm.setlistopt=false）：setInt 内部 barrier + barrierback 双重检查
    private static void opSetlistOld(LuaThread L, LuaTable t, LuaValue[] stack, int ra, int n, int last) {
        for (int i = 1; i <= n; i++) {
            LuaValue val = stack[ra + i];
            t.setInt(last - n + i, val);
            LuaGC.barrierback(L.l_G, t, val);
        }
    }

    // lvm.c: OP_SETI  -  luaV_fastseti 内联数组/哈希，否则 luaV_finishseti。
    // 慢路径 finishseti 可能触发元方法 realloc，故返回刷新后的 stack。
    private static LuaValue[] opSeti(LuaThread L, CallInfo ci, int pc, LuaValue[] stack, int ra,
                                     int base, int B, int C, int kBit, LuaValue[] k) {
        LuaValue t = stack[ra];
        LuaValue val = kBit != 0 ? k[C] : stack[base + C];
        if (t instanceof LuaTable table) {
            table.bindGlobals(L.l_G);
            LuaTable.bindValue(L.l_G, val);
            int u = B - 1;
            if (u >= 0 && table.array_tags != null && u < table.array_tags.length) {
                LuaValue mt = table.metatable;
                if (mt == null || !(mt instanceof LuaTable mtt) || (mtt.flags & LuaTable.MASK_NEWINDEX) != 0 || table.array_tags[u] != 0) {
                    if (val.isnil()) {
                        LuaTable.clearArrSlot(table.array_tags, table.array_numVals, table.array_refs, u);
                        if (B == table.lenhint) {
                            while (table.lenhint > 0 && table.array_tags[table.lenhint - 1] == 0)
                                table.lenhint--;
                        }
                    } else {
                        LuaTable.fval2arr(table.array_tags, table.array_numVals, table.array_refs, u, val);
                        if (B > table.lenhint) table.lenhint = B;
                    }
                    LuaGC.barrierback(L.l_G, table, val);
                    return stack;
                }
                ci.savedpc = pc;
                L.top = ci.top;
                LuaIndex.finishSeti(t, B, val, L, ra, ~u);
                stack = L.stack;
            } else {
                int hres = table.fastSeti(B, val);
                if (hres == LuaTable.HOK) {
                    LuaGC.barrierback(L.l_G, table, val);
                    return stack;
                }
                ci.savedpc = pc;
                L.top = ci.top;
                LuaIndex.finishSeti(t, B, val, L, ra, hres);
                stack = L.stack;
            }
        } else {
            if (tryJavaSet(L, ci, pc, t, LuaInteger.valueOf(B), val)) return L.stack;
            ci.savedpc = pc;
            L.top = ci.top;
            LuaIndex.finishSeti(t, B, val, L, ra);
            stack = L.stack;
        }
        return stack;
    }

    public record DebugName(String name, String namewhat) {
    }
}
