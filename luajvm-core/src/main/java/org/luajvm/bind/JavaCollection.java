// java-only: Lua 表式访问 Java 集合/数组/Map（无 C 对应）
//   继承 JavaObject 以获得字段/方法查找（get("add")/get("put")/get("size") 等）；
//   否则集合方法名索引 fallback 到 LuaValue.rawget -> typeError("table")，
//   List/Map 的方法调用（list.add/map.put）全废。
package org.luajvm.bind;

import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaUserdata;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class JavaCollection extends JavaObject {

    static final LuaValue LENGTH = valueOf("length");
    static final LuaValue CLASS = valueOf("class");
    static final LuaTable collection_metatable;

    static {
        collection_metatable = new LuaTable();
        collection_metatable.setEntry(LuaValue.LEN, new LenFunction());
        // java: __ipairs/__pairs使用字符串键（非TMS枚举）
        collection_metatable.setEntry(LuaValue.IPAIRS, new IPairsFunction());
        collection_metatable.setEntry(LuaValue.PAIRS, new PairsFunction());
        // __index 转发到 get()（Java 多态）：VM 对 userdata 索引要求 __index 元方法
        //   （对齐 C，finishGet 无 __index 报 "attempt to index"），不直接调重写的 get()，
        //   VM 核心保持 C 语义、不做 userdata fallback。
        collection_metatable.setEntry(LuaValue.INDEX, new IndexFunction());
        // __newindex 转发到 set() - Lua 对 Java 数组/List/Map 的写入
        //   （t[i]=v）经此到达重写的 set()（Array.set/List.set/Map.put）。
        collection_metatable.setEntry(LuaValue.NEWINDEX, new NewIndexFunction());
    }

    JavaCollection(Object instance) {
        super(instance);
        // java diff: collection_metatable 是进程级共享常量，不经 setmetatable() 写入字段，
        //   由 getmetatable() 覆写返回（同 JavaObject.javaMetatable 的处理方式）。
    }

    @Override
    public LuaValue getmetatable() {
        if (metatable != null) return metatable;
        return collection_metatable;
    }

    static JavaCollection wrap(Object instance) {
        return new JavaCollection(instance);
    }

    private boolean isArray() {
        return udatamem.getClass().isArray();
    }

    private boolean isList() {
        return udatamem instanceof List;
    }

    private boolean isMap() {
        return udatamem instanceof Map;
    }

    @Override
    public Varargs next(LuaValue index) {
        if (isArray()) {
            int len = Array.getLength(udatamem);
            int idx = index.isnil() ? 0 : index.toint() + 1;
            if (idx >= len)
                return LuaValue.NIL;
            return LuaValue.varargsOf(new LuaValue[]{Coercion.toLua(idx), Coercion.toLua(Array.get(udatamem, idx))});
        } else if (isList()) {
            List<?> list = (List<?>) udatamem;
            int idx = index.isnil() ? 0 : index.toint() + 1;
            if (idx >= list.size())
                return LuaValue.NIL;
            return LuaValue.varargsOf(new LuaValue[]{Coercion.toLua(idx + 1), Coercion.toLua(list.get(idx))});
        } else if (isMap()) {
            return LuaValue.NIL;
        }
        return LuaValue.NIL;
    }

    @Override
    public LuaValue get(LuaValue key) {
        if (key.equals(LENGTH)) {
            if (isArray())
                return valueOf(Array.getLength(udatamem));
            if (isList())
                return valueOf(((List<?>) udatamem).size());
            if (isMap())
                return valueOf(((Map<?, ?>) udatamem).size());
        }
        if (key.equals(CLASS) && isArray())
            return Coercion.toLua(udatamem.getClass());
        if (isArray()) {
            if (key.isinteger()) {
                int i = key.toint();
                return i >= 0 && i < Array.getLength(udatamem) ?
                        Coercion.toLua(Array.get(udatamem, i)) :
                        LuaValue.NIL;
            }
        } else if (isList()) {
            if (key.isinteger()) {
                int i = key.toint() - 1;
                List<?> list = (List<?>) udatamem;
                return i >= 0 && i < list.size() ?
                        Coercion.toLua(list.get(i)) :
                        LuaValue.NIL;
            }
        } else if (isMap()) {
            Map<?, ?> map = (Map<?, ?>) udatamem;
            Object javaKey = Coercion.toJava(key, Object.class);
            if (map.containsKey(javaKey))
                return Coercion.toLua(map.get(javaKey));
        }
        return super.get(key);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void set(LuaValue key, LuaValue value) {
        if (key.equals(LENGTH) && isMap())
            LuaErrors.error("cannot set length");
        else if (isArray()) {
            if (key.isinteger()) {
                int i = key.toint();
                if (i >= 0 && i < Array.getLength(udatamem))
                    Array.set(udatamem, i, Coercion.toJava(value, udatamem.getClass().getComponentType()));
                else if (metatable == null || metaTag(LuaValue.NEWINDEX).isnil())
                    LuaErrors.error("array index out of bounds");
            } else
                super.set(key, value);
        } else if (isList()) {
            if (key.isinteger()) {
                int i = key.toint() - 1;
                List<Object> list = (List<Object>) udatamem;
                if (i >= 0 && i < list.size())
                    list.set(i, Coercion.toJava(value, Object.class));
                else if (metatable == null || metaTag(LuaValue.NEWINDEX).isnil())
                    LuaErrors.error("list index out of bounds");
            } else
                super.set(key, value);
        } else if (isMap()) {
            ((Map<Object, Object>) udatamem).put(
                    Coercion.toJava(key, Object.class),
                    Coercion.toJava(value, Object.class));
        }
    }

    private static final class IndexFunction extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue self = args.arg1();
            LuaValue key = args.arg(2);
            return self.get(key);
        }
    }

    private static final class NewIndexFunction extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue self = args.arg1();
            LuaValue key = args.arg(2);
            LuaValue value = args.arg(3);
            self.set(key, value);
            return LuaValue.NIL;
        }
    }

    private static final class LenFunction extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            Object instance = ((LuaUserdata) args.arg1()).udatamem;
            if (instance.getClass().isArray())
                return LuaValue.valueOf(Array.getLength(instance));
            if (instance instanceof List<?> list)
                return LuaValue.valueOf(list.size());
            if (instance instanceof Map<?, ?> map)
                return LuaValue.valueOf(map.size());
            return LuaValue.valueOf(0);
        }
    }

    private static final class IPairsFunction extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            Object instance = ((LuaUserdata) args.arg1()).udatamem;
            if (instance.getClass().isArray()) {
                return varargsOf(new LuaFunction() {
                    @Override
                    public Varargs call(Varargs args) {
                        Object list = ((LuaUserdata) args.arg1()).udatamem;
                        int index = args.arg(2).toint();
                        if (index == Array.getLength(list)) return LuaValue.NIL;
                        return varargsOf(LuaValue.valueOf(index + 1), Coercion.toLua(Array.get(list, index)));
                    }
                }, args.arg1(), LuaValue.valueOf(0));
            } else if (instance instanceof List) {
                return varargsOf(new LuaFunction() {
                    @Override
                    public Varargs call(Varargs args) {
                        List<?> list = (List<?>) ((LuaUserdata) args.arg1()).udatamem;
                        int index = args.arg(2).toint();
                        if (index >= list.size()) return LuaValue.NIL;
                        return varargsOf(LuaValue.valueOf(index + 1), Coercion.toLua(list.get(index)));
                    }
                }, args.arg1(), LuaValue.valueOf(0));
            }
            return LuaValue.NIL;
        }
    }

    @SuppressWarnings("PatternVariableCanBeUsed")
    private static final class PairsFunction extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            Object instance = ((LuaUserdata) args.arg1()).udatamem;
            if (instance.getClass().isArray()) {
                return varargsOf(new LuaFunction() {
                    @Override
                    public Varargs call(Varargs args) {
                        Object list = ((LuaUserdata) args.arg1()).udatamem;
                        int index = args.arg(2).toint();
                        if (index == Array.getLength(list)) return LuaValue.NIL;
                        return varargsOf(LuaValue.valueOf(index + 1), Coercion.toLua(Array.get(list, index)));
                    }
                }, args.arg1(), LuaValue.valueOf(0));
            } else if (instance instanceof List) {
                return varargsOf(new LuaFunction() {
                    @Override
                    public Varargs call(Varargs args) {
                        List<?> list = (List<?>) ((LuaUserdata) args.arg1()).udatamem;
                        int index = args.arg(2).toint();
                        if (index >= list.size()) return LuaValue.NIL;
                        return varargsOf(LuaValue.valueOf(index + 1), Coercion.toLua(list.get(index)));
                    }
                }, args.arg1(), LuaValue.valueOf(0));
            } else if (instance instanceof Map<?, ?> map) {
                final List<Object> keys = new ArrayList<>(map.keySet());
                return varargsOf(new LuaFunction() {
                    @Override
                    public Varargs call(Varargs args) {
                        int index = args.arg(2).isnil() ? 0 : keys.indexOf(Coercion.toJava(args.arg(2), Object.class)) + 1;
                        if (index <= 0 || index >= keys.size()) return LuaValue.NIL;
                        Object key = keys.get(index);
                        return varargsOf(Coercion.toLua(key), Coercion.toLua(map.get(key)));
                    }
                }, args.arg1(), LuaValue.NIL);
            }
            return LuaValue.NIL;
        }
    }
}
