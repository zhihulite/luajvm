// java-only: Java对象Lua包装
package org.luajvm.bind;

import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaUserdata;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// java-only: 实例字段与方法经 get()/set() 访问
public class JavaObject extends LuaUserdata {

    // Java 对象共享 metatable - VM 的 GETTABLE/GETFIELD 对非 table 走
    //   metamethod 路径（finishGet 不调用 Java 多态 get()），必须提供 __index/__newindex/__call
    //   转发到本类的 Java 方法。JavaCollection 已按此模式提供集合 metatable（见 JavaCollection.java）。
    static final LuaTable javaMetatable;
    private static final LuaFunction DEFAULT_JAVA_INDEX = new JavaIndexFn();
    private static final LuaFunction DEFAULT_JAVA_NEWINDEX = new JavaNewIndexFn();
    private static final LuaFunction DEFAULT_JAVA_CALL = new JavaCallFn();
    static final LuaValue CLASS = valueOf("class");

    static {
        javaMetatable = new LuaTable();
        javaMetatable.setEntry(INDEX, DEFAULT_JAVA_INDEX);
        javaMetatable.setEntry(NEWINDEX, DEFAULT_JAVA_NEWINDEX);
        javaMetatable.setEntry(CALL, DEFAULT_JAVA_CALL);
    }

    private final HashMap<LuaValue, LuaValue> methodCache = new HashMap<>();
    JavaClass javaClass;
    // java-only: 本实例的 Lua 侧附加字段（userdata 上 t.x = v 的落点）。
    //   按实例持有而非进程级 static registry：static 既是并发竞争点，
    //   又对每个包装对象持永久强引用而泄漏。
    //   包私有：JavaClass（类级附加字段的持有者）需读取。
    HashMap<LuaValue, LuaValue> extraValues;

    public JavaObject(Object instance) {
        super(instance);
        // java diff: 静态共享 javaMetatable 不经 setmetatable() 写入 this.metatable 字段，
        //   避免 LuaUserdata.bindGlobals 的 metatable 传播把它绑定到某个 Globals：
        //   javaMetatable 是进程级常量，不受任何 Globals 所有权约束，
        //   由 getmetatable() 覆写返回即可（LuaVM finishGet 经元方法路径调用 getmetatable()）。
    }

    // 覆写 tostring：返回 Java 对象内容（toJavaString()），而非基类的 NIL
    @Override
    public LuaValue tostring() {
        return LuaString.valueOf(toJavaString());
    }

    public Varargs call(Varargs args) {
        if (args.narg() == 1) {
            LuaValue arg = args.arg1();
            if (arg.istable()) {
                LuaValue key = LuaValue.NIL;
                Varargs next;
                while (!(next = arg.next(key)).isnil(1)) {
                    key = next.arg1();
                    set(key, next.arg(2));
                }
            }
        }
        return this;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Varargs next(LuaValue index) {
        if (udatamem instanceof Map map) {
            Set sets = map.keySet();
            Object key = Coercion.toJava(index, Object.class);
            for (Object set : sets) {
                if (key == null || key.equals(set)) {
                    return LuaValue.varargsOf(new LuaValue[]{Coercion.toLua(set), Coercion.toLua(map.get(set))});
                }
            }
        } else if (udatamem instanceof List list) {
            int idx = index.isnil() ? 0 : index.toint() + 1;
            if (idx >= list.size())
                return LuaValue.NIL;
            return LuaValue.varargsOf(new LuaValue[]{Coercion.toLua(idx), Coercion.toLua(list.get(idx))});
        } else if (udatamem instanceof Collection list) {
            int idx = index.isnil() ? 0 : index.toint() + 1;
            if (idx >= list.size())
                return LuaValue.NIL;
            return LuaValue.varargsOf(new LuaValue[]{Coercion.toLua(idx), Coercion.toLua(list.toArray()[idx])});
        }
        return super.next(index);
    }

    public Object invokeJavaMethod(String key, Object... args) {
        LuaValue m = getJavaMethod(LuaValue.valueOf(key));
        if (m instanceof JavaMethod.JavaOOMethod joom) {
            LuaValue[] vargs = new LuaValue[args.length];
            for (int i = 0; i < args.length; i++) vargs[i] = Coercion.toLua(args[i]);
            // JavaOOMethod 是 Java 实现的 LuaFunction，完整 Lua 帧是净开销
            if (InvokeSupport.FAST_CALL) {
                return LuaCall.callJavaBinding(joom, LuaValue.varargsOf(vargs));
            }
            return LuaCall.callLua(joom, LuaValue.varargsOf(vargs));
        }
        return null;
    }

    public LuaValue invokeJavaMethod(String key, Varargs args) {
        LuaValue m = getJavaMethod(LuaValue.valueOf(key));
        if (m instanceof JavaMethod.JavaOOMethod joom) {
            if (InvokeSupport.FAST_CALL) return LuaCall.callJavaBinding(joom, args).arg1();
            return LuaCall.callLua(joom, args).arg1();
        }
        return LuaValue.NIL;
    }

    public LuaValue getJavaMethod(String key) {
        return getJavaMethod(LuaValue.valueOf(key));
    }

    public LuaValue getJavaMethod(LuaValue key) {
        if (javaClass == null)
            javaClass = JavaClass.forClass(owner(), udatamem.getClass());
        LuaValue val = javaClass.cachedStaticFinals.get(key);
        if (val != null)
            return val;
        AccessType type = AccessType.of(javaClass.memberAccessTypeCache, key);

        if (type == null || type == AccessType.METHOD) {
            LuaValue m = methodOrBind(key, javaClass.memberAccessTypeCache, AccessType.METHOD);
            if (m != null) return m;
        }

        if (type == null || type == AccessType.INNER_CLASS) {
            LuaValue inner = innerClassOrNull(key, javaClass.memberAccessTypeCache);
            if (inner != null) return inner;
        }

        LuaValue collection = collectionValue(key);
        if (collection != null) return collection;

        if (type == null || type == AccessType.GETTER) {
            LuaValue g = getterValueOrNull(key, javaClass.memberAccessTypeCache);
            if (g != null) return g;
        }

        if (type == null || type == AccessType.GETFIELD) {
            LuaValue f = fieldValueOrNull(key, javaClass.memberAccessTypeCache, AccessType.GETFIELD);
            if (f != null) return f;
        }

        return tailLookup(key);
    }

    @SuppressWarnings("rawtypes")
    // LuaValue.get(String) 编译期绑定（静态类型 LuaValue），必须覆盖转发到
    //   get(LuaValue)，否则 `viewClass.get("LayoutParams")` 走 base rawget->typeError("table")
    @Override
    public LuaValue get(String k) {
        return get(LuaString.newStr(k));
    }

    public LuaValue get(LuaValue key) {
        if (javaClass == null)
            javaClass = JavaClass.forClass(owner(), udatamem.getClass());
        LuaValue val = javaClass.cachedStaticFinals.get(key);
        if (val != null)
            return val;
        AccessType type = AccessType.of(javaClass.getAccessTypeCache, key);

        if (type == null || type == AccessType.GETFIELD) {
            LuaValue f = fieldValueOrNull(key, javaClass.getAccessTypeCache, AccessType.GETFIELD);
            if (f != null) return f;
            LuaValue extrasValue = DebugFrameAccessor.getField(this, key);
            if (extrasValue != null) return extrasValue;
        }

        if (type == null || type == AccessType.METHOD) {
            LuaValue m = methodOrBind(key, javaClass.getAccessTypeCache, AccessType.METHOD);
            if (m != null) return m;
        }

        if (type == null || type == AccessType.INNER_CLASS) {
            LuaValue inner = innerClassOrNull(key, javaClass.getAccessTypeCache);
            if (inner != null) return inner;
        }

        LuaValue collection = collectionValue(key);
        if (collection != null) return collection;

        if (type == null || type == AccessType.GETTER) {
            LuaValue g = getterValueOrNull(key, javaClass.getAccessTypeCache);
            if (g != null) return g;
        }

        return tailLookup(key);
    }

    // LuaValue.set(String, LuaValue) 编译期绑定（静态类型 LuaValue），
    //   必须覆盖转发到 set(LuaValue, LuaValue)，否则走 base rawset->typeError("table")
    @Override
    public void set(String k, LuaValue v) {
        set(LuaString.newStr(k), v);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void set(LuaValue key, LuaValue value) {
        if (javaClass == null)
            javaClass = JavaClass.forClass(owner(), udatamem.getClass());
        AccessType type = AccessType.of(javaClass.setAccessTypeCache, key);
        if (type == null || type == AccessType.SETFIELD) {
            Field f = javaClass.getField(key);
            if (f != null) {
                AccessType.putIfAbsent(javaClass.setAccessTypeCache, key, AccessType.SETFIELD);
                try {
                    InvokeSupport.setField(f, udatamem, Coercion.toJava(value, f.getType()));
                    return;
                } catch (Exception e) {
                    LuaErrors.error(e);
                }
            }
            if (DebugFrameAccessor.setField(this, key, value)) return;
        }
        if (type == null || type == AccessType.SETTER) {
            LuaValue m = javaClass.cachedSetters.get(key);
            if (m == null) {
                String keyStr = key.toJavaString();
                String setterName = keyStr;
                if (Character.isLowerCase(setterName.charAt(0)))
                    setterName = Character.toUpperCase(setterName.charAt(0)) + setterName.substring(1);
                m = javaClass.getMethod(Coercion.toLua("set" + setterName));
            }
            if (m != null) {
                if (type == null) {
                    javaClass.cachedSetters.put(key, m);
                    javaClass.setAccessTypeCache.put(key, AccessType.SETTER);
                }
                // setter 可能有多重载（Overload），不能直接 cast JavaMethod
                if (m instanceof JavaMethod jm) {
                    jm.invokeJavaMethod(this, value);
                } else {
                    ((LuaFunction) m).call(Varargs.of(this, value));
                }
                return;
            }
        }
        if (type == null || type == AccessType.SETLISTENER) {
            String keyStr = key.toJavaString();
            if (keyStr.length() > 2 && keyStr.startsWith("on") && value.isfunction()) {
                if (javaSetListener(keyStr, value)) {
                    AccessType.putIfAbsent(javaClass.setAccessTypeCache, key, AccessType.SETLISTENER);
                    return;
                }
            }
        }

        // 集合赋值：判定按类缓存，不按 key（同 get()）。
        if (javaClass.isCollectionAccess(udatamem)) {
            if (udatamem instanceof Map map) {
                Coercion.toLua(map.put(Coercion.toJava(key, Object.class), Coercion.toJava(value, Object.class)));
                return;
            }
            if (udatamem instanceof List list) {
                Coercion.toLua(list.set(key.checkint(), Coercion.toJava(value, Object.class)));
                return;
            }
            if (udatamem instanceof LuaTable map) {
                map.set(key, value);
                return;
            }
        }

        if (extraValues == null) extraValues = new HashMap<>();
        extraValues.put(key, value);
    }

    /** java-only: 先查实例级 extras，再回落到 javaClass（类级）extras。null 表示未找到。 */
    private LuaValue lookupExtraValue(LuaValue key) {
        if (extraValues != null && extraValues.containsKey(key))
            return extraValues.get(key);
        if (javaClass != null && javaClass.extraValues != null
                && javaClass.extraValues.containsKey(key))
            return javaClass.extraValues.get(key);
        return null;
    }

    // ==================== 查找链的共享步骤 ====================
    // get()/getJavaMethod() 的差异只在步骤排列（见 JavaClass 头部注释），步骤实现共用。

    /** 方法步骤：methodCache 命中或经类方法表包装成绑 receiver 的 JavaOOMethod。 */
    private LuaValue methodOrBind(LuaValue key, HashMap<LuaValue, AccessType> cache, AccessType cacheType) {
        LuaValue m = methodCache.get(key);
        if (m != null) return m;
        m = javaClass.getMethod(key);
        if (m != null) {
            AccessType.putIfAbsent(cache, key, cacheType);
            // 绑定 receiver - Lua 点调用 obj.method(...) 不传 self，
            //   JavaOOMethod 同时处理点/冒号调用；get()/getJavaMethod() 共用 methodCache。
            m = new JavaMethod.JavaOOMethod(this, m);
            methodCache.put(key, m);
        }
        return m;
    }

    /** 内部类步骤：仅 Class 包装的实例；静态内部类同时落 cachedStaticFinals。 */
    private LuaValue innerClassOrNull(LuaValue key, HashMap<LuaValue, AccessType> cache) {
        if (!(udatamem instanceof Class)) return null;
        JavaClass innerClass = javaClass.getInnerClass(key);
        if (innerClass == null) return null;
        AccessType.putIfAbsent(cache, key, AccessType.INNER_CLASS);
        if (Modifier.isStatic(((Class<?>) innerClass.udatamem).getModifiers())) {
            javaClass.cachedStaticFinals.put(key, innerClass);
        }
        return innerClass;
    }

    /**
     * 集合取值：插在 getter 探测之前 - 成员查找（Field/Method/InnerClass）已走完，
     *   M:put()/L:add() 仍由 methodMap 命中；任意键在此直接返回，跳过最贵的
     *   getter 探测段（toJavaString + 首字母大写拼接 + get/is 两次 getMethod）。
     *   [判定按类不按键]key 是任意用户键，按键缓存无界增长（见 JavaClass.isCollectionAccess）。
     */
    private LuaValue collectionValue(LuaValue key) {
        if (!javaClass.isCollectionAccess(udatamem) || key.raweq(CLASS)) return null;
        if (udatamem instanceof Map<?, ?> map)
            return Coercion.toLua(map.get(Coercion.toJava(key, Object.class)));
        if (udatamem instanceof List<?> list)
            return Coercion.toLua(list.get(key.checkint()));
        if (udatamem instanceof LuaTable map)
            return map.get(key);
        return null;
    }

    /** getter 步骤：get/isXxx 探测并调用；"class" 键直接返回 Class。 */
    private LuaValue getterValueOrNull(LuaValue key, HashMap<LuaValue, AccessType> cache) {
        LuaValue m = javaClass.cachedGetters.get(key);
        if (m == null) {
            String keyStr = key.toJavaString();
            if (keyStr.equals("class"))
                return Coercion.toLua(udatamem.getClass());
            String getterName = keyStr;
            if (Character.isLowerCase(getterName.charAt(0)))
                getterName = Character.toUpperCase(getterName.charAt(0)) + getterName.substring(1);
            m = javaClass.getMethod(Coercion.toLua("get" + getterName));
            if (m == null)
                m = javaClass.getMethod(Coercion.toLua("is" + getterName));
        }
        if (m == null) return null;
        if (AccessType.of(cache, key) == null) {
            javaClass.cachedGetters.put(key, m);
            cache.put(key, AccessType.GETTER);
        }
        LuaValue ret;
        if (m instanceof JavaMethod jm) {
            ret = jm.invokeJavaMethod(this, LuaValue.NONE).arg1();
        } else {
            ret = ((LuaFunction) m).call(this).arg1();
        }
        if (ret.isuserdata(CharSequence.class))
            return ret.tostring();
        return ret;
    }

    /** 字段步骤：Field 读取；静态 final 常量同时落 cachedStaticFinals。 */
    private LuaValue fieldValueOrNull(LuaValue key, HashMap<LuaValue, AccessType> cache, AccessType cacheType) {
        Field f = javaClass.getField(key);
        if (f == null) return null;
        AccessType.putIfAbsent(cache, key, cacheType);
        try {
            LuaValue ret = Coercion.toLua(InvokeSupport.getField(f, udatamem));
            if (Modifier.isFinal(f.getModifiers()) && Modifier.isStatic(f.getModifiers())) {
                javaClass.cachedStaticFinals.put(key, ret);
            }
            return ret;
        } catch (Exception e) {
            LuaErrors.error(e);
            return null;
        }
    }

    /** 链尾：extras 附加字段 + "class" 键，未命中返回 NIL（Lua 语义，不报 typeError）。 */
    private LuaValue tailLookup(LuaValue key) {
        LuaValue extra = lookupExtraValue(key);
        if (extra != null) return extra;

        if (key.raweq(CLASS)) {
            javaClass.cachedStaticFinals.put(key, javaClass);
            return javaClass;
        }
        return LuaValue.NIL;
    }

    private boolean javaSetListener(String keyStr, LuaValue v) {
        String name = "setOn" + keyStr.substring(2) + "Listener";
        JavaMethod m = (JavaMethod) javaClass.getMethod(Coercion.toLua(name));
        if (m != null) {
            LuaTable t = new LuaTable();
            t.set(keyStr, v);
            Class<?>[] pt = m.method.getParameterTypes();
            // 优先使用 userdata 的所属状态；多个 Globals 同时存在且都未处于
            // Lua 执行区时，不能靠进程级 running 状态猜测代理所归属的状态。
            JavaLib owned = JavaLib.forGlobals(owner());
            JavaLib lib = owned != null ? owned : JavaLib.forRunningGlobals();
            m.invokeJavaMethod(this, lib.createProxy(new Class<?>[]{pt[0]}, t));
            return true;
        }
        return false;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public LuaValue len() {
        if (udatamem instanceof Map map) {
            return Coercion.toLua(map.size());
        }

        if (udatamem instanceof List list) {
            return Coercion.toLua(list.size());
        }
        return super.len();
    }

    @Override
    public LuaValue getmetatable() {
        if (metatable != null)
            return metatable;
        if (javaClass == null)
            javaClass = JavaClass.forClass(owner(), udatamem.getClass());
        if (javaClass != null && javaClass.metatable != null)
            return javaClass.metatable;
        // java diff: 返回进程级共享 javaMetatable（不写入 this.metatable，避免 bindGlobals 传播）
        return javaMetatable;
    }

    /**
     * C：lvm.c : luaV_finishget
     * Java 宿主优化：只有真实元表仍是未修改的 Java 默认转发表时，VM 才能直接调用
     * {@link #get(LuaValue)}。集合子类、实例/类级自定义元表以及被替换的共享转发函数
     * 都返回 false，继续走完整元方法协议。
     */
    public final boolean hasDefaultJavaIndex() {
        // getmetatable() 会在首次访问时解析类级元表；不能仅检查 javaClass 是否已初始化。
        return getmetatable() == javaMetatable
                && javaMetatable.rawget(INDEX) == DEFAULT_JAVA_INDEX;
    }

    /**
     * C：lvm.c : luaV_finishset
     * Java 宿主优化：确认默认 __newindex 转发未被覆盖后才允许 VM 直接调用 set()。
     */
    public final boolean hasDefaultJavaNewIndex() {
        return getmetatable() == javaMetatable
                && javaMetatable.rawget(NEWINDEX) == DEFAULT_JAVA_NEWINDEX;
    }

    /**
     * C：ldo.c : tryfuncTM
     * Java 宿主优化：确认默认 {@code __call} 转发未被覆盖后，宿主才能直调 {@link #call}。
     *
     * <p>完整路径是 {@code precall -> tryfuncTM}（查 {@code __call} 元方法 + 栈移位插入
     * self）{@code -> precallC(JavaCallFn) -> jo.call(args.subargs(2))}；确认转发未被
     * 改写后这一整层可以省掉，直接进 {@link #call}。见 {@link JavaCall#construct}。
     */
    public final boolean hasDefaultJavaCall() {
        return getmetatable() == javaMetatable
                && javaMetatable.rawget(CALL) == DEFAULT_JAVA_CALL;
    }

    public LuaValue metaTag(LuaValue tag) {
        LuaValue mt = getmetatable();
        if (mt == null) {
            if (extraValues != null && extraValues.containsKey(tag)) {
                return extraValues.get(tag);
            }
            // 实例上没有则回落到 JavaClass 上的类级 extras（javaClass 本身也是 JavaObject）
            if (javaClass != null && javaClass.extraValues != null
                    && javaClass.extraValues.containsKey(tag)) {
                return javaClass.extraValues.get(tag);
            }
            return LuaValue.NIL;
        }
        return mt.rawget(tag);
    }

    /**
     * __index：转发到 JavaObject.get（字段/方法/getter/嵌套类）。
     */
    private static final class JavaIndexFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue self = args.arg1();
            LuaValue key = args.arg(2);
            if (self instanceof JavaObject jo) return jo.get(key);
            return LuaValue.NIL;
        }
    }

    /**
     * __newindex：转发到 JavaObject.set（字段/setter）。
     */
    private static final class JavaNewIndexFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue self = args.arg1();
            if (self instanceof JavaObject jo) {
                jo.set(args.arg(2), args.arg(3));
            }
            return LuaValue.NONE;
        }
    }

    /**
     * __call：转发到 JavaObject.call/JavaClass.call（JavaClass(...) 构造实例）。
     */
    private static final class JavaCallFn extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue self = args.arg1();
            if (self instanceof JavaObject jo) return jo.call(args.subargs(2));
            return LuaValue.NIL;
        }
    }
}
