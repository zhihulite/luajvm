// ref: lobject.h (LUA_VLIGHTUD)
// diff: C用void*指针，Java用Object引用; C的pvalue(o)宏直接访问指针
package org.luajvm.core;

public class LuaLightUserdata extends LuaValue {
    public final Object pointer;

    public LuaLightUserdata(Object p) {
        super(LUA_VLIGHTUD);
        this.pointer = p;
    }

    // lobject.h: ttype
    @Override
    public int type() {
        return TLIGHTUSERDATA;
    }

    // ltm.h: ttypename
    @Override
    public String typeName() {
        return "light userdata";
    }

    // lobject.h: ttislightuserdata
    @Override
    public boolean islightuserdata() {
        return true;
    }

    // lobject.h: pvalue
    @Override
    public Object touserdata() {
        return pointer;
    }

    // rawEqualObj  -  同一指针对象才相等
    @Override
    public boolean raweq(LuaValue r) {
        return r instanceof LuaLightUserdata l && l.pointer == this.pointer;
    }

    // java-only
    @Override
    public int hashCode() {
        return System.identityHashCode(pointer);
    }

    // java:
    @Override
    public String toJavaString() {
        return "light userdata: " + Integer.toHexString(System.identityHashCode(pointer));
    }
}
