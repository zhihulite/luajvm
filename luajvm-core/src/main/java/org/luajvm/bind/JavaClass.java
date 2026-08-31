// java-only: Java类反射绑定
package org.luajvm.bind;

import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.spi.Loggers;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// java-only: 静态成员经 get()/set() 访问
public class JavaClass extends JavaObject implements Coercion.Adapter {
    private static final int MISSING_METHOD_CACHE_MAX = 64;
    // 进程级缓存只收 bootstrap/system/app loader 的类；自定义 loader 仍按状态缓存，避免泄漏。
    private static final Map<Class<?>, Map<String, List<Method>>> SHARED_METHOD_INDEX =
            new ConcurrentHashMap<>();
    /**
     * 进程级"简名 -> 内部类 Class"索引，分层判据同 {@link #SHARED_METHOD_INDEX}。
     * 值是纯 {@code Class}，不含 {@code JavaClass}、不携带 {@code Globals}，故可跨状态共享。
     * 见 {@link #getInnerClass}。
     */
    private static final Map<Class<?>, Map<LuaValue, Class<?>>> SHARED_INNER_CLASS_INDEX =
            new ConcurrentHashMap<>();
    /** A/B 开关：{@code -Dluajvm.bindinnerlazy=false} 回到"一次建全部内部类包装"的行为。 */
    private static final boolean INNER_LAZY =
            System.getProperty("luajvm.bindinnerlazy") == null
                    || Boolean.parseBoolean(System.getProperty("luajvm.bindinnerlazy"));
    static final HashMap<LuaValue, LuaValue> javaClassMethods = new HashMap<>();
    // 缓存挂在 Globals.javaClassCache（按状态）：JavaClass 是 LuaUserdata 且携带
    //   ownerGlobals，进程级缓存会让第二个 Globals 拿到另一状态已绑定的对象而抛
    //   "belongs to another Globals"。
    static final LuaValue NEW = valueOf("new");

    static {
        Method[] methodArray = Class.class.getMethods();
        for (Method method : methodArray) {
            javaClassMethods.put(LuaValue.valueOf(method.getName()), JavaMethod.forMethod(method));
        }
    }

    // java: 访问类型缓存，加速反射查找（键类型见 AccessType）
    // get 查找顺序：字段->方法->内部类->getter->map/list 取值
    final HashMap<LuaValue, AccessType> getAccessTypeCache = new HashMap<>();
    // member 查找顺序：方法->内部类->getter->字段->map/list 取值
    final HashMap<LuaValue, AccessType> memberAccessTypeCache = new HashMap<>();
    // set 查找顺序：字段->setter->setListener->map/list 赋值
    final HashMap<LuaValue, AccessType> setAccessTypeCache = new HashMap<>();
    // java-only: 被包装对象是否走集合取值/赋值语义（Map/List/LuaTable）。
    //   [按类而非按键]判定只依赖被包装对象的运行时类型，与 key 无关；JavaClass 正以
    //   udatamem.getClass() 为缓存键（见 forClass）⇒ 同一 JavaClass 下所有实例结论一致。
    //   [不得按键缓存]集合的 key 是用户数据的任意键，键空间无限——按 key 缓存会让
    //   条目数随互异键数单调增长且与 Globals 同寿、fullGC 不回落。
    //   [快路径]省去每次集合访问重走 Field/Method/InnerClass 三次查表与 getter 探测。
    boolean collectionAccess;
    private boolean collectionAccessComputed;
    final HashMap<LuaValue, LuaValue> cachedStaticFinals = new HashMap<>();
    final HashMap<LuaValue, LuaValue> cachedGetters = new HashMap<>();
    final HashMap<LuaValue, LuaValue> cachedSetters = new HashMap<>();
    Map<LuaValue, Field> fieldMap;
    Map<LuaValue, LuaValue> methodMap;
    // 按方法名保存反射重载，避免每个新名称重复遍历整张方法表。
    private volatile Map<String, List<Method>> methodIndex;
    private final Map<LuaValue, Boolean> missingMethodKeys = new LinkedHashMap<>(
            MISSING_METHOD_CACHE_MAX, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<LuaValue, Boolean> eldest) {
            return size() > MISSING_METHOD_CACHE_MAX;
        }
    };
    Map<LuaValue, JavaClass> innerClassMap;
    // 简名 -> 内部类 Class；INNER_LAZY 路径用（共享性见 SHARED_INNER_CLASS_INDEX）
    private volatile Map<LuaValue, Class<?>> innerClassIndex;

    JavaClass(Class<?> clazz) {
        super(clazz);
        this.javaClass = this;
    }

    /** 按状态缓存：JavaClass 携带 ownerGlobals，不可跨 Globals 复用。 */
    static JavaClass forClass(Globals g, Class<?> clazz) {
        if (g == null) return new JavaClass(clazz);
        LuaValue cached = g.javaClassCache.get(clazz);
        if (cached instanceof JavaClass jc) return jc;
        JavaClass jc = new JavaClass(clazz);
        g.javaClassCache.put(clazz, jc);
        return jc;
    }

    static JavaClass forName(Globals g, String className) throws ClassNotFoundException {
        if (g == null) return forClass(null, Class.forName(className));
        LuaValue cached = g.javaClassByNameCache.get(className);
        if (cached instanceof JavaClass jc) return jc;
        JavaClass jc = forClass(g, Class.forName(className));
        g.javaClassByNameCache.put(className, jc);
        return jc;
    }

    static JavaClass forName(Globals g, String className, ClassLoader loader)
            throws ClassNotFoundException {
        if (g == null) return forClass(null, Class.forName(className, true, loader));
        LuaValue cached = g.javaClassByNameCache.get(className);
        if (cached instanceof JavaClass jc) return jc;
        JavaClass jc = forClass(g, Class.forName(className, true, loader));
        g.javaClassByNameCache.put(className, jc);
        return jc;
    }

    @Override
    public Varargs call(Varargs args) {
        if (args.narg() == 1) {
            Class<?> obj = (Class<?>) touserdata();
            LuaValue arg = args.arg1();

            if (arg.istable()) {
                if (obj.isPrimitive()) {
                    return Coercion.toLua(new Coercion.ArrayCoercion(obj).coerce(arg));
                }
                if (obj.isInterface())
                    return JavaLib.forRunningGlobals().createProxy(new Class<?>[]{obj}, arg);
                if ((obj.getModifiers() & Modifier.ABSTRACT) != 0) {
                    try {
                        return JavaLib.forRunningGlobals().override(obj, arg);
                    } catch (Exception e) {
                        Loggers.get().e("JavaClass", "error", e);
                        LuaErrors.error(e);
                    }
                }
                if (Map.class.isAssignableFrom(obj))
                    return Coercion.toLua(new Coercion.ContainerCoercion(
                            Coercion.ContainerCoercion.Kind.MAP, obj).coerce(arg));

                if (List.class.isAssignableFrom(obj))
                    return Coercion.toLua(new Coercion.ContainerCoercion(
                            Coercion.ContainerCoercion.Kind.COLLECTION, obj).coerce(arg));
                try {
                    LuaValue ctor = get(NEW);
                    return callCtor(ctor, args).arg1();
                } catch (Exception e) {
                    return Coercion.toLua(new Coercion.ArrayCoercion(obj).coerce(arg));
                }
            }
            if (obj.isPrimitive()) {
                return new JavaObject(Coercion.toJava(arg, obj));
            }
        }

        LuaValue ctor = get(NEW);
        return callCtor(ctor, args).arg1();
    }

    /**
     * java-only：构造器包装的调用。{@code ctor} 必然是 {@code JavaConstructor} 或其
     * {@code Overload}（Java 实现的 {@code LuaFunction}），故默认走
     * {@code LuaCall.callJavaBinding} 直调，省掉一整层 Lua 调用帧
     * （理由与实测见 {@code LuaCall.callJavaBinding}）。
     */
    private static Varargs callCtor(LuaValue ctor, Varargs args) {
        if (InvokeSupport.FAST_CALL && ctor instanceof LuaFunction fn) {
            return LuaCall.callJavaBinding(fn, args);
        }
        return LuaCall.callLua(ctor, args);
    }

    @Override
    public Object coerce(LuaValue value) {
        return this;
    }

    /**
     * java-only: 被包装对象是否走集合语义，按类算一次后复用。
     *
     * <p>入参是被包装实例本身而非 {@code udatamem}：{@code JavaClass} 自己的
     * {@code udatamem} 是 {@code Class} 对象（不是集合），而其它 {@code JavaObject}
     * 的 {@code javaClass} 才指向以该实例类型为键的 {@code JavaClass}。
     */
    boolean isCollectionAccess(Object instance) {
        if (!collectionAccessComputed) {
            collectionAccess = instance instanceof Map
                    || instance instanceof List
                    || instance instanceof LuaTable;
            collectionAccessComputed = true;
        }
        return collectionAccess;
    }

    Field getField(LuaValue key) {
        if (fieldMap == null) {
            Map<LuaValue, Field> map = new HashMap<>();
            Field[] fieldArray = ((Class<?>) udatamem).getFields();
            for (int i = fieldArray.length - 1; i >= 0; i--) {
                Field fi = fieldArray[i];
                if (Modifier.isPublic(fi.getModifiers())) {
                    map.put(LuaValue.valueOf(fi.getName()), fi);
                    try {
                        if (!fi.isAccessible())
                            fi.setAccessible(true);
                    } catch (SecurityException ignored) {
                    }
                }
            }
            fieldMap = map;
        }
        return fieldMap.get(key);
    }

    public LuaValue getMethod(LuaValue key) {
        if (methodMap == null) {
            methodMap = new HashMap<>();
        }
        LuaValue cached = methodMap.get(key);
        if (cached != null) return cached;
        if (missingMethodKeys.containsKey(key)) return null;

        if (key.equals(NEW)) {
            LuaValue constructor = buildConstructors();
            if (constructor != null) methodMap.put(NEW, constructor);
            else rememberMissingMethod(key);
            return constructor;
        }

        ensureMethodIndex();
        List<Method> candidates = methodIndex.get(key.toJavaString());
        LuaValue result;
        if (candidates == null) {
            // Class.class 自身的方法（例如 bindClass(...):getName()）仍回退到共享表。
            result = javaClassMethods.get(key);
        } else {
            List<JavaMethod> overloads = new ArrayList<>(candidates.size());
            for (Method method : candidates) {
                JavaMethod javaMethod = JavaMethod.forMethod(method);
                if (javaMethod != null) overloads.add(javaMethod);
            }
            result = switch (overloads.size()) {
                // 目标类的方法均无法包装时回退到 Class.class 方法。
                case 0 -> javaClassMethods.get(key);
                case 1 -> overloads.get(0);
                default -> JavaMethod.forMethods(overloads.toArray(new JavaMethod[0]));
            };
        }
        if (result != null) methodMap.put(key, result);
        else rememberMissingMethod(key);
        return result;
    }

    private void rememberMissingMethod(LuaValue key) {
        missingMethodKeys.put(key, Boolean.TRUE);
    }

    // 首次访问方法时建立名称索引，后续只处理命中的重载。
    private void ensureMethodIndex() {
        if (methodIndex != null) return;
        Class<?> clazz = (Class<?>) udatamem;
        if (JavaMethod.cacheable(clazz)) {
            methodIndex = SHARED_METHOD_INDEX.computeIfAbsent(clazz, JavaClass::buildMethodIndex);
        } else {
            methodIndex = buildMethodIndex(clazz);
        }
    }

    private static Map<String, List<Method>> buildMethodIndex(Class<?> clazz) {
        Map<String, List<Method>> index = new HashMap<>();
        for (Method method : clazz.getMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                index.computeIfAbsent(method.getName(), ignored -> new ArrayList<>()).add(method);
            }
        }
        for (Map.Entry<String, List<Method>> entry : index.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(index);
    }

    private LuaValue buildConstructors() {
        Constructor<?>[] constructorArray = ((Class<?>) udatamem).getConstructors();
        if (constructorArray.length == 0)
            constructorArray = ((Class<?>) udatamem).getDeclaredConstructors();
        List<JavaConstructor> constructors = new ArrayList<>();
        for (Constructor<?> constructor : constructorArray) {
            if (!Modifier.isPublic(constructor.getModifiers())) continue;
            constructor.setAccessible(true);
            constructors.add(JavaConstructor.forConstructor(constructor));
        }
        return switch (constructors.size()) {
            case 0 -> null;
            case 1 -> constructors.get(0);
            default -> JavaConstructor.forConstructors(
                    constructors.toArray(new JavaConstructor[0]));
        };
    }

    /**
     * java-only：按需构建内部类包装。
     *
     * <p><b>只为真正被查到的名字建 {@code JavaClass}</b>：View 子类 public 内部类数量大，
     * 而每个 {@code JavaClass} 构造即分配多张 Map，且按 {@code Globals} 缓存 ⇒ 全量构建
     * 会让每个 Activity 重建一遍并长期挂在 {@code g.javaClassCache} 上。实际需求通常
     * 只有一个名字（{@code LuaLayout.load} 的 {@code viewClass.get("LayoutParams")}）。
     *
     * <p>故先建"简名 → Class"的纯元数据索引（沿链 {@code getDeclaredClasses()}）。索引不含
     * {@code JavaClass}、不携带 {@code Globals}，可按 {@link JavaMethod#cacheable} 分层进
     * 进程级共享（同 {@link #SHARED_METHOD_INDEX}）。
     *
     * <p>先到先得：沿继承链自子向父遍历，同名只保留首次遇到的
     * （即子类的内部类优先）。开关 {@code -Dluajvm.bindinnerlazy=false} 时全量构建。
     */
    JavaClass getInnerClass(LuaValue key) {
        if (!INNER_LAZY) return getInnerClassEager(key);
        ensureInnerClassIndex();
        Class<?> member = innerClassIndex.get(key);
        if (member == null) return null;
        if (innerClassMap == null) innerClassMap = new HashMap<>();
        JavaClass hit = innerClassMap.get(key);
        if (hit == null) {
            hit = forClass(owner(), member);
            innerClassMap.put(key, hit);
        }
        return hit;
    }

    /** 基线路径（{@code -Dluajvm.bindinnerlazy=false}）：一次把全部内部类包装建出来。 */
    private JavaClass getInnerClassEager(LuaValue key) {
        if (innerClassMap == null) {
            Map<LuaValue, JavaClass> map = new HashMap<>();
            for (Class<?> clazz = (Class<?>) udatamem; clazz != null; clazz = clazz.getSuperclass()) {
                for (Class<?> member : clazz.getDeclaredClasses()) {
                    if (Modifier.isPublic(member.getModifiers())) {
                        String name = member.getName();
                        String stub = name.substring(Math.max(name.lastIndexOf('$'), name.lastIndexOf('.')) + 1);
                        LuaString k = LuaValue.valueOf(stub);
                        if (!map.containsKey(k))
                            map.put(k, forClass(owner(), member));
                    }
                }
            }
            innerClassMap = map;
        }
        return innerClassMap.get(key);
    }

    private void ensureInnerClassIndex() {
        if (innerClassIndex != null) return;
        Class<?> clazz = (Class<?>) udatamem;
        if (JavaMethod.cacheable(clazz)) {
            innerClassIndex = SHARED_INNER_CLASS_INDEX.computeIfAbsent(
                    clazz, JavaClass::buildInnerClassIndex);
        } else {
            innerClassIndex = buildInnerClassIndex(clazz);
        }
    }

    private static Map<LuaValue, Class<?>> buildInnerClassIndex(Class<?> clazz) {
        Map<LuaValue, Class<?>> index = new HashMap<>();
        for (Class<?> k = clazz; k != null; k = k.getSuperclass()) {
            for (Class<?> member : k.getDeclaredClasses()) {
                if (Modifier.isPublic(member.getModifiers())) {
                    String name = member.getName();
                    String stub = name.substring(
                            Math.max(name.lastIndexOf('$'), name.lastIndexOf('.')) + 1);
                    LuaString mk = LuaValue.valueOf(stub);
                    // 先到先得：子类的同名内部类优先
                    if (!index.containsKey(mk)) index.put(mk, member);
                }
            }
        }
        return Collections.unmodifiableMap(index);
    }

    public LuaValue getConstructor() {
        return getMethod(NEW);
    }

    @Override
    public LuaValue get(LuaValue key) {
        if (key.isnumber())
            return Coercion.arrayCoercion.coerce(Array.newInstance((Class<?>) touserdata(), key.toint()));
        return switch (key.toJavaString()) {
            case "override" -> new JavaOverride(this);
            case "new" -> getMethod(key);
            case "array" -> new LuaFunction() {
                @Override
                public Varargs call(Varargs args) {
                    return Coercion.toLua(new Coercion.ArrayCoercion((Class<?>) udatamem).coerce(args.arg1()));
                }
            };
            case "class" -> this;
            default -> super.get(key);
        };
    }

    public static final class JavaOverride extends LuaFunction {
        private final Class<?> mClass;

        public JavaOverride(JavaClass javaClass) {
            mClass = javaClass.touserdata(Class.class);
        }

        @Override
        public LuaValue call(Varargs arg) {
            try {
                return JavaLib.forRunningGlobals().override(mClass, arg);
            } catch (Exception e) {
                Loggers.get().e("LuaJavaLib", "error", e);
                LuaErrors.error(e);
            }
            return LuaValue.NIL;
        }
    }

}
