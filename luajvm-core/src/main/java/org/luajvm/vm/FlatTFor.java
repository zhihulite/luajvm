// java-only: TFOR（泛型 for pairs/ipairs）循环扁平化执行器（无 C 对应）。
// 只保留 TFOR 通道；数值 for / while / 自递归等一律走装箱路径，
// 行为等价由 flatloop_equiv 门禁把关。开关属性名 -Dluajvm.flatloop / -Dluajvm.inlinenext。
//
// 适用两类标准迭代器：
//   next（NextMark）：按 node/数组槽序遍历，键惰性物化；
//   ipairs（IpairsMark）：严格按整数键序走数组段，body 可读索引
//     （A+3 由执行器逐元素供给，plan.needsKey 标记），遇空洞/非整数值交回装箱收尾。
// 任一 guard 失败 => bail 回装箱循环（写回已算部分 + 设 ctrl，execute 从该条目续跑，
//   状态一致零分叉）。
package org.luajvm.vm;

import org.luajvm.compiler.Opcodes;
import org.luajvm.core.IpairsMark;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.NextMark;
import org.luajvm.core.Prototype;
import org.luajvm.core.UpVal;

public final class FlatTFor {
    // 总开关：默认开启，可用 -Dluajvm.flatloop=false 关闭（回退纯装箱路径）。
    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("luajvm.flatloop"));
    // java-only: 标准 next 迭代器内联快路径（LuaVM OP_TFORCALL 直调 nextEntryOnStack，
    //   跳过 precallC/callOnStack/poscall 调用机制）。独立于上面的整循环扁平化。
    public static final boolean INLINE_NEXT = !"false".equalsIgnoreCase(System.getProperty("luajvm.inlinenext"));

    private static final int MAX_BODY = 64;
    private static final int MAX_REG = 256;
    // ipairs 扁平化启用的最小表长：低于此值固定开销大于收益（见 tryRunTFor 内注释）
    private static final int MIN_IPAIRS_LEN = 8;

    private static TForPlan getTForPlan(Prototype proto, int tforprepPc) {
        Object[] cache = proto.tforPlans;
        if (cache == null || tforprepPc >= cache.length) return null;
        return (TForPlan) cache[tforprepPc];
    }

    // ============================================================================
    // TFOR（pairs）循环扁平化  -  把 for k,v in pairs(t) do <body> end 的整循环提取到 JVM 局部变量，
    // 消除 TFORCALL/TFORLOOP/body 的逐指令 dispatch；零装箱：值直读 array_numVals/node.value_num。
    // 适用条件与 bail 行为见文件头注释。
    // ============================================================================

    private static void putTForPlan(Prototype proto, int tforprepPc, TForPlan plan) {
        Object[] cache = proto.tforPlans;
        if (cache == null || tforprepPc >= cache.length) {
            int newLen = Math.max(tforprepPc + 1, proto.code.length);
            Object[] grown = new Object[newLen];
            if (cache != null) System.arraycopy(cache, 0, grown, 0, cache.length);
            proto.tforPlans = grown;
            cache = grown;
        }
        cache[tforprepPc] = plan;
    }

    // tryRunTFor: TFORPREP 入口，尝试扁平化整段 pairs 循环。
    // 调用点：LuaVM OP_TFORPREP，setup 完成后、pc+=sBx 之前。
    // 参数：tforprepPc = TFORPREP 位置（pc-1）；tforcallPc = TFORCALL 位置（pc+sBx）。
    // 返回 true=整循环跑完（调用方 pc+=sBx+2 跳过 TFORCALL+TFORLOOP）；false=未命中或 bail
    //   （调用方 pc+=sBx 走正常 TFORCALL 路径）。
    public static boolean tryRunTFor(LuaThread L, LuaClosure cl, int tforprepPc,
                                     int tforcallPc, int base, int ra, int[] code, LuaValue[] k) {
        if (!ENABLED) return false;


        int A = ra - base;
        int valueReg = A + 4;  // TFOR 值槽（ra+4）

        Prototype proto = cl.p;
        TForPlan plan = getTForPlan(proto, tforprepPc);
        if (plan == null) {
            plan = analyzeTFor(proto, tforprepPc, tforcallPc, A, code, k);
            putTForPlan(proto, tforprepPc, plan);
        }
        if (plan == TFOR_REJECTED) return false;

        // -- 运行时 guard：迭代器身份分派 --
        //   NextMark = 标准 next（键序未指定，node/数组槽扫描）；IpairsMark = 标准
        //   ipairs 迭代器（严格整数键序）。两者扁平执行语义不同，body 白名单相同。
        LuaValue[] stack = L.stack;
        final boolean ipairsMode;
        if (stack[ra] instanceof NextMark) {
            ipairsMode = false;
            if (plan.needsKey) return false;   // body 读键槽：仅 ipairs 模式可供给
        } else if (stack[ra] instanceof IpairsMark) {
            ipairsMode = true;
        } else {
            return false;
        }
        LuaValue state = stack[ra + 1];
        if (!(state instanceof LuaTable t) || t.metatable != null) return false;

        // 开放 upvalue 别名检查：openupval 里的槽若落在寄存器窗口内则拒收扁平化
        if (L.openupval != null && !L.openupval.isEmpty()) {
            for (UpVal uv : L.openupval) {
                if (uv.upisopen()) {
                    int s = uv.slot();
                    if (s >= base + plan.minReg && s <= base + plan.maxReg) return false;
                }
            }
        }

        // -- 载入 live-in 整数寄存器 --
        long[] R = new long[plan.maxReg + 1];
        int[] liveIn = plan.liveInRegs;
        for (int i = 0; i < liveIn.length; i++) {
            int r = liveIn[i];
            LuaValue v = stack[base + r];
            if (v.tt_ != LuaValue.LUA_VNUMINT) return false;
            R[r] = ((LuaInteger) v).v;
        }

        // -- 迭代表内指令预取 --
        int[] bodyOps = plan.bodyOps;
        int[] bodyA = plan.bodyA;
        int[] bodyB = plan.bodyB;
        long[] bodyKv = plan.bodyKv;
        int nbody = bodyOps.length;

        // -- ipairs 模式：严格整数键序走数组段，零装箱（键仅 bail 时物化）--
        // 语义对齐 lbaselib.c ipairsaux（lua_geti）：t[i] 缺失即停；带 __index 的表已被 metatable guard 拒收。
        // 空洞/非整数值/数组段耗尽一律交回装箱路径判定（哈希段可能还持更大整键的稀疏表），每轮只多付一次装箱调用。
        if (ipairsMode) {
            if (!(stack[ra + 3] instanceof LuaInteger startCtrl)) return false;
            // 最小长度门槛：扁平化的固定开销（R[] 分配 + 收尾 bail 的写回与一次装箱
            //   调用）只在元素足够多时才赚得回；官方套件大量小表高频 ipairs，
            //   小表直接走装箱更划算。
            byte[] arrTags = t.array_tags;
            if (t.lenhint < MIN_IPAIRS_LEN) return false;
            long i = startCtrl.v;              // 上一个已交付的索引（初始为 0）
            long asize = arrTags != null ? arrTags.length : 0;
            while (i < asize) {
                int u = (int) i;               // 槽下标 = 键 - 1
                byte tag = arrTags[u];
                if (tag == FlatArith.T_INT) {
                    R[A + 3] = i + 1;          // 键槽：body 读 i 时由此供给
                    R[valueReg] = t.array_numVals[u];
                    for (int j = 0; j < nbody; j++) {
                        if (!execTForOp(bodyOps[j], bodyA[j], bodyB[j], bodyKv[j], R)) {
                            writeBackTFor(stack, base, plan, R);
                            stack[ra + 3] = LuaInteger.valueOf(i);
                            return false;
                        }
                    }
                    i++;
                } else {
                    // 空洞（键缺失 ⇒ 迭代应结束）或非整数值：交回装箱精确判定。
                    // 装箱 TFORCALL 以 ctrl=i 调 lua_geti(i+1)：空洞得 nil 正确终止，
                    // 非整数值得真实值继续 —— 两种语义都由装箱路径处理，状态一致。
                    writeBackTFor(stack, base, plan, R);
                    stack[ra + 3] = (i > 0) ? LuaInteger.valueOf(i) : LuaValue.ZERO;
                    return false;
                }
            }
            // 数组段耗尽：稀疏表的更大整键可能在哈希段 => 交回装箱判定
            writeBackTFor(stack, base, plan, R);
            stack[ra + 3] = (i > 0) ? LuaInteger.valueOf(i) : LuaValue.ZERO;
            return false;
        }

        LuaValue idx = null;  // 当前 key（null = 起始 = nil）

        // -- 数组段 --
        // 对齐 nextEntryOnStack 的 nArray 逻辑：线性扫描 array_tags，跳过空槽(0)，上界
        // min(lenhint, asize)。整数槽(T_INT)直读 array_numVals[u]，零装箱。
        byte[] arrTags = t.array_tags;
        if (arrTags != null) {
            long[] arrVals = t.array_numVals;
            int limit = Math.min(t.lenhint, arrTags.length);
            for (int i = 0; i < limit; i++) {
                byte tag = arrTags[i];
                if (tag == 0) continue;  // 空槽
                if (tag != FlatArith.T_INT) {
                    // 非整数值 ⇒ 写回 + 设 ctrl=idx ⇒ bail
                    writeBackTFor(stack, base, plan, R);
                    stack[ra + 3] = (idx != null) ? idx : LuaValue.NIL;
                    return false;
                }
                R[valueReg] = arrVals[i];
                // -- 执行 body --
                for (int j = 0; j < nbody; j++) {
                    if (!execTForOp(bodyOps[j], bodyA[j], bodyB[j], bodyKv[j], R)) {
                        writeBackTFor(stack, base, plan, R);
                        stack[ra + 3] = (idx != null) ? idx : LuaValue.NIL;
                        return false;
                    }
                }
                idx = LuaInteger.valueOf(i + 1);  // 更新 key（数组键 = 索引+1）
            }
        }

        // -- 哈希段 --
        // 对齐 nHashOnStack：线性扫描 node[]，跳过空槽(T_NIL/T_NILVAL)，整数槽直读 value_num
        LuaTable.Node[] nodes = t.node;
        if (nodes != null && nodes.length > 0) {
            for (int i = 0; i < nodes.length; i++) {
                LuaTable.Node n = nodes[i];
                byte tag = n.value_tag;
                if (tag == FlatArith.T_NIL || tag == LuaTable.T_NILVAL) continue;
                if (tag != FlatArith.T_INT) {
                    writeBackTFor(stack, base, plan, R);
                    stack[ra + 3] = (idx != null) ? idx : LuaValue.NIL;
                    return false;
                }
                R[valueReg] = n.value_num;
                for (int j = 0; j < nbody; j++) {
                    if (!execTForOp(bodyOps[j], bodyA[j], bodyB[j], bodyKv[j], R)) {
                        writeBackTFor(stack, base, plan, R);
                        stack[ra + 3] = (idx != null) ? idx : LuaValue.NIL;
                        return false;
                    }
                }
                idx = n.key;  // 更新 key（哈希键 = node.key）
            }
        }

        // -- 循环完成：写回所有寄存器 + 设 ctrl=NIL（标志循环结束）--
        writeBackTFor(stack, base, plan, R);
        stack[ra + 3] = LuaValue.NIL;
        return true;
    }

    /**
     * java-only: 标准 {@code next} 迭代器的逐次内联（TFORCALL 未走整循环扁平化时的快路径，
     * 即 bail 续跑 / 非扁平表形态）。语义同 lbaselib.c next：取 t[ctrl] 的下一对 (k,v)
     * 直写 stack[ra+3]/stack[ra+4]；无下一对时 k 槽写 NIL，TFORLOOP 检查 ra+3 退出。
     *
     * <p>放本文件而非 LuaVM.execute 内联展开：execute 方法体须守 C2 HugeMethodLimit
     * （8000 字节）；小而热的静态方法由 C2 自动内联，零额外开销。
     *
     * @return true 已内联处理；false 调用方走通用调用路径
     */
    public static boolean inlineNext(LuaValue func, LuaValue state, LuaValue[] stack, int ra) {
        if (!(func instanceof NextMark) || !(state instanceof LuaTable tbl)) return false;
        LuaValue ctrl = stack[ra + 3];
        int nr = tbl.nextEntryOnStack(ctrl, stack, ra + 3);
        if (nr == 0) stack[ra + 3] = LuaValue.NIL;
        return true;
    }

    // execTForOp: 执行单条 body 指令（紧凑格式）。返回 false=不可处理（应 bail）。
    private static boolean execTForOp(int op, int a, int b, long kv, long[] R) {
        switch (op) {
            case Opcodes.OP_MOVE -> R[a] = R[b];
            case Opcodes.OP_LOADI -> R[a] = kv;
            case Opcodes.OP_ADD -> R[a] = R[b] + R[(int) kv];
            case Opcodes.OP_SUB -> R[a] = R[b] - R[(int) kv];
            case Opcodes.OP_MUL -> R[a] = R[b] * R[(int) kv];
            case Opcodes.OP_ADDI -> R[a] = R[b] + kv;
            case Opcodes.OP_ADDK -> R[a] = R[b] + kv;
            case Opcodes.OP_SUBK -> R[a] = R[b] - kv;
            case Opcodes.OP_MULK -> R[a] = R[b] * kv;
            // 除零返回 false 交回装箱路径：裸 ArithmeticException 会以非 LuaError
            // 形态穿透 pcall，错误消息也与 C 不一致（'n%0' / 'divide by zero'）
            case Opcodes.OP_MOD -> {
                long d = R[(int) kv];
                if (d == 0) return false;
                R[a] = intMod(R[b], d);
            }
            case Opcodes.OP_IDIV -> {
                long d = R[(int) kv];
                if (d == 0) return false;
                R[a] = intIDiv(R[b], d);
            }
            case Opcodes.OP_MODK -> {
                if (kv == 0) return false;
                R[a] = intMod(R[b], kv);
            }
            case Opcodes.OP_IDIVK -> {
                if (kv == 0) return false;
                R[a] = intIDiv(R[b], kv);
            }
            case Opcodes.OP_BAND -> R[a] = R[b] & R[(int) kv];
            case Opcodes.OP_BOR -> R[a] = R[b] | R[(int) kv];
            case Opcodes.OP_BXOR -> R[a] = R[b] ^ R[(int) kv];
            case Opcodes.OP_BANDK -> R[a] = R[b] & kv;
            case Opcodes.OP_BORK -> R[a] = R[b] | kv;
            case Opcodes.OP_BXORK -> R[a] = R[b] ^ kv;
            case Opcodes.OP_SHL -> R[a] = shiftL(R[b], R[(int) kv]);
            case Opcodes.OP_SHR -> R[a] = shiftL(R[b], -R[(int) kv]);
            case Opcodes.OP_SHRI -> R[a] = shiftL(R[b], -kv);
            case Opcodes.OP_SHLI -> R[a] = shiftL(kv, R[b]);
            case Opcodes.OP_UNM -> R[a] = 0 - R[b];
            case Opcodes.OP_BNOT -> R[a] = ~R[b];
            // MMBIN* 是算术元方法回退标记，纯整数路径下恒死指令（pc++ 跳过）⇒ no-op
            case Opcodes.OP_MMBIN, Opcodes.OP_MMBINI, Opcodes.OP_MMBINK -> {
            }
            default -> {
                return false;
            }  // 未覆盖 opcode ⇒ bail
        }
        return true;
    }

    // writeBackTFor: 把 R[] 中的寄存器写回栈（对齐装箱路径退出状态）
    private static void writeBackTFor(LuaValue[] stack, int base, TForPlan plan, long[] R) {
        int[] wb = plan.writeBackRegs;
        for (int i = 0; i < wb.length; i++) {
            int r = wb[i];
            stack[base + r] = LuaInteger.valueOf(R[r]);
        }
    }

    // -- analyzeTFor: 静态分析 TFOR body --
    // body 范围 [tforprepPc+1, tforcallPc)。白名单：纯整数直线算术 + MMBIN* 剥离。
    // 约束：body 不得读 A+3（key 槽） - 仅支持用 A+4（value 槽）的归约体；A+4 由迭代器写
    //   （非 live-in），body 读它合法
    private static TForPlan analyzeTFor(Prototype proto, int tforprepPc, int tforcallPc,
                                        int A, int[] code, LuaValue[] k) {
        int bodyStart = tforprepPc + 1;
        int bodyLen = tforcallPc - bodyStart;
        if (bodyLen <= 0 || bodyLen > MAX_BODY) return TFOR_REJECTED;
        if (A + 4 >= MAX_REG) return TFOR_REJECTED;
        int valueReg = A + 4;

        boolean[] liveIn = new boolean[MAX_REG];
        boolean[] regWrite = new boolean[MAX_REG];
        boolean[] written = new boolean[MAX_REG];
        boolean needsKey = false;         // body 读键槽 A+3（ipairs 模式才可供给）
        int minReg = A, maxReg = A + 4;  // 窗口覆盖到值槽 A+4

        int[] ops = new int[bodyLen];
        int[] opA = new int[bodyLen];
        int[] opB = new int[bodyLen];
        long[] opKv = new long[bodyLen];
        int ni = 0;

        for (int p = bodyStart; p < tforcallPc; p++) {
            int inst = code[p];
            int op = inst & 0x7F;
            int a = (inst >>> 7) & 0xFF;
            int b = (inst >>> 16) & 0xFF;
            int c = (inst >>> 24) & 0xFF;
            int kBit = (inst >>> 15) & 1;
            switch (op) {
                case Opcodes.OP_MMBIN, Opcodes.OP_MMBINI, Opcodes.OP_MMBINK -> {
                    // 元方法回退标记：纯整数路径下是死指令，从紧凑体剥离
                }
                case Opcodes.OP_MOVE -> {
                    if (a >= MAX_REG || b >= MAX_REG) return TFOR_REJECTED;
                    // 读键槽 A+3：ipairs 模式可供给（needsKey）；pairs 模式运行时拒收
                    if (b == A + 3) {
                        needsKey = true;
                    } else if (!written[b] && !regWrite[b]) {
                        liveIn[b] = true;
                    }
                    regWrite[a] = true;
                    written[a] = true;
                    ops[ni] = op;
                    opA[ni] = a;
                    opB[ni] = b;
                    ni++;
                    if (a < minReg) minReg = a;
                    if (a > maxReg) maxReg = a;
                    if (b < minReg) minReg = b;
                    if (b > maxReg) maxReg = b;
                }
                case Opcodes.OP_LOADI -> {
                    if (a >= MAX_REG) return TFOR_REJECTED;
                    regWrite[a] = true;
                    written[a] = true;
                    ops[ni] = op;
                    opA[ni] = a;
                    opKv[ni] = ((inst >>> 15) & 0x1FFFF) - 0xFFFF;
                    ni++;
                    if (a < minReg) minReg = a;
                    if (a > maxReg) maxReg = a;
                }
                case Opcodes.OP_ADD, Opcodes.OP_SUB, Opcodes.OP_MUL,
                     Opcodes.OP_BAND, Opcodes.OP_BOR, Opcodes.OP_BXOR,
                     Opcodes.OP_SHL, Opcodes.OP_SHR, Opcodes.OP_MOD, Opcodes.OP_IDIV -> {
                    if (a >= MAX_REG || b >= MAX_REG || c >= MAX_REG) return TFOR_REJECTED;
                    // 操作数 b（键槽 A+3 读 => needsKey）
                    boolean readsKey = false;
                    if (b == A + 3) {
                        readsKey = true;
                    } else if (!written[b] && !regWrite[b]) {
                        liveIn[b] = true;
                    }
                    // 操作数 c（kBit==0 时是寄存器，kBit==1 时是常量）
                    if (kBit == 0) {
                        if (c == valueReg) {
                            // 值槽由迭代器写，非 live-in（不标记）
                        } else if (c == A + 3) {
                            readsKey = true;
                        } else if (!written[c] && !regWrite[c]) {
                            liveIn[c] = true;
                        }
                        ops[ni] = op;
                        opA[ni] = a;
                        opB[ni] = b;
                        opKv[ni] = c;
                        ni++;
                    } else {
                        // 常量操作数：须为整数
                        if (c >= k.length) return TFOR_REJECTED;
                        LuaValue kv = k[c];
                        if (kv.tt_ != LuaValue.LUA_VNUMINT) return TFOR_REJECTED;
                        ops[ni] = op;
                        opA[ni] = a;
                        opB[ni] = b;
                        opKv[ni] = ((LuaInteger) kv).v;
                        ni++;
                    }
                    if (readsKey) needsKey = true;
                    regWrite[a] = true;
                    written[a] = true;
                    if (a < minReg) minReg = a;
                    if (a > maxReg) maxReg = a;
                    if (b < minReg) minReg = b;
                    if (b > maxReg) maxReg = b;
                    if (kBit == 0 && c < minReg) minReg = c;
                    if (kBit == 0 && c > maxReg) maxReg = c;
                }
                case Opcodes.OP_ADDI -> {
                    if (a >= MAX_REG || b >= MAX_REG) return TFOR_REJECTED;
                    if (b == A + 3) {
                        needsKey = true;
                    } else if (!written[b] && !regWrite[b]) {
                        liveIn[b] = true;
                    }
                    int sC = c - 127;
                    ops[ni] = op;
                    opA[ni] = a;
                    opB[ni] = b;
                    opKv[ni] = sC;
                    ni++;
                    regWrite[a] = true;
                    written[a] = true;
                    if (a < minReg) minReg = a;
                    if (a > maxReg) maxReg = a;
                    if (b < minReg) minReg = b;
                    if (b > maxReg) maxReg = b;
                }
                case Opcodes.OP_ADDK, Opcodes.OP_SUBK, Opcodes.OP_MULK,
                     Opcodes.OP_MODK, Opcodes.OP_IDIVK,
                     Opcodes.OP_BANDK, Opcodes.OP_BORK, Opcodes.OP_BXORK -> {
                    if (a >= MAX_REG || b >= MAX_REG) return TFOR_REJECTED;
                    if (b == A + 3) return TFOR_REJECTED;
                    if (c >= k.length) return TFOR_REJECTED;
                    LuaValue kv = k[c];
                    if (kv.tt_ != LuaValue.LUA_VNUMINT) return TFOR_REJECTED;
                    if (!written[b] && !regWrite[b]) liveIn[b] = true;
                    ops[ni] = op;
                    opA[ni] = a;
                    opB[ni] = b;
                    opKv[ni] = ((LuaInteger) kv).v;
                    ni++;
                    regWrite[a] = true;
                    written[a] = true;
                    if (a < minReg) minReg = a;
                    if (a > maxReg) maxReg = a;
                    if (b < minReg) minReg = b;
                    if (b > maxReg) maxReg = b;
                }
                case Opcodes.OP_SHRI, Opcodes.OP_SHLI -> {
                    if (a >= MAX_REG || b >= MAX_REG) return TFOR_REJECTED;
                    if (b == A + 3) {
                        needsKey = true;
                    } else if (!written[b] && !regWrite[b]) {
                        liveIn[b] = true;
                    }
                    int sC = c - 127;
                    ops[ni] = op;
                    opA[ni] = a;
                    opB[ni] = b;
                    opKv[ni] = sC;
                    ni++;
                    regWrite[a] = true;
                    written[a] = true;
                    if (a < minReg) minReg = a;
                    if (a > maxReg) maxReg = a;
                    if (b < minReg) minReg = b;
                    if (b > maxReg) maxReg = b;
                }
                case Opcodes.OP_UNM, Opcodes.OP_BNOT -> {
                    if (a >= MAX_REG || b >= MAX_REG) return TFOR_REJECTED;
                    if (b == A + 3) {
                        needsKey = true;
                    } else if (!written[b] && !regWrite[b]) {
                        liveIn[b] = true;
                    }
                    ops[ni] = op;
                    opA[ni] = a;
                    opB[ni] = b;
                    ni++;
                    regWrite[a] = true;
                    written[a] = true;
                    if (a < minReg) minReg = a;
                    if (a > maxReg) maxReg = a;
                    if (b < minReg) minReg = b;
                    if (b > maxReg) maxReg = b;
                }
                default -> {
                    // 任何非白名单 opcode（GETTABLE/CALL/LOADF/JMP/...）⇒ 拒绝
                    return TFOR_REJECTED;
                }
            }
        }
        if (ni == 0) return TFOR_REJECTED;  // 空体无意义

        TForPlan plan = new TForPlan();
        plan.A = A;
        plan.minReg = minReg;
        plan.maxReg = maxReg;
        plan.needsKey = needsKey;
        plan.liveInRegs = compact(liveIn, minReg, maxReg);
        plan.writeBackRegs = compact(regWrite, minReg, maxReg);
        // 复制紧凑指令
        plan.bodyOps = new int[ni];
        plan.bodyA = new int[ni];
        plan.bodyB = new int[ni];
        plan.bodyKv = new long[ni];
        System.arraycopy(ops, 0, plan.bodyOps, 0, ni);
        System.arraycopy(opA, 0, plan.bodyA, 0, ni);
        System.arraycopy(opB, 0, plan.bodyB, 0, ni);
        System.arraycopy(opKv, 0, plan.bodyKv, 0, ni);
        return plan;
    }

    private static boolean isK(int op) {
        return false;  // 基础 opcode (ADD/SUB/MUL/...) 的 c 总是寄存器（kBit 区分）
    }

    private static int[] compact(boolean[] mask, int minReg, int maxReg) {
        int n = 0;
        for (int r = minReg; r <= maxReg; r++) if (mask[r]) n++;
        int[] out = new int[n];
        int i = 0;
        for (int r = minReg; r <= maxReg; r++) if (mask[r]) out[i++] = r;
        return out;
    }

    // -- 整数 MOD/IDIV 内联（逐字对齐 LuaArith.intMod/intIDiv）--
    private static long intMod(long a, long b) {
        if (b == -1) return 0;
        long m = a % b;
        if (m != 0 && (m ^ b) < 0) m += b;
        return m;
    }

    private static long intIDiv(long a, long b) {
        if (b == -1) return 0 - a;
        long q = a / b;
        if ((a % b != 0) && ((a ^ b) < 0)) q--;
        return q;
    }

    // -- lvm.c: luaV_shiftl 逐行复刻（NBITS=64）--
    // 关键：Java 的 << / >> 把位移量隐式 & 63（`1L << 64` 得 1、`x >> 64` 得 x），
    // C 在 |y| >= 64 时明确返回 0 —— 须显式判断，否则语义分叉；
    // 右移是逻辑右移（Lua 位运算把整数当无符号位串），用 >>>。
    private static long shiftL(long x, long y) {
        if (y < 0) {
            if (y <= -64) return 0;
            return x >>> (int) (-y);
        } else {
            if (y >= 64) return 0;
            return x << (int) y;
        }
    }

    // TFOR_REJECTED 哨兵：analyzeTFor 已判定该站点永不可扁平化（同样缓存，免重复扫描）。
    private static final TForPlan TFOR_REJECTED = new TForPlan();

    static final class TForPlan {
        int A;               // TFOR 寄存器基（ra = base + A）
        int minReg, maxReg;  // 寄存器窗口（含 A+4 值槽）
        int[] liveInRegs;    // 进入时须为整数的寄存器（不含 A+4，它由迭代器写）
        int[] writeBackRegs; // 出口须写回的寄存器
        // body 读键槽 A+3（如 for i,v in ipairs(t) do s=s+i*v end）：ipairs 模式下由
        //   执行器逐元素供给 R[A+3]，pairs 模式无法供给 => 运行时拒收该计划
        boolean needsKey;
        // 紧凑体指令（剥离 MMBIN*，整数常量解引用为 long）
        int[] bodyOps;
        int[] bodyA;
        int[] bodyB;         // b 寄存器或立即数
        long[] bodyKv;       // 常量值 / c 寄存器号（ADD/SUB/MUL）
    }

}
