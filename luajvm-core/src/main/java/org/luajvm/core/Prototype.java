// ref: lobject.h (Proto)
// diff: 无CommonHeader/gclist(JVM GC); 无PF_FIXED(无固定内存); abslineinfo用int[]平铺; is_vararg通过flag位运算; LocVar.ridx为Java特有
package org.luajvm.core;

import java.util.ArrayList;
import java.util.Iterator;

public class Prototype {
    // PF_VAHID/PF_VATAB  -  vararg标志位; java: C还有PF_FIXED=4，Java不需要
    public static final byte PF_VAHID = 1, PF_VATAB = 2;
    // lobject.h: sizeof(Proto)  -  C中luaF_newproto调用luaC_newobj走frealloc
    // java diff: 用luaM_checkmemory追踪字节，allProtos列表用于sweep时减luaMemoryBytes
    private static final long PROTO_HEADER_BYTES = 128L;
    private static final long K_BYTES = 16L;
    private static final long CODE_BYTES = 4L;
    private static final long LINEINFO_BYTES = 4L;
    private static final long ABSLINEINFO_BYTES = 8L;
    private static final long SUBPROTO_REF_BYTES = 8L;
    private static final long UPVALDESC_BYTES = 16L;
    private static final long LOCVAR_BYTES = 16L;
    // lgc.h: WHITE0BIT=3, WHITE1BIT=4, BLACKBIT=5
    // java diff: C 用 'marked' 的位域；Java 用 gcColor 字节
    // gcColor: 0=WHITE0, 1=WHITE1, 2=GRAY, 3=BLACK; 默认 WHITE0
    public byte gcColor = 0;
    // C：lstate.h : GCObject 所属 global_State。
    Globals ownerGlobals;

    public byte gcAge = LuaValue.G_NEW;
    // Proto.flag
    public byte flag;
    // Proto.numparams
    public int numparams;
    // Proto.maxstacksize
    public int maxstacksize;
    // Proto.sizek/sizecode/sizelineinfo/sizep/sizelocvars/sizeabslineinfo/sizeupvalues
    public int sizek, sizecode, sizelineinfo, sizep, sizelocvars, sizeabslineinfo, sizeupvalues;
    // Proto.linedefined/lastlinedefined
    public int linedefined, lastlinedefined;
    // Proto.k
    public LuaValue[] k = LuaValue.NOVALS;
    // Proto.code
    public int[] code = new int[0];
    // Proto.lineinfo
    public int[] lineinfo = new int[0];
    // Proto.abslineinfo
    public int[] abslineinfo = new int[0];
    // Proto.p
    public Prototype[] p = new Prototype[0];
    // Proto.source
    public LuaString source;
    // Proto.upvalues
    public Upvaldesc[] upvalues = new Upvaldesc[0];
    // Proto.locvars
    public LocVar[] locvars = new LocVar[0];
    // java-only: TFOR 循环扁平化静态计划缓存（按 TFORPREP pc 缓存；Object 存放规避
    //   反向依赖；TFOR_REJECTED 哨兵；null=未分析）
    public Object[] tforPlans;
    // java-only: 数值 for 循环扁平化计划缓存（按 FORPREP pc 缓存，结构同 tforPlans）
    public Object[] iforPlans;

    /**
     * 非白原型复位为白，供 fullGC 的重传播使用。
     *
     * <p>原型不是 LuaValue、不入灰链，其内容（source、常量池 k、子原型）只在
     * {@code propagateOne} 见到 {@code iswhite(p.gcColor)} 时才内联标记一次。
     * 若跨周期保留上轮的 BLACK，本轮就不会再遍历它，常量池里的串既不被标记
     * 也不被判活，于是被 sweep 摘出驻留表 - 同内容随后产生第二个对象，
     * 而短串相等是身份比较，表现为 chunk 里的字面量与 Java 侧 intern 的串判不等。
     */
    static void repropagateAll(Globals g, byte cw) {
        for (int i = 0, n = g.gc.allProtos.size(); i < n; i++) {
            Prototype pt = g.gc.allProtos.get(i);
            if (!LuaGC.iswhite(pt.gcColor)) pt.gcColor = cw;
        }
    }

    // lgc.c: sweep  -  按 gcColor 释放不可达的 Prototype
    // java diff: 用 LuaGC.isdead 检查对象是否来自上一轮的 white
    static void sweepProtosByColor(Globals g) {
        byte cw = LuaGC.isWhite(g);
        boolean inc = LuaGC.isIncrementalMode(g);
        // java diff: 反向索引遍历替代 Iterator，消除 ArrayList$Itr 分配
        for (int j = g.gc.allProtos.size() - 1; j >= 0; j--) {
            Prototype pt = g.gc.allProtos.get(j);
            if (LuaGC.isdead(g, pt.gcColor)) {
                LuaGC.free(g, pt.computeBytes());
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                g.gc.allProtos.remove(j);
            } else {
                // lgc.c: sweeplist/sweep2old  -  inc 幸存 white|G_NEW，fullgc G_OLD+white
                // java diff: 此处设 gcAge（消除 agesAfterFullGC 的 O(n) 遍历）
                if (!LuaGC.iswhite(pt.gcColor)) pt.gcColor = cw;
                pt.gcAge = (byte) (inc ? LuaValue.G_NEW : LuaValue.G_OLD);
            }
        }
    }

    // lgc.c: sweepgen  -  G_NEW->G_SURVIVAL|white；其余前进 age，保持颜色
    static void sweepGen(Globals g, byte cw) {
        Iterator<Prototype> it = g.gc.allProtos.iterator();
        while (it.hasNext()) {
            Prototype pt = it.next();
            if (LuaGC.isdead(g, pt.gcColor)) {
                LuaGC.free(g, pt.computeBytes());
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                it.remove();
            } else if (pt.gcAge == LuaValue.G_NEW) {
                pt.gcColor = cw;
                pt.gcAge = LuaValue.G_SURVIVAL;
            } else {
                switch (pt.gcAge) {
                    case LuaValue.G_SURVIVAL:
                        pt.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD0:
                        pt.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD1:
                        pt.gcAge = LuaValue.G_OLD;
                        break;
                }
            }
        }
    }

    // lobject.h: isvararg
    public boolean isVararg() {
        return (flag & (PF_VAHID | PF_VATAB)) != 0;
    }

    // lparser.c: setvararg
    public void setVararg() {
        flag |= PF_VAHID;
    }

    // lfunc.c: luaF_newproto  -  create new prototype
    // java diff: 编译完成后调 commitProtoMem（size 字段逐步设置）；
    //   C 用 luaC_newobj 走 frealloc；Java 用 checkMemory 追踪 + allProtos 列表
    public void commitProtoMem() {
        long bytes = computeBytes();
        LuaGC.checkMemory(bytes);
        gcColor = LuaGC.isWhite();  // lgc.c: luaC_newobj sets marked = isWhite(g)
        LuaGC.commitRealloc(0, bytes);
    }

    final void bindGlobals(Globals globals) {
        if (globals == null || ownerGlobals == globals) return;
        if (ownerGlobals != null) throw LuaErrors.errorObject("prototype belongs to another Globals");
        ownerGlobals = globals;
        gcColor = LuaGC.isWhite(globals);
        globals.gc.allProtos.add(this);
        LuaGC.commitRealloc(globals, 0, computeBytes());
        for (int i = 0; i < p.length; i++) {
            Prototype child = p[i];
            if (child != null) child.bindGlobals(globals);
        }
    }

    private long computeBytes() {
        return PROTO_HEADER_BYTES
                + sizek * K_BYTES
                + sizecode * CODE_BYTES
                + sizelineinfo * LINEINFO_BYTES
                + sizeabslineinfo * ABSLINEINFO_BYTES
                + sizep * SUBPROTO_REF_BYTES
                + sizeupvalues * UPVALDESC_BYTES
                + sizelocvars * LOCVAR_BYTES;
    }

    // ref: lobject.h (Upvaldesc)
    public static class Upvaldesc {
        // Upvaldesc.name
        public LuaString name;
        // Upvaldesc.idx
        public int idx;
        // Upvaldesc.instack
        public boolean instack;
        // Upvaldesc.kind
        public int kind;

        public Upvaldesc() {
        }

        public Upvaldesc(LuaString name, int idx, boolean instack, int kind) {
            this.name = name;
            this.idx = idx;
            this.instack = instack;
            this.kind = kind;
        }
    }

    // ref: lobject.h (LocVar)
    // diff: ridx为Java特有，编译期记录寄存器索引
    public static class LocVar {
        // LocVar.varname
        public LuaString varname;
        // LocVar.startpc
        public int startpc;
        // LocVar.endpc
        public int endpc;
        // java diff: C 无 ridx，编译期使用
        public int ridx = -1;
    }
}
