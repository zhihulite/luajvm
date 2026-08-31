// ref: ldebug.c + lobject.c (luaO_chunkid)
// diff: NameWhat 类替代 C 的 const char **name 输出参数；FindLocal 替代 luaG_findlocal 的
//   (name, StkId*) 双输出；Prototype/CallInfo 直读替代 lua_Debug 填充。
// java-only 架构说明：本类是 ldebug.c 的调试信息与名字解析族（luaG_findlocal /
//   getfuncname / funcnamefromcode / getobjname / varinfo / luaG_getfuncline），
//   属**引擎自身**、与标准库无关；ldblib.c 对应的 Lua 侧函数
//   （debug.getinfo/getlocal/setlocal/getregistry）仍留在 lib/DebugInfo。
package org.luajvm.core;

import org.luajvm.compiler.Opcodes;

public final class LuaDebug {
    // lobject.c: LUA_IDSIZE  -  chunkid 输出最多 LUA_IDSIZE-1 = 59 字符（C 保留 1 字节给结尾 NUL）
    private static final int LUA_IDSIZE = 60;
    private static final int MAX_SRC = LUA_IDSIZE - 1;  // 59
    // lopcodes.h: ABSLINEINFO
    private static final int ABSLINEINFO = -0x80;
    // ltm.c: tmname[tm] + 2 -> 跳过 "__" 前缀
    private static final String[] TM_NAMES = {
            "index", "newindex", "gc", "mode", "len", "eq",
            "add", "sub", "mul", "mod", "pow", "div", "idiv",
            "band", "bor", "bxor", "shl", "shr",
            "unm", "bnot", "lt", "le", "concat", "call", "close"
    };

    private LuaDebug() {
    }

    // lobject.c: luaO_chunkid
    public static String chunkid(String source) {
        if (source == null) return "?";
        if (source.isEmpty()) return "[string \"\"]";
        if (source.charAt(0) == '=') {
            String s = source.substring(1);
            // C：if (srclen <= bufflen) memcpy(out, source+1, srclen)  -  srclen 含 '='，
            // C 从 source+1 复制 srclen 字节（含 '\0'），有效输出长度 = s.length()；
            // 截断时 bufflen-1 = 59 字符。
            if (s.length() <= MAX_SRC) return s;
            return s.substring(0, MAX_SRC);
        } else if (source.charAt(0) == '@') {
            String s = source.substring(1);
            // C：if (srclen <= bufflen) memcpy(out, source+1, srclen)
            // 截断时："..." + 最后 (bufflen-3) 字符
            if (s.length() <= MAX_SRC) return s;
            return "..." + s.substring(s.length() - (MAX_SRC - 3));
        } else {
            // C：[string "source"]  -  在第一个换行符处截断，总共最多 bufflen 字符
            int nl = source.indexOf('\n');
            boolean hasNewline = nl >= 0;
            String s = hasNewline ? source.substring(0, nl) : source;
            int maxLen = MAX_SRC - "[string \"...\"]".length();
            if (hasNewline || s.length() > maxLen) {
                if (s.length() > maxLen) s = s.substring(0, maxLen);
                s = s + "...";
            }
            String result = "[string \"" + s + "\"]";
            // 最终安全检查：确保结果 <= MAX_SRC
            if (result.length() > MAX_SRC) result = result.substring(0, MAX_SRC);
            return result;
        }
    }

    // java-only: pc<0（C 函数帧 / 无当前指令）时 C 由调用方保证不查，Java 显式返回 -1
    public static int getFuncLinePub(Prototype p, int pc) {
        if (pc < 0) return -1;
        return getFuncLine(p, pc);
    }

    // ldebug.c: luaG_getfuncline（含 getbaseline 的内联展开）
    public static int getFuncLine(Prototype p, int pc) {
        if (p.lineinfo == null || p.lineinfo.length == 0) return -1;
        // 查找基线：扫 abslineinfo 取满足 pc <= 目标 pc 的最大者
        int basepc = -1;
        int baseline = p.linedefined;  // 默认从 linedefined 开始
        if (p.abslineinfo != null && p.abslineinfo.length >= 2) {
            for (int slot = 0; slot + 1 < p.abslineinfo.length; slot += 2) {
                int absPc = p.abslineinfo[slot];
                if (absPc <= pc) {
                    basepc = absPc;
                    baseline = p.abslineinfo[slot + 1];
                } else {
                    break;
                }
            }
        }
        // 从 basepc+1 遍历到 pc，累加相对增量
        int line = baseline;
        for (int i = basepc + 1; i <= pc; i++) {
            if (i >= 0 && i < p.lineinfo.length) {
                int delta = p.lineinfo[i];
                // delta 存为有符号字节：读出 0-255，0-127 为正，128-255 为负（-128 到 -1）
                if (delta >= 128) delta -= 256;
                // C: lua_assert(f->lineinfo[basepc] != ABSLINEINFO)  -  basepc 是最后一个
                //   绝对标记，区间内不可能再有；release 版 C 无条件累加，此处同
                line += delta;
            }
        }
        return line;
    }


    // ldebug.c: activeLines
    public static LuaTable activeLines(Prototype p) {
        LuaTable t = LuaValue.tableOf();
        if (p == null || p.lineinfo == null || p.lineinfo.length == 0) return t;
        int line = p.linedefined;
        int pc = 0;
        if (p.isVararg()) {
            // C: lua_assert(GET_OPCODE(p->code[0]) == OP_VARARGPREP)  -  跳过首指令
            line = nextLine(p, line, 0);
            pc = 1;
        }
        for (; pc < p.lineinfo.length; pc++) {  // C: for (; i < p->sizelineinfo; i++)
            line = nextLine(p, line, pc);
            t.setEntry(LuaInteger.valueOf(line), LuaValue.TRUE);
        }
        return t;
    }

    // ldebug.c: nextline
    private static int nextLine(Prototype p, int currentline, int pc) {
        int delta = p.lineinfo[pc];
        if (delta == -ABSLINEINFO) {  // 0x80：无符号读出的 ABSLINEINFO
            return getFuncLine(p, pc);
        }
        if (delta >= 128) delta -= 256;
        return currentline + delta;
    }

    // ldebug.c: luaG_findlocal
    public static FindLocal findLocal(LuaThread L, CallInfo ci, int n) {
        int base = ci.func + 1;
        String name = null;
        if (ci.isLua()) {
            LuaClosure cl = CallInfo.ciFunc(L, ci);
            if (cl == null || cl.p == null) return null;
            Prototype p = cl.p;
            if (n < 0) {
                if ((p.flag & Prototype.PF_VAHID) != 0) {
                    int nextra = ci.nextraargs;
                    if (n >= -nextra) {
                        int pos = ci.func - nextra - (n + 1);
                        return new FindLocal("(vararg)", pos);
                    }
                }
                return null;
            }
            int currentpc = CallInfo.currentpc(ci);
            if (currentpc < 0) currentpc = 0;
            name = getLocalName(p, n, currentpc);
        }
        if (name == null) {
            int limit = (ci == L.ci) ? L.top : (ci.next != null ? ci.next.func : ci.top);
            if (n > 0 && limit - base >= n) {
                name = ci.isLua() ? "(temporary)" : "(C temporary)";
            } else {
                return null;
            }
        }
        return new FindLocal(name, base + (n - 1));
    }

    // ldebug.c: luaG_findlocal  -  checkclosemth luaL_error 消息的公开包装
    public static String findLocalName(LuaThread L, CallInfo ci, int n) {
        FindLocal fl = findLocal(L, ci, n);
        return fl != null ? fl.name : null;
    }

    // ldebug.c: findUpvalue
    public static LuaString findUpvalue(LuaClosure c, int up) {
        if (c.p.upvalues != null && up > 0 && up <= c.p.upvalues.length) {
            Prototype.Upvaldesc ud = c.p.upvalues[up - 1];
            return ud != null ? ud.name : null;
        }
        return null;
    }

    // ldebug.c: upvalname
    private static String upvalname(Prototype p, int uv) {
        if (p.upvalues != null && uv >= 0 && uv < p.upvalues.length) {
            Prototype.Upvaldesc ud = p.upvalues[uv];
            return ud != null && ud.name != null ? ud.name.toJavaString() : "?";
        }
        return "?";
    }

    // ldebug.c: getfuncname  -  public API returns NameWhat
    // java diff: 内部用 NameWhat out，从 funcnamefromcall 结果构造 NameWhat；
    // 每次调用仅分配一个 NameWhat（供公开 API 返回）。
    public static NameWhat getfuncname(Globals globals, LuaThread targetThread, int level) {
        if (globals == null) return null;
        if (targetThread == null) targetThread = globals.running;
        if (targetThread == null) return null;

        CallInfo ci = globals.getCallInfoAtLevel(targetThread, level, false);
        if (ci == null || (ci.callstatus & CallInfo.CIST_TAIL) != 0) return null;

        CallInfo callerCi = globals.getCallInfoAtLevel(targetThread, level + 1, false);
        if (callerCi == null) return null;
        NameWhat out = new NameWhat();  // java-only: out for funcnamefromcall
        String what = funcnamefromcall(targetThread, callerCi, out);
        if (what == null) return null;
        return new NameWhat(out.name, what);  // one allocation for public API
    }

    // ldebug.c: funcnamefromcall  -  返回 namewhat String，写 name 到 out
    // java diff: C 用 const char **name，Java 用 NameWhat out，消除 NameWhat 分配。
    // 调用方必须传可复用的 NameWhat out（对齐 C 的 const char *name 局部变量）
    public static String funcnamefromcall(LuaThread L, CallInfo ci, NameWhat out) {
        if ((ci.callstatus & CallInfo.CIST_HOOKED) != 0) {
            out.name = "?";  // ldebug.c:
            return "hook";  // ldebug.c:
        }
        if ((ci.callstatus & CallInfo.CIST_FIN) != 0) {
            out.name = "__gc";  // ldebug.c:
            return "metamethod";  // ldebug.c:
        }
        if (!ci.isLua()) return null;
        LuaClosure cl = CallInfo.ciFunc(L, ci);
        if (cl == null) return null;
        Prototype p = cl.p;
        if (p == null || p.code == null) return null;
        int pc = CallInfo.currentpc(ci);
        if (pc < 0 || pc >= p.code.length) return null;
        return funcnamefromcode(p, pc, out);  // ldebug.c:
    }

    // ldebug.c: instack
    private static int instack(LuaThread L, CallInfo ci, LuaValue o) {
        int base = ci.func + 1;
        for (int pos = 0; base + pos < ci.top && base + pos < L.stack.length; pos++) {
            if (L.stack[base + pos] == o) return pos;
        }
        return -1;
    }

    // ldebug.c: getupvalname  -  返回 "upvalue" 或 null，写 name 到 out
    // java diff: C 用 const char **name 输出参数，Java 用 NameWhat out 参数，
    // 消除 new NameWhat(...) 分配（C 写 *name 并返回静态 "upvalue"）
    private static String getupvalname(CallInfo ci, LuaClosure c, LuaValue o, NameWhat out) {
        if (c == null || c.upvals == null) return null;
        for (int i = 0; i < c.upvals.length; i++) {
            UpVal uv = c.upvals[i];
            if (uv != null && uv.get() == o) {
                out.name = upvalname(c.p, i);
                return "upvalue";
            }
        }
        return null;
    }

    // ldebug.c: formatvarinfo
    public static String formatvarinfo(String kind, String name) {
        if (kind == null || kind.isEmpty()) return "";
        return " (" + kind + " '" + (name != null ? name : "") + "')";
    }

    // ldebug.c: getupvalname (stackSlot variant)
    // C 比较 UpVal.v.p 与错误 TValue*，Java 必须比较同一个共享栈槽（nil/true/false 是共享单例，不能用 LuaValue 引用比）
    // java diff: 返回 "upvalue" 或 null，写 name 到 out（消除 NameWhat 分配）
    private static String getupvalnameAt(LuaThread L, CallInfo ci,
                                         LuaClosure c, int stackSlot, NameWhat out) {
        if (c == null || c.upvals == null || L == null || L.stack == null) return null;
        for (int i = 0; i < c.upvals.length; i++) {
            UpVal uv = c.upvals[i];
            if (uv != null && uv.isOpenAt(L.stack, stackSlot)) {
                out.name = upvalname(c.p, i);
                return "upvalue";
            }
        }
        return null;
    }

    // ldebug.c: varinfo  -  java diff：int stackSlot 模拟 C 的 const TValue *。
    // stackSlot >= 0: 共享栈上的操作数；stackSlot < -1: upvalue 编码，-(stackSlot+2) 是
    // upvalue 索引（OP_GETTABUP/OP_SETTABUP）；stackSlot == -1: 常量/立即数，无变量信息
    // java diff: getupvalnameAt + getobjname 共用单个可复用 NameWhat out
    public static String varinfoAtStack(LuaThread L, int stackSlot) {
        if (L == null || L.ci == null || L.stack == null) return "";
        CallInfo ci = L.ci;
        if (!ci.isLua()) return "";
        LuaClosure c = CallInfo.ciFunc(L, ci);
        if (stackSlot == -1) return "";
        if (stackSlot < -1) {
            int uvIdx = -(stackSlot + 2);
            if (c != null && c.upvals != null && uvIdx >= 0 && uvIdx < c.upvals.length) {
                String name = upvalname(c.p, uvIdx);
                return formatvarinfo("upvalue", name);
            }
            return "";
        }
        if (stackSlot < ci.func + 1 || stackSlot >= ci.top
                || stackSlot >= L.stack.length) return "";

        NameWhat out = new NameWhat();  // java-only: reusable out for both calls
        String what = getupvalnameAt(L, ci, c, stackSlot, out);
        if (what == null && c != null && c.p != null) {
            int reg = stackSlot - (ci.func + 1);
            what = getobjname(c.p, CallInfo.currentpc(ci), reg, out);
        }
        return what != null ? formatvarinfo(what, out.name) : "";
    }

    // ldebug.c: varinfo
    // java diff: getupvalname + getobjname 共用单个可复用 NameWhat out
    // （消除每次调用 2-3 个 NameWhat 分配，对齐 C 的 const char *name 局部变量）
    public static String varinfo(LuaThread L, LuaValue o) {
        if (L == null || L.ci == null || L.stack == null) return "";
        CallInfo ci = L.ci;
        if (ci.isLua()) {
            LuaClosure c = CallInfo.ciFunc(L, ci);
            NameWhat out = new NameWhat();  // java-only: reusable out for both calls
            String what = getupvalname(ci, c, o, out);
            if (what == null) {
                int reg = instack(L, ci, o);
                if (reg >= 0 && c != null && c.p != null) {
                    what = getobjname(c.p, CallInfo.currentpc(ci), reg, out);
                }
            }
            if (what != null) return formatvarinfo(what, out.name);
        }
        return "";
    }

    // ldebug.c: funcnamefromcode  -  返回 namewhat，写 name 到 out
    // java diff: C 用 const char **name，Java 用 NameWhat out，消除 NameWhat 分配。
    private static String funcnamefromcode(Prototype p, int pc, NameWhat out) {
        int inst = p.code[pc];
        int op = Opcodes.GET_OPCODE(inst);
        switch (op) {
            case Opcodes.OP_CALL:
            case Opcodes.OP_TAILCALL:
                return getobjname(p, pc, Opcodes.GETARG_A(inst), out);  // ldebug.c:
            case Opcodes.OP_TFORCALL:
                out.name = "for iterator";  // ldebug.c:
                return "for iterator";  // ldebug.c:
            case Opcodes.OP_SELF:
            case Opcodes.OP_GETTABUP:
            case Opcodes.OP_GETTABLE:
            case Opcodes.OP_GETI:
            case Opcodes.OP_GETFIELD:
                return metamethodName(Opcodes.TM_INDEX, out);  // ldebug.c:
            case Opcodes.OP_SETTABUP:
            case Opcodes.OP_SETTABLE:
            case Opcodes.OP_SETI:
            case Opcodes.OP_SETFIELD:
                return metamethodName(Opcodes.TM_NEWINDEX, out);  // ldebug.c:
            case Opcodes.OP_MMBIN:
            case Opcodes.OP_MMBINI:
            case Opcodes.OP_MMBINK: {
                int tm = Opcodes.GETARG_C(inst);
                return metamethodName(tm, out);  // ldebug.c:
            }
            case Opcodes.OP_UNM:
                return metamethodName(Opcodes.TM_UNM, out);
            case Opcodes.OP_BNOT:
                return metamethodName(Opcodes.TM_BNOT, out);
            case Opcodes.OP_LEN:
                return metamethodName(Opcodes.TM_LEN, out);
            case Opcodes.OP_CONCAT:
                return metamethodName(Opcodes.TM_CONCAT, out);
            case Opcodes.OP_EQ:
                return metamethodName(Opcodes.TM_EQ, out);
            case Opcodes.OP_LT:
            case Opcodes.OP_LTI:
            case Opcodes.OP_GTI:
                return metamethodName(Opcodes.TM_LT, out);
            case Opcodes.OP_LE:
            case Opcodes.OP_LEI:
            case Opcodes.OP_GEI:
                return metamethodName(Opcodes.TM_LE, out);
            case Opcodes.OP_CLOSE:
            case Opcodes.OP_RETURN:
                return metamethodName(Opcodes.TM_CLOSE, out);
            default:
                return null;  // ldebug.c:
        }
    }

    // ldebug.c: *name = getshrstr(G(L)->tmname[tm]) + 2; return "metamethod";
    // java diff: C 用 const char **name，Java 用 NameWhat out，消除 NameWhat 分配。
    // TM_NAMES 已跳过 "__" 前缀（C 做 tmname[tm]+2）。
    private static String metamethodName(int tm, NameWhat out) {
        if (tm >= 0 && tm < TM_NAMES.length) {
            out.name = TM_NAMES[tm];
        } else {
            out.name = "?";
        }
        return "metamethod";
    }

    // ldebug.c: filterpc
    private static int filterpc(int pc, int jmptarget) {
        return pc < jmptarget ? -1 : pc;
    }

    // ldebug.c: findsetreg
    private static int findsetreg(Prototype p, int lastpc, int reg) {
        int setreg = -1;
        int jmptarget = 0;

        if (lastpc >= 0 && lastpc < p.code.length) {
            if (Opcodes.testMMMode(Opcodes.GET_OPCODE(p.code[lastpc]))) lastpc--;
        }
        if (lastpc < 0) return -1;
        for (int pc = 0; pc < lastpc; pc++) {
            int i = p.code[pc];
            int op = Opcodes.GET_OPCODE(i);
            int a = Opcodes.GETARG_A(i);
            boolean change;
            switch (op) {
                case Opcodes.OP_LOADNIL: {
                    int b = Opcodes.GETARG_B(i);
                    change = (a <= reg && reg <= a + b);
                    break;
                }
                case Opcodes.OP_TFORCALL: {
                    change = (reg >= a + 2);
                    break;
                }
                case Opcodes.OP_CALL:
                case Opcodes.OP_TAILCALL: {
                    change = (reg >= a);
                    break;
                }
                case Opcodes.OP_JMP: {
                    int b = Opcodes.GETARG_sJ(i);
                    int dest = pc + 1 + b;
                    if (dest <= lastpc && dest > jmptarget) jmptarget = dest;
                    change = false;
                    break;
                }
                default:
                    change = Opcodes.testAMode(op) && reg == a;
                    break;
            }
            if (change) setreg = filterpc(pc, jmptarget);
        }
        return setreg;
    }

    // ldebug.c: kname  -  返回 namewhat（"constant" 或 null），写 name 到 out
    // java diff: C 用 const char **name 输出参数，Java 用 NameWhat out 参数，
    // 消除每次调用 new NameWhat(...) 分配（C 返回静态字符串指针）
    private static String kname(Prototype p, int index, NameWhat out) {
        if (p.k != null && index >= 0 && index < p.k.length) {
            LuaValue kv = p.k[index];
            if (kv instanceof LuaString ls) {
                out.name = ls.toJavaString();
                return "constant";
            }
        }
        out.name = "?";
        return null;  // ldebug.c: C returns NULL for non-string constant
    }

    // ldebug.c: rname  -  把 out.name 设为常量名或 "?"
    // java diff: C 用 const char **name 输出参数，Java 用 NameWhat out 参数，
    // 并用 int[] ppcHolder 建模 C 的 int *ppc 传引用
    private static void rname(Prototype p, int pc, int c, NameWhat out) {
        int[] ppcHolder = {pc};  // java-only: model C's int *ppc
        String what = basicgetobjname(p, ppcHolder, c, out);
        // ldebug.c: if (!(what && *what == 'c'))  -  检查 namewhat 以 'c' 开头（常量）
        if (what == null || what.isEmpty() || what.charAt(0) != 'c') {
            out.name = "?";
        }
    }

    // ldebug.c: basicgetobjname  -  返回 namewhat 字符串，写 name 到 out
    // java diff: C 用 int *ppc 传引用复用 findsetreg 结果，Java 用 int[] ppcHolder
    //   （ppcHolder[0] 为 in/out）建模，消除 getobjname 中重复的 findsetreg 调用
    // java diff: C 用 const char **name + 返回 const char *，Java 用 NameWhat out +
    //   返回 String，消除每次 new NameWhat(...) 分配
    private static String basicgetobjname(Prototype p, int[] ppcHolder, int reg, NameWhat out) {
        int pc = ppcHolder[0];
        String localname = getLocalName(p, reg + 1, pc);
        if (localname != null) {
            out.name = localname;
            return "local";  // ldebug.c: strlocal
        }
        // ldebug.c: *ppc = pc = findsetreg(p, pc, reg)  -  为调用方更新 ppc
        ppcHolder[0] = pc = findsetreg(p, pc, reg);
        if (pc != -1) {
            int i = p.code[pc];
            int op = Opcodes.GET_OPCODE(i);
            switch (op) {
                case Opcodes.OP_MOVE: {
                    int b = Opcodes.GETARG_B(i);
                    if (b < Opcodes.GETARG_A(i)) {
                        return basicgetobjname(p, ppcHolder, b, out);  // ldebug.c: recursive
                    }
                    break;
                }
                case Opcodes.OP_GETUPVAL: {
                    out.name = upvalname(p, Opcodes.GETARG_B(i));
                    return "upvalue";  // ldebug.c: strupval
                }
                case Opcodes.OP_LOADK: {
                    return kname(p, Opcodes.GETARG_Bx(i), out);  // ldebug.c:
                }
                case Opcodes.OP_LOADKX: {
                    if (pc + 1 < p.code.length) {
                        return kname(p, Opcodes.GETARG_Ax(p.code[pc + 1]), out);  // ldebug.c:
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return null;  // ldebug.c: could not find reasonable name
    }

    // ldebug.c: isEnv  -  返回 "global" 或 "field"
    // java diff: C 用 const char **name + int *pc，Java 用 NameWhat out + int[] ppcHolder
    private static String isEnv(Prototype p, int pc, int inst, boolean isup) {
        int t = Opcodes.GETARG_B(inst);
        String name;
        if (isup) {
            name = upvalname(p, t);
        } else {
            NameWhat out = new NameWhat();
            int[] ppcHolder = {pc};  // java-only: model C's int *ppc
            String what = basicgetobjname(p, ppcHolder, t, out);
            name = out.name;
            // ldebug.c: if (what != strlocal && what != strupval) name = NULL;
            if (what == null || (!what.equals("local") && !what.equals("upvalue"))) {
                name = null;
            }
        }
        return (name != null && name.equals("_ENV")) ? "global" : "field";
    }

    // ldebug.c: getobjname  -  返回 namewhat 字符串，写 name 到 out
    // java diff: C 用 const char **name + int *lastpc，Java 用 NameWhat out + int[] ppcHolder；
    // 复用 ppcHolder[0] 消除重复 findsetreg，并消除各分支 new NameWhat(...) 分配
    private static String getobjname(Prototype p, int lastpc, int reg, NameWhat out) {
        int[] ppcHolder = {lastpc};  // java-only: model C's int *lastpc
        String kind = basicgetobjname(p, ppcHolder, reg, out);
        if (kind != null) {
            return kind;  // ldebug.c:
        }
        int pc = ppcHolder[0];  // ldebug.c:  -  reuse findsetreg result (no duplicate call)
        if (pc != -1) {
            int i = p.code[pc];
            int op = Opcodes.GET_OPCODE(i);
            switch (op) {
                case Opcodes.OP_GETTABUP: {
                    int k = Opcodes.GETARG_C(i);
                    kname(p, k, out);  // ldebug.c: writes *name
                    return isEnv(p, pc, i, true);  // ldebug.c:
                }
                case Opcodes.OP_GETTABLE:
                case Opcodes.OP_GETVARG: {  // ldebug.c: 新增 OP_GETVARG
                    // 命名 vararg（...t）字段的只读访问编码为 OP_GETVARG（lcode.c: ；
                    //   finish 仅在 PF_VATAB 时转 OP_GETTABLE） - getobjname 必须同样
                    //   处理才能提取字段名（errors.lua:162 "field 'xx'"）
                    int k = Opcodes.GETARG_C(i);
                    rname(p, pc, k, out);  // ldebug.c: writes *name
                    return isEnv(p, pc, i, false);  // ldebug.c:
                }
                case Opcodes.OP_GETI: {
                    out.name = "integer index";  // ldebug.c:
                    return "field";  // ldebug.c:
                }
                case Opcodes.OP_GETFIELD: {
                    int k = Opcodes.GETARG_C(i);
                    kname(p, k, out);  // ldebug.c: writes *name
                    return isEnv(p, pc, i, false);  // ldebug.c:
                }
                case Opcodes.OP_SELF: {
                    int k = Opcodes.GETARG_C(i);
                    kname(p, k, out);  // ldebug.c: writes *name
                    return "method";  // ldebug.c:
                }
                default:
                    break;
            }
        }
        return null;  // ldebug.c: could not find reasonable name
    }

    // ldebug.c: luaF_getlocalname
    public static String getLocalName(Prototype p, int localNumber, int pc) {
        if (p.locvars == null || localNumber <= 0) return null;
        int active = 0;
        for (int slot = 0; slot < p.locvars.length; slot++) {
            Prototype.LocVar lv = p.locvars[slot];
            if (lv == null) break;
            if (lv.startpc > pc) break;
            if (pc < lv.endpc) {
                active++;
                if (active == localNumber) {
                    return lv.varname != null ? lv.varname.toJavaString() : "?";
                }
            }
        }
        return null;
    }


    // NameWhat
    public static class NameWhat {
        public String name = "";
        public String namewhat = "";

        public NameWhat() {
        }

        public NameWhat(String name, String namewhat) {
            this.name = name != null ? name : "";
            this.namewhat = namewhat != null ? namewhat : "";
        }
    }


    // ldebug.c: luaG_findlocal 的 (name, StkId) 双输出
    // java-only: 供 lib/DebugInfo 的 db_getlocal/db_setlocal 使用，故 public
    public static final class FindLocal {
        public final String name;
        public final int pos;

        FindLocal(String name, int pos) {
            this.name = name;
            this.pos = pos;
        }
    }

}
