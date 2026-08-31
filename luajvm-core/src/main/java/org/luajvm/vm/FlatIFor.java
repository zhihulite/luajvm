// java-only: 数值 for（OP_FORPREP/FORLOOP）整循环扁平化执行器（无 C 对应）。
//
// 把 init/limit/step 全为整数的数值 for 提取到 long 局部变量上跑完整个循环，
// 消除每轮的 FORLOOP dispatch 与计数槽/控制变量装箱；纯计算 body 下把
// 装箱路径的循环机器成本压到接近 C 水平。
//
// 语义对齐 lvm.c forprep/FORLOOP（Lua 5.5 计数制）：
//   布局：FORPREP 时 ra=init、ra+1=limit、ra+2=step；换算后 ra=剩余步数-1、
//   ra+1=step、ra+2=控制变量。FORLOOP 在 count>0 时递减并 ctrl += step 跳回；
//   count==0 落出，控制变量停在最后一个有效值。step==0 报错、零迭代跳过、
//   long 环绕全部交给装箱路径逐字复现；本执行器只在「三值全整数且非零步长且
//   至少一轮」时接管。
// 控制变量是 const（编译器拒绝赋值），隐藏槽无词法访问 ⇒ 循环三元组只由引擎推进。
// body 白名单外的指令（调用/表访问/比较分支/内层循环等）在分析期拒绝并缓存结论。
// 运行中唯一可能中途交回的是 MOD/IDIV 除数为零 —— 写回全部寄存器后从该条指令
// 续跑装箱路径，由其抛出与 C 一致的 'n%%0' 错误。
//
// 计划缓存在 Prototype.iforPlans 上，生命周期与 Prototype 相同；并发首调各自分析、
// 发布互相覆盖，结果幂等。
package org.luajvm.vm;

import org.luajvm.compiler.Opcodes;
import org.luajvm.core.LuaClosure;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Prototype;
import org.luajvm.core.UpVal;

import java.util.ArrayList;
import java.util.Arrays;

public final class FlatIFor {
    // 总开关：默认开启，-Dluajvm.ifor=false 回退装箱路径（同一份 class 切基线）
    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("luajvm.ifor"));

    private static final int MAX_BODY = 64;
    private static final int MAX_REG = 256;

    private static final IForPlan REJECTED = new IForPlan();

    // java-only 诊断：-Dluajvm.iforstats=true 在退出时打印接管/拒绝统计。
    //   用于测量通道在真实负载里接住了多少数值 for。
    static final boolean STATS = Boolean.getBoolean("luajvm.iforstats");
    private static long statTaken;        // 整循环扁平执行完成次数
    private static long statEntryRefused; // 入口条件不满足（类型/零迭代/upval/live-in）
    private static long statRejectedEntry;// 站点已判拒收后的再次进入（分析负缓存命中）
    private static long statMidBail;      // 运行中除零 bail 续跑次数

    static {
        if (STATS) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.printf(
                    "[iforstats] taken=%d entryRefused=%d rejectedEntries=%d midBail=%d%n",
                    statTaken, statEntryRefused, statRejectedEntry, statMidBail)));
        }
    }

    private static IForPlan getPlan(Prototype proto, int pc) {
        Object[] cache = proto.iforPlans;
        if (cache == null || pc >= cache.length) return null;
        return (IForPlan) cache[pc];
    }

    private static void putPlan(Prototype proto, int pc, IForPlan plan) {
        Object[] cache = proto.iforPlans;
        if (cache == null || pc >= cache.length) {
            int newLen = Math.max(pc + 1, proto.code.length);
            Object[] grown = new Object[newLen];
            if (cache != null) System.arraycopy(cache, 0, grown, 0, cache.length);
            proto.iforPlans = grown;
            cache = grown;
        }
        cache[pc] = plan;
    }

    /**
     * 数值 for 扁平化入口。调用点：execute 的 OP_FORPREP，trap==0 且未做任何 setup 前。
     *
     * @param forprepPc OP_FORPREP 的位置（当前 pc-1）
     * @return 已完整执行或已确定续跑点时返回绝对新 pc；未接管返回 -1（调用方原样走
     *         forprep 装箱路径）
     */
    public static int tryRun(LuaThread L, LuaClosure cl, int forprepPc,
                             int base, int ra, int[] code, LuaValue[] k) {
        if (!ENABLED) return -1;

        // lvm.c forprep 的入参布局：ra=init、ra+1=limit、ra+2=step
        LuaValue[] stack = L.stack;
        LuaValue initV = stack[ra];
        LuaValue limitV = stack[ra + 1];
        LuaValue stepV = stack[ra + 2];
        if (initV.tt_ != LuaValue.LUA_VNUMINT || limitV.tt_ != LuaValue.LUA_VNUMINT
                || stepV.tt_ != LuaValue.LUA_VNUMINT) {
            if (STATS) statEntryRefused++;
            return -1;
        }
        long init = ((LuaInteger) initV).v;
        long limit = ((LuaInteger) limitV).v;
        long step = ((LuaInteger) stepV).v;
        // step==0 的报错（含 chunk:line）由装箱路径产生
        if (step == 0) {
            if (STATS) statEntryRefused++;
            return -1;
        }

        // 零迭代同样交回装箱路径跳过
        boolean skip = (step > 0) ? (init > limit) : (init < limit);
        if (skip) {
            if (STATS) statEntryRefused++;
            return -1;
        }

        // forprep 的计数公式逐字复刻：count = 步数-1（无符号除法容忍大步长边界）
        long count;
        if (step > 0) {
            count = limit - init;
            if (step != 1) count = Long.divideUnsigned(count, step);
        } else {
            count = init - limit;
            count = Long.divideUnsigned(count, (-(step + 1)) + 1);
        }

        int A = ra - base;
        Prototype proto = cl.p;
        IForPlan plan = getPlan(proto, forprepPc);
        if (plan == null) {
            plan = analyze(proto, forprepPc, A, code, k);
            putPlan(proto, forprepPc, plan);
        }
        if (plan == REJECTED) {
            if (STATS) statRejectedEntry++;
            return -1;
        }

        // 开放 upvalue 别名检查：upvalue 盒指向本帧寄存器时，扁平写不经过盒子会分叉
        if (L.openupval != null && !L.openupval.isEmpty()) {
            for (UpVal uv : L.openupval) {
                if (uv.upisopen()) {
                    int s = uv.slot();
                    if (s >= base + plan.minReg && s <= base + plan.maxReg) {
                        if (STATS) statEntryRefused++;
                        return -1;
                    }
                }
            }
        }

        long[] R = new long[plan.maxReg + 1];
        R[A] = count;
        R[A + 1] = step;
        R[A + 2] = init;

        // live-in 寄存器进入时必须已是整数
        int[] liveIn = plan.liveInRegs;
        for (int i = 0; i < liveIn.length; i++) {
            int r = liveIn[i];
            LuaValue v = stack[base + r];
            if (v.tt_ != LuaValue.LUA_VNUMINT) {
                if (STATS) statEntryRefused++;
                return -1;
            }
            R[r] = ((LuaInteger) v).v;
        }

        // -- 主循环：先跑一轮，之后每次 FORLOOP（count>0 则递减 + ctrl += step）--
        int[] ops = plan.ops;
        int[] regA = plan.regA;
        int[] regB = plan.regB;
        long[] kv = plan.kv;
        int[] pcs = plan.pcs;
        int n = ops.length;
        while (true) {
            for (int j = 0; j < n; ) {
                int op = ops[j];
                if (op == Opcodes.OP_JMP) {
                    j = (int) kv[j];
                    continue;
                }
                // 返回值：0=bail；1=顺序前进；2=跳过下一条（比较条件命中 k 的反向）
                int adv = execOp(op, regA[j], regB[j], kv[j], R);
                if (adv == 0) {
                    // 除零 bail：写回后从该条指令续跑装箱路径（剩余计数保持未递减状态）
                    if (STATS) statMidBail++;
                    writeBack(stack, base, plan, R);
                    int resumePc = forprepPc + 1 + pcs[j];
                    L.ci.savedpc = resumePc;
                    L.top = L.ci.top;
                    return resumePc;
                }
                j += adv;
            }
            if (R[A] == 0) break;
            R[A]--;
            R[A + 2] += step;
        }

        writeBack(stack, base, plan, R);
        if (STATS) statTaken++;
        return forprepPc + 1 + plan.bodyLen + 1;  // FORLOOP 之后一条
    }

    // 返回值：0=bail；1=顺序前进；2=跳过下一条指令（比较条件与 k 相异时，对齐
    // lvm.c 的 "if (cond != k) pc++"——被跳过的那条恒是 JMP）
    private static int execOp(int op, int a, int b, long c, long[] R) {
        switch (op) {
            case Opcodes.OP_MOVE -> R[a] = R[b];
            case Opcodes.OP_LOADI -> R[a] = c;
            case Opcodes.OP_ADD -> R[a] = R[b] + R[(int) c];
            case Opcodes.OP_SUB -> R[a] = R[b] - R[(int) c];
            case Opcodes.OP_MUL -> R[a] = R[b] * R[(int) c];
            case Opcodes.OP_ADDI -> R[a] = R[b] + c;
            case Opcodes.OP_ADDK -> R[a] = R[b] + c;
            case Opcodes.OP_SUBK -> R[a] = R[b] - c;
            case Opcodes.OP_MULK -> R[a] = R[b] * c;
            case Opcodes.OP_MOD -> {
                long d = R[(int) c];
                if (d == 0) return 0;
                R[a] = intMod(R[b], d);
            }
            case Opcodes.OP_MODK -> {
                if (c == 0) return 0;
                R[a] = intMod(R[b], c);
            }
            case Opcodes.OP_IDIV -> {
                long d = R[(int) c];
                if (d == 0) return 0;
                R[a] = intIDiv(R[b], d);
            }
            case Opcodes.OP_IDIVK -> {
                if (c == 0) return 0;
                R[a] = intIDiv(R[b], c);
            }
            case Opcodes.OP_BAND -> R[a] = R[b] & R[(int) c];
            case Opcodes.OP_BOR -> R[a] = R[b] | R[(int) c];
            case Opcodes.OP_BXOR -> R[a] = R[b] ^ R[(int) c];
            case Opcodes.OP_BANDK -> R[a] = R[b] & c;
            case Opcodes.OP_BORK -> R[a] = R[b] | c;
            case Opcodes.OP_BXORK -> R[a] = R[b] ^ c;
            case Opcodes.OP_SHL -> R[a] = shiftL(R[b], R[(int) c]);
            case Opcodes.OP_SHR -> R[a] = shiftL(R[b], -R[(int) c]);
            case Opcodes.OP_SHRI -> R[a] = shiftL(R[b], -c);
            case Opcodes.OP_SHLI -> R[a] = shiftL(c, R[b]);
            case Opcodes.OP_UNM -> R[a] = 0 - R[b];
            case Opcodes.OP_BNOT -> R[a] = ~R[b];
            // -- 比较：扁平世界全为整数，直接 long 比较；b=k 位 --
            case Opcodes.OP_EQ -> {
                if ((R[a] == R[(int) c]) != (b != 0)) return 2;
            }
            case Opcodes.OP_LT -> {
                if ((R[a] < R[(int) c]) != (b != 0)) return 2;
            }
            case Opcodes.OP_LE -> {
                if ((R[a] <= R[(int) c]) != (b != 0)) return 2;
            }
            case Opcodes.OP_EQK -> {
                if ((R[a] == c) != (b != 0)) return 2;
            }
            case Opcodes.OP_EQI -> {
                if ((R[a] == c) != (b != 0)) return 2;
            }
            case Opcodes.OP_LTI -> {
                if ((R[a] < c) != (b != 0)) return 2;
            }
            case Opcodes.OP_LEI -> {
                if ((R[a] <= c) != (b != 0)) return 2;
            }
            case Opcodes.OP_GTI -> {
                if ((R[a] > c) != (b != 0)) return 2;
            }
            case Opcodes.OP_GEI -> {
                if ((R[a] >= c) != (b != 0)) return 2;
            }
            // l_isfalse：整数世界非零即真；TESTSET 命中 k 时把 B 槽复制到 A 槽
            case Opcodes.OP_TEST -> {
                if ((R[a] != 0) != (b != 0)) return 2;
            }
            case Opcodes.OP_TESTSET -> {
                boolean cond = R[(int) c] != 0;
                if (cond != (b != 0)) return 2;
                R[a] = R[(int) c];
            }
            // 元方法回退标记在纯整数路径恒死（前置快路径成功即 pc++ 跳过）
            case Opcodes.OP_MMBIN, Opcodes.OP_MMBINI, Opcodes.OP_MMBINK -> {
            }
            default -> {
                return 0;
            }
        }
        return 1;
    }

    // 出口/bail 时把引擎寄存器写回栈槽（装箱路径看到的帧状态与其自身跑到此处一致）
    private static void writeBack(LuaValue[] stack, int base, IForPlan plan, long[] R) {
        stack[base + plan.A] = LuaInteger.valueOf(R[plan.A]);
        stack[base + plan.A + 1] = LuaInteger.valueOf(R[plan.A + 1]);
        stack[base + plan.A + 2] = LuaInteger.valueOf(R[plan.A + 2]);
        int[] wb = plan.writeBackRegs;
        for (int i = 0; i < wb.length; i++) {
            int r = wb[i];
            stack[base + r] = LuaInteger.valueOf(R[r]);
        }
    }

    // -- 分析：扫描 [forprepPc+1, forloopPc)，白名单外一律拒绝 --
    private static IForPlan analyze(Prototype proto, int forprepPc, int A, int[] code, LuaValue[] k) {
        int bodyStart = forprepPc + 1;
        int maxEnd = Math.min(code.length, bodyStart + MAX_BODY);
        int forloopPc = -1;
        for (int p = bodyStart; p < maxEnd; p++) {
            int op = code[p] & 0x7F;
            if (op == Opcodes.OP_FORLOOP) {
                forloopPc = p;
                break;
            }
            // 内层数值 for 的 FORPREP 先于外层 FORLOOP 出现 ⇒ 配对歧义，拒绝
            if (op == Opcodes.OP_FORPREP) return REJECTED;
        }
        if (forloopPc < 0) return REJECTED;
        // FORLOOP 必须作用于同一寄存器基（编译器保证，防御性校验）
        if (((code[forloopPc] >>> 7) & 0xFF) != A) return REJECTED;
        int bodyLen = forloopPc - bodyStart;

        boolean[] liveIn = new boolean[MAX_REG];
        boolean[] writtenMask = new boolean[MAX_REG];
        int[] ops = new int[bodyLen];
        int[] regA = new int[bodyLen];
        int[] regB = new int[bodyLen];
        long[] kv = new long[bodyLen];
        int[] pcs = new int[bodyLen];
        // 槽位→压缩下标映射：MMBIN 剥离使压缩数组下标 ≠ 原始槽位，JMP 目标必须重定位
        int[] compactOf = new int[bodyLen];
        Arrays.fill(compactOf, -1);
        // JMP 的 kv 先记原始目标槽位，扫描完统一回填压缩下标
        ArrayList<int[]> jmpFixups = new ArrayList<>();
        int minReg = A, maxReg = A + 2;
        int ni = 0;

        for (int idx = 0; idx < bodyLen; idx++) {
            int p = bodyStart + idx;
            int inst = code[p];
            int op = inst & 0x7F;
            int a = (inst >>> 7) & 0xFF;
            int b = (inst >>> 16) & 0xFF;
            int c = (inst >>> 24) & 0xFF;
            switch (op) {
                case Opcodes.OP_MMBIN, Opcodes.OP_MMBINI, Opcodes.OP_MMBINK -> {
                    continue;
                }
                case Opcodes.OP_JMP -> {
                    // sJ 占满 A+B 位域（25 位有符号），目标相对下一条指令
                    int sj = ((inst >>> 7) & 0x1FFFFFF) - (((1 << 25) - 1) >> 1);
                    int target = p + 1 + sj;
                    if (target <= p || target >= forloopPc) return REJECTED;
                    ops[ni] = op;
                    kv[ni] = target - bodyStart;   // 先存原始槽位，扫描完回填压缩下标
                    jmpFixups.add(new int[]{ni, target - bodyStart});
                    pcs[ni] = idx;
                    ni++;
                    continue;
                }
                case Opcodes.OP_MOVE -> {
                    if (!checkDst(a, A)) return REJECTED;
                    markRead(b, A, liveIn, writtenMask);
                    markWrite(a, liveIn, writtenMask);
                    ops[ni] = op;
                    regA[ni] = a;
                    regB[ni] = b;
                    pcs[ni] = idx;
                    minReg = Math.min(minReg, Math.min(a, b));
                    maxReg = Math.max(maxReg, Math.max(a, b));
                }
                case Opcodes.OP_LOADI -> {
                    if (!checkDst(a, A)) return REJECTED;
                    markWrite(a, liveIn, writtenMask);
                    ops[ni] = op;
                    regA[ni] = a;
                    kv[ni] = ((inst >>> 15) & 0x1FFFF) - 0xFFFF;
                    pcs[ni] = idx;
                    minReg = Math.min(minReg, a);
                    maxReg = Math.max(maxReg, a);
                }
                case Opcodes.OP_ADD, Opcodes.OP_SUB, Opcodes.OP_MUL,
                     Opcodes.OP_BAND, Opcodes.OP_BOR, Opcodes.OP_BXOR,
                     Opcodes.OP_SHL, Opcodes.OP_SHR, Opcodes.OP_MOD, Opcodes.OP_IDIV -> {
                    if (!checkDst(a, A)) return REJECTED;
                    if (c >= MAX_REG) return REJECTED;
                    markRead(b, A, liveIn, writtenMask);
                    markRead(c, A, liveIn, writtenMask);
                    markWrite(a, liveIn, writtenMask);
                    ops[ni] = op;
                    regA[ni] = a;
                    regB[ni] = b;
                    kv[ni] = c;   // 第三操作数是寄存器号
                    pcs[ni] = idx;
                    minReg = Math.min(minReg, Math.min(Math.min(a, b), c));
                    maxReg = Math.max(maxReg, Math.max(Math.max(a, b), c));
                }
                case Opcodes.OP_ADDI, Opcodes.OP_ADDK, Opcodes.OP_SUBK, Opcodes.OP_MULK,
                     Opcodes.OP_MODK, Opcodes.OP_IDIVK,
                     Opcodes.OP_BANDK, Opcodes.OP_BORK, Opcodes.OP_BXORK,
                     Opcodes.OP_SHRI, Opcodes.OP_SHLI -> {
                    if (!checkDst(a, A)) return REJECTED;
                    markRead(b, A, liveIn, writtenMask);
                    markWrite(a, liveIn, writtenMask);
                    ops[ni] = op;
                    regA[ni] = a;
                    regB[ni] = b;
                    // K 系列取常量值（须整数）；ADDI/SHRI/SHLI 取有符号立即数
                    if (op == Opcodes.OP_ADDK || op == Opcodes.OP_SUBK || op == Opcodes.OP_MULK
                            || op == Opcodes.OP_MODK || op == Opcodes.OP_IDIVK
                            || op == Opcodes.OP_BANDK || op == Opcodes.OP_BORK
                            || op == Opcodes.OP_BXORK) {
                        if (c >= k.length) return REJECTED;
                        LuaValue kc = k[c];
                        if (kc.tt_ != LuaValue.LUA_VNUMINT) return REJECTED;
                        kv[ni] = ((LuaInteger) kc).v;
                    } else {
                        kv[ni] = c - 127;
                    }
                    pcs[ni] = idx;
                    minReg = Math.min(minReg, Math.min(a, b));
                    maxReg = Math.max(maxReg, Math.max(a, b));
                }
                case Opcodes.OP_UNM, Opcodes.OP_BNOT -> {
                    if (!checkDst(a, A)) return REJECTED;
                    markRead(b, A, liveIn, writtenMask);
                    markWrite(a, liveIn, writtenMask);
                    ops[ni] = op;
                    regA[ni] = a;
                    regB[ni] = b;
                    pcs[ni] = idx;
                    minReg = Math.min(minReg, Math.min(a, b));
                    maxReg = Math.max(maxReg, Math.max(a, b));
                }
                // -- 比较族：不写寄存器，条件与 k 相异时跳过紧随的 JMP（引擎三态返回）--
                // EQ/LT/LE 寄存器对；EQK 常量须整数；EQI/LTI/LEI/GTI/GEI 立即数在 B 域；
                // TEST 只测 A；TESTSET 测 B、命中时复制到 A
                case Opcodes.OP_EQ, Opcodes.OP_LT, Opcodes.OP_LE -> {
                    if (a >= MAX_REG || b >= MAX_REG) return REJECTED;
                    markRead(a, A, liveIn, writtenMask);
                    markRead(b, A, liveIn, writtenMask);
                    ops[ni] = op;
                    regA[ni] = a;
                    regB[ni] = (inst >>> 15) & 1;
                    kv[ni] = b;
                    pcs[ni] = idx;
                    minReg = Math.min(minReg, Math.min(a, b));
                    maxReg = Math.max(maxReg, Math.max(a, b));
                }
                case Opcodes.OP_EQK -> {
                    if (a >= MAX_REG) return REJECTED;
                    if (b >= k.length) return REJECTED;
                    LuaValue kc = k[b];
                    if (kc.tt_ != LuaValue.LUA_VNUMINT) return REJECTED;
                    markRead(a, A, liveIn, writtenMask);
                    ops[ni] = op;
                    regA[ni] = a;
                    regB[ni] = (inst >>> 15) & 1;
                    kv[ni] = ((LuaInteger) kc).v;
                    pcs[ni] = idx;
                    minReg = Math.min(minReg, a);
                    maxReg = Math.max(maxReg, a);
                }
                case Opcodes.OP_EQI, Opcodes.OP_LTI, Opcodes.OP_LEI,
                     Opcodes.OP_GTI, Opcodes.OP_GEI -> {
                    if (a >= MAX_REG) return REJECTED;
                    markRead(a, A, liveIn, writtenMask);
                    ops[ni] = op;
                    regA[ni] = a;
                    regB[ni] = (inst >>> 15) & 1;
                    kv[ni] = b - 127;
                    pcs[ni] = idx;
                    minReg = Math.min(minReg, a);
                    maxReg = Math.max(maxReg, a);
                }
                case Opcodes.OP_TEST -> {
                    if (a >= MAX_REG) return REJECTED;
                    markRead(a, A, liveIn, writtenMask);
                    ops[ni] = op;
                    regA[ni] = a;
                    regB[ni] = (inst >>> 15) & 1;
                    pcs[ni] = idx;
                    minReg = Math.min(minReg, a);
                    maxReg = Math.max(maxReg, a);
                }
                case Opcodes.OP_TESTSET -> {
                    if (!checkDst(a, A)) return REJECTED;
                    if (b >= MAX_REG) return REJECTED;
                    markRead(b, A, liveIn, writtenMask);
                    markWrite(a, liveIn, writtenMask);
                    ops[ni] = op;
                    regA[ni] = a;
                    regB[ni] = (inst >>> 15) & 1;
                    kv[ni] = b;
                    pcs[ni] = idx;
                    minReg = Math.min(minReg, Math.min(a, b));
                    maxReg = Math.max(maxReg, Math.max(a, b));
                }
                default -> {
                    // 调用/表访问/内层泛型 for 等 ⇒ 该站点不可扁平化
                    return REJECTED;
                }
            }
            compactOf[idx] = ni;
            ni++;
        }

        // JMP 目标重定位：原始槽位下标 → 压缩数组下标；目标是被剥离的 MMBIN 槽则拒绝
        for (int[] fx : jmpFixups) {
            int ci = compactOf[fx[1]];
            if (ci < 0) return REJECTED;
            kv[fx[0]] = ci;
        }

        IForPlan plan = new IForPlan();
        plan.A = A;
        plan.bodyLen = bodyLen;
        plan.minReg = minReg;
        plan.maxReg = maxReg;
        plan.liveInRegs = compact(liveIn, minReg, maxReg);
        plan.writeBackRegs = compact(writtenMask, minReg, maxReg);
        plan.ops = new int[ni];
        plan.regA = new int[ni];
        plan.regB = new int[ni];
        plan.kv = new long[ni];
        plan.pcs = new int[ni];
        System.arraycopy(ops, 0, plan.ops, 0, ni);
        System.arraycopy(regA, 0, plan.regA, 0, ni);
        System.arraycopy(regB, 0, plan.regB, 0, ni);
        System.arraycopy(kv, 0, plan.kv, 0, ni);
        System.arraycopy(pcs, 0, plan.pcs, 0, ni);
        return plan;
    }

    // 目标寄存器不得是循环三元组槽（A=count、A+1=step 隐藏，A+2=const 控制变量）。
    // body 其余寄存器可任意（既有局部变量的编号可以低于循环三元组）。
    private static boolean checkDst(int a, int A) {
        return (a < A || a > A + 2) && a < MAX_REG;
    }

    private static void markRead(int r, int A, boolean[] liveIn, boolean[] written) {
        if (r < MAX_REG && !written[r] && r != A && r != A + 1 && r != A + 2) liveIn[r] = true;
    }

    private static void markWrite(int r, boolean[] liveIn, boolean[] written) {
        if (r < MAX_REG) {
            written[r] = true;
            liveIn[r] = false;
        }
    }

    private static int[] compact(boolean[] mask, int minReg, int maxReg) {
        int n = 0;
        for (int r = minReg; r <= maxReg; r++) if (mask[r]) n++;
        int[] out = new int[n];
        int i = 0;
        for (int r = minReg; r <= maxReg; r++) if (mask[r]) out[i++] = r;
        return out;
    }

    // -- 整数 mod/idiv/shl（与 FlatTFor 同一算术，逐字对齐 LuaArith/lvm.c）--
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

    // NBITS=64：Java 移位量隐式 &63，C 在 |y|>=64 时明确返回 0，须显式判断；
    // 右移按无符号位串处理（>>>）
    private static long shiftL(long x, long y) {
        if (y < 0) {
            if (y <= -64) return 0;
            return x >>> (int) (-y);
        } else {
            if (y >= 64) return 0;
            return x << (int) y;
        }
    }

    static final class IForPlan {
        int A;
        int bodyLen;
        int minReg, maxReg;
        int[] liveInRegs;
        int[] writeBackRegs;
        int[] ops;
        int[] regA;
        int[] regB;
        long[] kv;      // 常量/立即数；reg 形式的第三操作数存寄存器号；JMP 存体内目标下标
        int[] pcs;      // 各指令在体内的下标（bail 续跑定位用）
    }
}
