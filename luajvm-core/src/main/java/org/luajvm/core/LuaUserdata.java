// ref: lobject.h (Udata)
// diff: Java用Object代替void*; finalizer注册/GC管理为Java特有
package org.luajvm.core;

import org.luajvm.vm.LuaCall;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;

public class LuaUserdata extends LuaValue {
    // Udata.nuvalue
    public final int nuvalue;
    // Udata.len  -  payload字节数
    public final long len;
    // Udata.uv[]  -  user value数组
    private final LuaValue[] uv;
    // java-only
    private final long storageBytes;
    // Udata.udatamem  -  载荷
    public Object udatamem;
    // C：lstate.h : GCObject 所属 global_State。
    Globals ownerGlobals;
    // Udata.metatable
    public LuaValue metatable;
    // java-only: finalizer注册标记
    boolean m_finalizerRegistered;
    // java-only
    private boolean storageReleased;

    public LuaUserdata(Object o) {
        this(o, 1);
    }

    // java-only
    public LuaUserdata(Object o, int nuvalue) {
        this(o, nuvalue, 0);
    }

    public LuaUserdata(Object o, int nuvalue, long payloadBytes) {
        super(LUA_VUSERDATA | BIT_ISCOLLECTABLE);
        int normalizedNuvalue = Math.max(0, nuvalue);
        long bytes = userdataStorageBytes(payloadBytes, normalizedNuvalue);
        LuaGC.checkMemory(bytes);
        LuaGC.commitRealloc(0, bytes);
        this.storageBytes = bytes;
        this.storageReleased = false;
        gcColor = LuaGC.isWhite();  // lgc.c: luaC_newobj sets marked = isWhite(g)
        this.udatamem = o;
        this.metatable = null;
        this.m_finalizerRegistered = false;
        this.uv = new LuaValue[normalizedNuvalue];
        Arrays.fill(this.uv, LuaValue.NIL);
        this.nuvalue = normalizedNuvalue;
        this.len = Math.max(0, payloadBytes);
    }

    /**
     * java-only: 只读取所属状态（{@code org.luajvm.bind} 需按状态缓存 {@code JavaClass}）。
     * 写入仍只经 {@link #bindGlobals}，保持单次绑定语义。
     */
    public final Globals owner() {
        return ownerGlobals;
    }

    final void bindGlobals(Globals globals) {
        if (globals == null || ownerGlobals == globals) return;
        if (ownerGlobals != null) throw LuaErrors.errorObject("userdata belongs to another Globals");
        ownerGlobals = globals;
        // 分配限额检查在构造处完成（LuaUserdata 构造调 LuaGC.checkMemory），对齐 C。
        gcColor = LuaGC.isWhite(globals);
        globals.gc.allUserdata.add(this);
        LuaGC.commitRealloc(globals, 0, storageBytes);
        if (metatable instanceof LuaTable table) table.bindGlobals(globals);
    }

    // lobject.h: sizeudata  -  user-value 数组 + payload 区域字节数
    //   头部字节数对齐 C 的 udatamemoffset 宏计算
    private static long userdataStorageBytes(long payloadBytes, int nuvalue) {
        long payload = Math.max(0, payloadBytes);
        long header = nuvalue == 0 ? 48L : 48L + (long) nuvalue * 16L;
        return header + payload;
    }

    // java-only
    public static int managedUserdataCount(Globals g) {
        return g == null ? 0 : g.gc.allUserdata.size();
    }

    // lgc.c: sweep  -  luaM_free_ unreachable userdata by gcColor
    // java diff: uses LuaGC.isdead to check if object is from previous cycle's white
    static void sweepByColor(Globals g) {
        byte cw = LuaGC.isWhite(g);
        boolean inc = LuaGC.isIncrementalMode(g);
        // java diff: 反向索引遍历替代 Iterator，消除 ArrayList$Itr 分配
        for (int j = g.gc.allUserdata.size() - 1; j >= 0; j--) {
            LuaUserdata userdata = g.gc.allUserdata.get(j);
            if (LuaGC.isdead(g, userdata.gcColor) && !userdata.m_finalizerRegistered) {
                userdata.releaseStorageForCollector();
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                g.gc.allUserdata.remove(j);
            } else {
                // lgc.c: sweeplist/sweep2old  -  inc 幸存 white|G_NEW，fullgc G_OLD+white
                // java diff: 此处设 gcAge（消除 agesAfterFullGC 的 O(n) 遍历）
                if (!LuaGC.iswhite(userdata.gcColor)) userdata.makeWhite(cw);
                userdata.gcAge = (byte) (inc ? LuaValue.G_NEW : LuaValue.G_OLD);
            }
        }
    }

    // lgc.c: sweepgen  -  G_NEW->G_SURVIVAL|white; others advance age, keep color
    static void sweepGen(Globals g, byte cw) {
        Iterator<LuaUserdata> it = g.gc.allUserdata.iterator();
        while (it.hasNext()) {
            LuaUserdata userdata = it.next();
            if (LuaGC.isdead(g, userdata.gcColor) && !userdata.m_finalizerRegistered) {
                userdata.releaseStorageForCollector();
                LuaGC.markObjectsSwept(g);  // java-only: 动态阈值跟踪
                it.remove();
            } else if (userdata.gcAge == LuaValue.G_NEW) {
                // lgc.c: sweepgen  -  new objects go back to white + G_SURVIVAL
                userdata.makeWhite(cw);
                userdata.gcAge = LuaValue.G_SURVIVAL;
            } else {
                // lgc.c: sweepgen  -  advance age, keep color
                switch (userdata.gcAge) {
                    case LuaValue.G_SURVIVAL:
                        userdata.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD0:
                        userdata.gcAge = LuaValue.G_OLD1;
                        break;
                    case LuaValue.G_OLD1:
                        userdata.gcAge = LuaValue.G_OLD;
                        break;
                }
            }
        }
    }

    // java-only
    public static LuaUserdata userdataOf(Object o) {
        return new LuaUserdata(o);
    }

    // java-only
    public static LuaUserdata userdataOf(Object o, int nuvalue) {
        return new LuaUserdata(o, nuvalue);
    }

    static void repropagateAll(Globals g, byte cw) {
        for (int _gi = 0, _gn = g.gc.allUserdata.size(); _gi < _gn; _gi++) {
            LuaUserdata u = g.gc.allUserdata.get(_gi);
            if (!LuaGC.iswhite(u.gcColor)) u.makeWhite(cw);
        }
    }

    // lgc.c: objsize  -  approximate size for GCmarked
    @Override
    public int gcSize() {
        return 64 + (uv != null ? uv.length * 8 : 0);
    }

    // java-only
    private void releaseStorageForCollector() {
        if (storageReleased) return;
        LuaGC.free(ownerGlobals, storageBytes);
        storageReleased = true;
    }

    @Override
    public int type() {
        return TUSERDATA;
    }

    @Override
    public String typeName() {
        return "userdata";
    }

    // java-only 修复：userdata 相等按包装的 Java 对象比较（equals）而非引用 -
    //   否则同一 Java 对象多次包装后 `==` 恒 false
    @Override
    public boolean raweq(LuaValue r) {
        if (this == r) return true;
        if (r instanceof LuaUserdata lu)
            return Objects.equals(udatamem, lu.udatamem);
        return false;
    }

    @Override
    public Object touserdata() {
        return udatamem;
    }

    // java-only
    @Override
    public <T> T touserdata(Class<T> c) {
        return c.isInstance(udatamem) ? c.cast(udatamem) : null;
    }

    // java-only: 与 touserdata(Class)/optuserdata(Class,d) 同判据；基类桩恒 false
    //   会让 isuserdata(Class) 与那两个互相矛盾（loadlayout 构造器缓存、src 的
    //   Drawable 分支、typeface、getter 的 CharSequence 转字符串全依赖它）
    @Override
    public boolean isuserdata(Class<?> c) {
        return c.isInstance(udatamem);
    }

    // java-only: optuserdata 基类是桩，按实例类型判断
    @Override
    public Object optuserdata(Class<?> c, Object d) {
        return c.isInstance(udatamem) ? udatamem : d;
    }

    @Override
    public Object checkuserdata() {
        return udatamem;
    }

    // java-only
    @Override
    public Object checkuserdata(Class<?> c) {
        if (c.isInstance(udatamem)) return udatamem;
        typeError("userdata");
        return null;
    }

    @Override
    public LuaValue getmetatable() {
        return metatable;
    }

    @Override
    public LuaValue setmetatable(LuaValue mt) {
        if (ownerGlobals != null && mt instanceof LuaTable table) table.bindGlobals(ownerGlobals);
        // lgc.c/lapi.c: luaC_objbarrier  -  userdata 黑、mt 白时标记 mt
        if (LuaGC.isblack(gcColor) && mt != null && mt.iscollectable() && LuaGC.iswhite(mt.gcColor)) {
            LuaGC.barrier(ownerGlobals, mt);
        }
        // lgc.c: luaC_barrier_  -  old userdata referencing new metatable -> metatable becomes OLD0
        if (gcAge >= LuaValue.G_OLD && mt != null && mt.iscollectable() && mt.gcAge == LuaValue.G_NEW) {
            mt.gcAge = LuaValue.G_OLD0;
        }
        metatable = mt;

        if (!m_finalizerRegistered && LuaTable.hasFinalizer(mt)) {

            LuaTable.registerFinalizerCandidate(this);
        }
        return this;
    }

    // java-only
    public LuaValue getuservalue() {
        return getuservalue(1) != null ? getuservalue(1) : LuaValue.NIL;
    }

    public LuaValue getuservalue(int n) {
        return n >= 1 && n <= uv.length ? uv[n - 1] : null;
    }

    // java-only
    LuaValue[] uservaluesRaw() {
        return uv;
    }

    // java-only
    public void setuservalue(LuaValue v) {
        setuservalue(1, v);
    }

    public boolean setuservalue(int n, LuaValue v) {
        if (n < 1 || n > uv.length) return false;
        // lgc.c: luaC_barrierback  -  black userdata referencing white collectable -> link in grayagain
        if (LuaGC.isblack(gcColor) && v.iscollectable() && LuaGC.iswhite(v.gcColor)) {
            gcColor = 2;  // GRAY
            ownerGlobals.gc.grayagain.push(this);
        }
        // lgc.c: luaC_barrierback  -  old userdata referencing new value -> touched1, link in grayagain
        if (gcAge >= LuaValue.G_OLD && v.iscollectable() && v.gcAge == LuaValue.G_NEW) {
            gcAge = LuaValue.G_TOUCHED1;
            gcColor = 2;  // GRAY
            ownerGlobals.gc.grayagain.push(this);
        }
        uv[n - 1] = v;
        return true;
    }

    @Override
    public String toJavaString() {
        return udatamem != null ? udatamem.toString() : typeName();
    }

    // lvm.c: luaV_objlen (default branch for userdata)
    @Override
    public LuaValue len() {
        if (metatable != null) {
            LuaValue mm = metatable.rawget(LuaValue.LEN);
            if (!mm.isnil()) {
                return LuaCall.invoke(mm, this).arg1();
            }
        }
        return LuaErrors.error("attempt to get length of a " + typeName() + " value");
    }
}
