// java-only: Lua/Java 类型强制转换
package org.luajvm.bind;

/*
  Java/Lua 双向类型强制转换引擎。

  <p>Java 特有：C 无对应。C 通过栈直接传递值，Java 须显式桥接类型。
 */

import org.luajvm.core.Globals;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.spi.Loggers;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Coercion {

    public static final int SCORE_NULL_VALUE = 0x10;
    public static final int SCORE_NUM_WRONG_TYPE = 0x20;
    public static final int SCORE_INT_WRONG_TYPE = 0x80;
    public static final int SCORE_WRONG_TYPE = 0x100;
    public static final int SCORE_UNCOERCIBLE = 0x10000;
    static final JavaToLuaCoercion arrayCoercion = new ArrayToLuaCoercion();

    // ===================================================================
    // java-only: 两张 Class->Adapter 缓存按 ClassLoader 可回收性分两层，
    //            起因是实测到的 ClassLoader 泄漏。
    //
    // 问题：进程级只增不减的 Class->Adapter 表，键是 Class；Class 强引用其
    //   ClassLoader => 收下自定义 loader（Android 热重载/插件化）加载的类后，
    //   该 loader 及其全部类永久无法回收。WeakHashMap 救不了：ObjectCoercion 等
    //   是 record，targetType 字段就是键，而 WeakHashMap 的 entry 对值持强引用
    //   => 键恒可达（Javadoc 明确警告此形态）。
    //
    // 分层判据 JavaMethod.cacheable(c)：loader 为 null（bootstrap）或 platform/app
    //   loader——与进程同生命周期，强引用无害。
    //   第一层（可缓存）-> 无锁 ConcurrentHashMap，覆盖基元类、Object.class、
    //     全部 JDK 类及宿主应用自身的类；
    //   第二层（自定义 loader）-> 弱键 + 弱值，取锁，仅热重载/插件化场景命中。
    //
    // 第二层取锁可接受：toJava 热路径只传 Object.class 与基元类（全在第一层）；
    //   其他类仅经 MemberSupport.ParamInfo 构造进入，ParamInfo 把 Adapter 存进
    //   自己的字段 => 每方法一次，是冷路径。
    // ===================================================================

    /** loader 不可回收的类（基元/装箱/Object/JDK/宿主应用类）-> Adapter。强持无害。 */
    static final Map<Object, Object> LUA_TO_JAVA_COERCIONS = new ConcurrentHashMap<>();
    /** 同上，java->lua 方向。 */
    private static final Map<Class<?>, JavaToLuaCoercion> JAVA_TO_LUA_COERCIONS = new ConcurrentHashMap<>();

    /**
     * 自定义 loader 类的 lua->java 适配器缓存：弱键 + 弱值。
     *
     * <p>值必须再包一层 {@link WeakReference}：适配器持有目标 {@code Class}（即键），
     * 若值被强持，{@code WeakHashMap} 的键就恒可达、永不回收。包弱引用后环被打断。
     * 适配器被回收仅是缓存未命中（它无状态，重建等价），不影响正确性。
     */
    private static final Map<Class<?>, WeakReference<Adapter>> L2J_USER = new WeakHashMap<>();
    /** 自定义 loader 类的 java->lua 适配器缓存。值是共享无状态单例、不反引键，故可强持。 */
    private static final Map<Class<?>, JavaToLuaCoercion> J2L_USER = new WeakHashMap<>();
    // java-only: ReentrantLock 而非 synchronized - 避免虚拟线程阻塞时 pin carrier
    //   （与 LuaString.Intern.LOCK、LuaThread 同一约定）。
    private static final ReentrantLock USER_LOCK = new ReentrantLock();
    private static final JavaToLuaCoercion instanceCoercion = new InstanceCoercion();
    private static final JavaToLuaCoercion arrayToLuaCoercion = new ArrayToLuaCoercion();
    private static final JavaToLuaCoercion luaCoercion = new LuaCoercion();

    static {
        JavaToLuaCoercion boolCoercion = new BoolCoercion();
        JavaToLuaCoercion intCoercion = new IntCoercion();
        JavaToLuaCoercion longCoercion = new LongCoercion();
        JavaToLuaCoercion charCoercion = new CharCoercion();
        JavaToLuaCoercion doubleCoercion = new DoubleCoercion();
        JavaToLuaCoercion stringCoercion = new JStringCoercion();
        JavaToLuaCoercion classCoercion = new ClassCoercion();
        JAVA_TO_LUA_COERCIONS.put(Boolean.class, boolCoercion);
        JAVA_TO_LUA_COERCIONS.put(Byte.class, intCoercion);
        JAVA_TO_LUA_COERCIONS.put(Character.class, charCoercion);
        JAVA_TO_LUA_COERCIONS.put(Short.class, intCoercion);
        JAVA_TO_LUA_COERCIONS.put(Integer.class, intCoercion);
        JAVA_TO_LUA_COERCIONS.put(Long.class, longCoercion);
        JAVA_TO_LUA_COERCIONS.put(Float.class, doubleCoercion);
        JAVA_TO_LUA_COERCIONS.put(Double.class, doubleCoercion);
        JAVA_TO_LUA_COERCIONS.put(String.class, stringCoercion);
        JAVA_TO_LUA_COERCIONS.put(Class.class, classCoercion);
        // byte[] 不注册出方向映射：与 int[]/String[] 等一切数组一致，落到
        //   arrayToLuaCoercion 包成 JavaCollection（userdata，Lua 式索引/长度）。
        //   保持 Java 引用语义（可写、instanceof、重载分派），需要字节串时脚本侧显式转换。
    }

    static {
        Adapter boolCoercion = new LuaBoolCoercion();
        Adapter byteCoercion = new NumericCoercion(NumericTarget.BYTE);
        Adapter charCoercion = new NumericCoercion(NumericTarget.CHAR);
        Adapter shortCoercion = new NumericCoercion(NumericTarget.SHORT);
        Adapter intCoercion = new NumericCoercion(NumericTarget.INT);
        Adapter longCoercion = new NumericCoercion(NumericTarget.LONG);
        Adapter floatCoercion = new NumericCoercion(NumericTarget.FLOAT);
        Adapter doubleCoercion = new NumericCoercion(NumericTarget.DOUBLE);
        Adapter stringCoercion = new LuaStringCoercion(LuaStringCoercion.TARGET_TYPE_STRING);

        LUA_TO_JAVA_COERCIONS.put(Boolean.TYPE, boolCoercion);
        LUA_TO_JAVA_COERCIONS.put(Boolean.class, boolCoercion);
        LUA_TO_JAVA_COERCIONS.put(Byte.TYPE, byteCoercion);
        LUA_TO_JAVA_COERCIONS.put(Byte.class, byteCoercion);
        LUA_TO_JAVA_COERCIONS.put(Character.TYPE, charCoercion);
        LUA_TO_JAVA_COERCIONS.put(Character.class, charCoercion);
        LUA_TO_JAVA_COERCIONS.put(Short.TYPE, shortCoercion);
        LUA_TO_JAVA_COERCIONS.put(Short.class, shortCoercion);
        LUA_TO_JAVA_COERCIONS.put(Integer.TYPE, intCoercion);
        LUA_TO_JAVA_COERCIONS.put(Integer.class, intCoercion);
        LUA_TO_JAVA_COERCIONS.put(Long.TYPE, longCoercion);
        LUA_TO_JAVA_COERCIONS.put(Long.class, longCoercion);
        LUA_TO_JAVA_COERCIONS.put(Float.TYPE, floatCoercion);
        LUA_TO_JAVA_COERCIONS.put(Float.class, floatCoercion);
        LUA_TO_JAVA_COERCIONS.put(Double.TYPE, doubleCoercion);
        LUA_TO_JAVA_COERCIONS.put(Double.class, doubleCoercion);
        LUA_TO_JAVA_COERCIONS.put(String.class, stringCoercion);
        // java-only: Object.class 必须预置在第一层。它是 toJava(v, Object.class) 的
        //   实参（JavaCollection/JavaObject 的每次索引/赋值都走），掉进第二层
        //   会让最热的路径每次取锁。Object 是 bootstrap 类，强持无泄漏风险。
        LUA_TO_JAVA_COERCIONS.put(Object.class, new ObjectCoercion(Object.class));
    }

    /**
     * 无状态入口：Class 值从进程级运行状态推断归属（见 {@link ClassCoercion}）。
     * 调用方持有目标 Globals/userdata 归属时应改用 {@link #toLua(Globals, Object)}，
     * 推断在多 Globals 并存时会选错状态。
     */
    public static LuaValue toLua(Object o) {
        return toLuaInternal(null, o);
    }

    /**
     * 带状态入口：Class 值经 g 包装 JavaClass（按 g 缓存）；g 为 null 时回落运行状态
     * 推断（与 {@link #toLua(Object)} 同行为）。其余类型与无状态入口完全一致。
     */
    public static LuaValue toLua(Globals g, Object o) {
        return toLuaInternal(g, o);
    }

    private static LuaValue toLuaInternal(Globals g, Object o) {
        if (o instanceof Class<?> clazz)
            return JavaClass.forClass(g != null ? g : LuaTable.runningGlobalsForGC(), clazz);
        switch (o) {
            case null -> {
                return LuaValue.NIL;
            }
            case Map<?, ?> map -> {
                return JavaCollection.wrap(map);
            }
            case List<?> list -> {
                return JavaCollection.wrap(list);
            }
            default -> {
            }
        }
        Class<?> clazz = o.getClass();

        // 第一层：loader 不可回收的类，无锁
        JavaToLuaCoercion c = JAVA_TO_LUA_COERCIONS.get(clazz);
        if (c == null) {
            JavaToLuaCoercion fresh = clazz.isArray() ? arrayToLuaCoercion :
                    o instanceof LuaValue ? luaCoercion :
                    instanceCoercion;
            if (JavaMethod.cacheable(clazz)) {
                JAVA_TO_LUA_COERCIONS.put(clazz, fresh);
                c = fresh;
            } else {
                // 第二层：自定义 loader 的类，弱键（值是共享单例、不反引键，故可强持）
                USER_LOCK.lock();
                try {
                    c = J2L_USER.get(clazz);
                    if (c == null) J2L_USER.put(clazz, c = fresh);
                } finally {
                    USER_LOCK.unlock();
                }
            }
        }
        return c.coerce(o);
    }

    public static Object toJava(LuaValue value, Class<?> clazz) {
        return getCoercion(clazz).coerce(value);
    }

    public static Object arrayCoerce(LuaValue value, Class<?> clazz) {
        return new ArrayCoercion(clazz).coerce(value);
    }

    static int inheritanceLevels(Class<?> baseclass, Class<?> subclass) {
        if (subclass == null)
            return SCORE_UNCOERCIBLE;
        if (baseclass == subclass)
            return 0;
        int min = Math.min(SCORE_UNCOERCIBLE, inheritanceLevels(baseclass, subclass.getSuperclass()) + 1);
        Class<?>[] ifaces = subclass.getInterfaces();
        for (Class<?> iface : ifaces) min = Math.min(min, inheritanceLevels(baseclass, iface) + 1);
        return min;
    }

    /**
     * 给一组形参类型对一批实参打分：**越小越匹配**，{@code >= SCORE_UNCOERCIBLE}
     * 表示这组实参无法强转到该签名。
     *
     * <p>匹配统一走各 Adapter 的 {@code score}：基元形参不能走 {@code isInstance}
     * （{@code int.class} 等基元类型上恒为 false，{@code Class.isInstance} 的规定）；
     * Lua 数值到 {@code byte/char/short} 的窄化、数字字符串、userdata 装箱数各有
     * 分数规则（见 {@link NumericCoercion#score}）。
     *
     * @param paramTypes 形参类型，长度须等于参与打分的实参个数
     * @param args       实参列表
     * @param off        {@code paramTypes[0]} 对应的实参下标（1 基，即 {@code args.arg(off)}）
     */
    public static int scoreParams(Class<?>[] paramTypes, Varargs args, int off) {
        int score = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            int part = getCoercion(paramTypes[i]).score(args.arg(i + off));
            if (part >= SCORE_UNCOERCIBLE) return SCORE_UNCOERCIBLE;
            score += part;
        }
        return score;
    }

    public static Adapter getCoercion(Class<?> c) {
        // 第一层：loader 不可回收的类（bootstrap/platform/app），无锁。
        Adapter co = (Adapter) LUA_TO_JAVA_COERCIONS.get(c);
        if (co != null) {
            return co;
        }
        if (JavaMethod.cacheable(c)) {
            co = newCoercion(c);
            LUA_TO_JAVA_COERCIONS.put(c, co);
            return co;
        }
        // 第二层：自定义 loader 的类。弱键 + 弱值，故其 ClassLoader 仍可回收。
        USER_LOCK.lock();
        try {
            WeakReference<Adapter> ref = L2J_USER.get(c);
            if (ref != null) {
                co = ref.get();
                if (co != null) return co;
            }
            co = newCoercion(c);
            L2J_USER.put(c, new WeakReference<>(co));
            return co;
        } finally {
            USER_LOCK.unlock();
        }
    }

    private static Adapter newCoercion(Class<?> c) {
        if (c == byte[].class) {
            // Lua 字符串**就是字节串**，byte[] 形参必须同时接受字符串与表；
            //   泛用的 ArrayCoercion 只认表与 userdata（见 BytesParamCoercion 的说明）。
            return new BytesParamCoercion();
        }
        if (c.isArray()) {
            return new ArrayCoercion(c.getComponentType());
        } else if (Map.class.isAssignableFrom(c)) {
            return new ContainerCoercion(ContainerCoercion.Kind.MAP, c);
        } else if (Collection.class.isAssignableFrom(c)) {
            return new ContainerCoercion(ContainerCoercion.Kind.COLLECTION, c);
        } else {
            return new ObjectCoercion(c);
        }
    }

    /**
     * {@code byte[]} 形参专用适配器：Lua 字符串与 Lua 表都能喂。
     *
     * <p>{@code ArrayCoercion} 只认表与 userdata，字符串一律
     * {@code SCORE_UNCOERCIBLE}，故 {@code MessageDigest.digest(byte[])}、
     * {@code OutputStream.write(byte[])} 这类只能靠字符串喂的签名
     * 须走 {@code LuaStringCoercion(TARGET_TYPE_BYTES)}。
     *
     * <p><b>字符串加 1 分</b>：同一个 Lua 字符串对 {@code String} 形参得 0 分，对
     * {@code byte[]} 得 1 分，保证 {@code f(String)} / {@code f(byte[])} 重载不会因为
     * 同分而退化成"看声明顺序"。
     *
     * <p>{@code coerce} 与 {@code score} 用同一条判据挑分支，二者不会不一致。
     */
    private static final class BytesParamCoercion implements Adapter {
        private final Adapter str = new LuaStringCoercion(LuaStringCoercion.TARGET_TYPE_BYTES);
        private final Adapter arr = new ArrayCoercion(Byte.TYPE);

        public String toString() {
            return "BytesParamCoercion(byte[])";
        }

        /** 字符串路径的分数：可强转时加 1，使 {@code String} 形参严格更优。 */
        private int strScore(LuaValue value) {
            int s = str.score(value);
            return s < SCORE_UNCOERCIBLE ? s + 1 : s;
        }

        public int score(LuaValue value) {
            return Math.min(arr.score(value), strScore(value));
        }

        public Object coerce(LuaValue value) {
            if (value.isnil()) return null;
            return arr.score(value) <= strScore(value) ? arr.coerce(value) : str.coerce(value);
        }
    }

    public interface Adapter {
        Object coerce(LuaValue value);

        default int score(LuaValue value) {
            return SCORE_UNCOERCIBLE;
        }
    }

    public interface JavaToLuaCoercion {
        LuaValue coerce(Object javaValue);
    }

    private static final class BoolCoercion implements JavaToLuaCoercion {
        public LuaValue coerce(Object javaValue) {
            Boolean b = (Boolean) javaValue;
            return b ? LuaValue.TRUE : LuaValue.FALSE;
        }
    }

    private static final class IntCoercion implements JavaToLuaCoercion {
        public LuaValue coerce(Object javaValue) {
            Number n = (Number) javaValue;
            return LuaInteger.valueOf(n.intValue());
        }
    }

    private static final class LongCoercion implements JavaToLuaCoercion {
        public LuaValue coerce(Object javaValue) {
            Number n = (Number) javaValue;
            return LuaInteger.valueOf(n.longValue());
        }
    }

    private static final class CharCoercion implements JavaToLuaCoercion {
        public LuaValue coerce(Object javaValue) {
            Character c = (Character) javaValue;
            return LuaInteger.valueOf(c);
        }
    }

    private static final class DoubleCoercion implements JavaToLuaCoercion {
        public LuaValue coerce(Object javaValue) {
            Number n = (Number) javaValue;
            return LuaFloat.valueOf(n.doubleValue());
        }
    }

    private static final class JStringCoercion implements JavaToLuaCoercion {
        public LuaValue coerce(Object javaValue) {
            return LuaString.newStr(javaValue.toString());
        }
    }

    private static final class ClassCoercion implements JavaToLuaCoercion {
        public LuaValue coerce(Object javaValue) {
            // JavaClass 按状态缓存；此入口无状态参数，取当前运行状态
            return JavaClass.forClass(LuaTable.runningGlobalsForGC(), (Class<?>) javaValue);
        }
    }

    private static final class InstanceCoercion implements JavaToLuaCoercion {
        public LuaValue coerce(Object javaValue) {
            return new JavaObject(javaValue);
        }
    }

    private static final class ArrayToLuaCoercion implements JavaToLuaCoercion {
        public LuaValue coerce(Object javaValue) {
            return JavaCollection.wrap(javaValue);
        }
    }

    private static final class LuaCoercion implements JavaToLuaCoercion {
        public LuaValue coerce(Object javaValue) {
            return (LuaValue) javaValue;
        }
    }

    static final class LuaBoolCoercion implements Adapter {
        public String toString() {
            return "BoolCoercion()";
        }

        public int score(LuaValue value) {
            return switch (value.type()) {
                case LuaValue.TNIL -> 32;
                case LuaValue.TBOOLEAN -> 0;
                default -> SCORE_UNCOERCIBLE;
            };
        }

        public Object coerce(LuaValue value) {
            return value.toboolean() ? Boolean.TRUE : Boolean.FALSE;
        }
    }

    /** 数值目标的窄化/装箱语义，按目标类型参数化评分与转换。 */
    enum NumericTarget {
        BYTE {
            int scoreInteger(long v) {
                return (v == (byte) v) ? 1 : SCORE_WRONG_TYPE;
            }

            int scoreNumber(double d) {
                // 值域内非整数值允许截断（对齐老引擎全 double 的宽松语义）；NaN/越界不可转。
            //   截断罚 1 分，仍让位于整数值与精确类型匹配
                if (d != d || d < Byte.MIN_VALUE || d > Byte.MAX_VALUE) {
                    return SCORE_WRONG_TYPE + SCORE_NUM_WRONG_TYPE;
                }
                return ((d == (byte) d) ? 0 : 1) + SCORE_NUM_WRONG_TYPE;
            }

            boolean matchesBoxed(Class<?> cls) {
                return cls == Byte.class || cls == Byte.TYPE;
            }

            Object convert(Number n) {
                return n.byteValue();
            }

            Object convertLua(LuaValue v) {
                return (byte) v.toint();
            }
        },
        CHAR {
            // char 给 2 而非 1：小于 int 的 2 -> append(123) 错配 append(char '{')
            //   （123 = ASCII '{'）
            int scoreInteger(long v) {
                return (v >= Character.MIN_VALUE && v <= Character.MAX_VALUE) ? 2 : SCORE_WRONG_TYPE;
            }

            int scoreNumber(double d) {
                // 值域内非整数值允许截断（对齐老引擎全 double 的宽松语义）；NaN/越界不可转。
            //   截断罚 1 分，仍让位于整数值与精确类型匹配
                if (d != d || d < Character.MIN_VALUE || d > Character.MAX_VALUE) {
                    return SCORE_WRONG_TYPE + SCORE_NUM_WRONG_TYPE;
                }
                return ((d == (char) d) ? 2 : 3) + SCORE_NUM_WRONG_TYPE;
            }

            boolean matchesBoxed(Class<?> cls) {
                return cls == Character.class || cls == Character.TYPE;
            }

            Object convert(Number n) {
                return (char) n.intValue();
            }

            Object convertLua(LuaValue v) {
                return (char) v.toint();
            }
        },
        SHORT {
            int scoreInteger(long v) {
                return (v == (short) v) ? 1 : SCORE_WRONG_TYPE;
            }

            int scoreNumber(double d) {
                // 值域内非整数值允许截断（对齐老引擎全 double 的宽松语义）；NaN/越界不可转。
            //   截断罚 1 分，仍让位于整数值与精确类型匹配
                if (d != d || d < Short.MIN_VALUE || d > Short.MAX_VALUE) {
                    return SCORE_WRONG_TYPE + SCORE_NUM_WRONG_TYPE;
                }
                return ((d == (short) d) ? 0 : 1) + SCORE_NUM_WRONG_TYPE;
            }

            boolean matchesBoxed(Class<?> cls) {
                return cls == Short.class || cls == Short.TYPE;
            }

            Object convert(Number n) {
                return n.shortValue();
            }

            Object convertLua(LuaValue v) {
                return (short) v.toint();
            }
        },
        INT {
            // int 值域内的 Lua 整数零损耗直配，score 0（最优）；超域（Lua 整数是 64 位）
            //   不可截断匹配，罚到 WRONG_TYPE 让位 long/精确重载
            int scoreInteger(long v) {
                return (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) ? 0 : SCORE_WRONG_TYPE;
            }

            int scoreNumber(double d) {
                // 值域内非整数值允许截断（对齐老引擎全 double 的宽松语义）；NaN/越界不可转。
            //   截断罚 1 分，仍让位于整数值与精确类型匹配
                if (d != d || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                    return SCORE_WRONG_TYPE + SCORE_NUM_WRONG_TYPE;
                }
                return ((d == (int) d) ? 0 : 1) + SCORE_NUM_WRONG_TYPE;
            }

            boolean matchesBoxed(Class<?> cls) {
                return cls == Integer.class || cls == Integer.TYPE;
            }

            Object convert(Number n) {
                return n.intValue();
            }

            Object convertLua(LuaValue v) {
                return v.toint();
            }
        },
        LONG {
            // long 给 3：int 目标零损耗在先，等价装箱须让位（防 "s" 错配 StringBuilder(long)）
            int scoreInteger(long v) {
                return 3;
            }

            int scoreNumber(double d) {
                // 值域内非整数值允许截断（对齐老引擎全 double 的宽松语义）；NaN/越界不可转。
            //   截断罚 1 分，仍让位于整数值与精确类型匹配
                if (d != d || d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
                    return SCORE_WRONG_TYPE + SCORE_NUM_WRONG_TYPE;
                }
                return ((d == (long) d) ? 0 : 1) + SCORE_NUM_WRONG_TYPE;
            }

            boolean matchesBoxed(Class<?> cls) {
                return cls == Long.class || cls == Long.TYPE;
            }

            Object convert(Number n) {
                return n.longValue();
            }

            Object convertLua(LuaValue v) {
                return v.tolong();
            }
        },
        FLOAT {
            int scoreInteger(long v) {
                return SCORE_INT_WRONG_TYPE;
            }

            int scoreNumber(double d) {
                // NaN 是合法 float 值（== 比较恒 false），须显式放行；超 float 精度域仍拒
                if (d != d) return 0;
                return (d == (float) d) ? 0 : SCORE_WRONG_TYPE;
            }

            boolean matchesBoxed(Class<?> cls) {
                return cls == Float.class || cls == Float.TYPE;
            }

            Object convert(Number n) {
                return n.floatValue();
            }

            Object convertLua(LuaValue v) {
                return (float) v.todouble();
            }
        },
        DOUBLE {
            int scoreInteger(long v) {
                return SCORE_INT_WRONG_TYPE;
            }

            // 整值或 float 精确值给 1：与 int 路径区分，避免 long-可表整数的错位倾向
            int scoreNumber(double d) {
                return ((d == (long) d) || (d == (float) d)) ? 1 : 0;
            }

            boolean matchesBoxed(Class<?> cls) {
                return cls == Double.class || cls == Double.TYPE;
            }

            Object convert(Number n) {
                return n.doubleValue();
            }

            Object convertLua(LuaValue v) {
                return v.todouble();
            }
        };

        abstract int scoreInteger(long v);

        abstract int scoreNumber(double d);

        abstract boolean matchesBoxed(Class<?> cls);

        abstract Object convert(Number n);

        abstract Object convertLua(LuaValue v);
    }

    record NumericCoercion(NumericTarget target) implements Adapter {

        public String toString() {
            return "NumericCoercion(" + target + ")";
        }

        public int score(LuaValue value) {
            // 字符串不做隐式数值转换：数字字符串应作为字符串精确匹配 String 形参
            if (value.type() == LuaValue.TSTRING) {
                return SCORE_UNCOERCIBLE;
            }
            if (value.isinteger()) {
                return target.scoreInteger(value.tolong());
            }
            if (value.isnumber()) {
                return target.scoreNumber(value.todouble());
            }
            if (value.isuserdata()) {
                return target.matchesBoxed(value.touserdata().getClass()) ? 0 : SCORE_UNCOERCIBLE;
            }
            return SCORE_UNCOERCIBLE;
        }

        public Object coerce(LuaValue value) {
            if (value.isuserdata()) {
                Number n = value.touserdata(Number.class);
                // java-only: 非 Number 的 userdata 取不到数字，返回 null 由调用方判失败；
                //   直接 n.xxxValue() 会在 coerce 内 NPE，根因看不出来
                if (n == null) return null;
                return target.convert(n);
            }
            return target.convertLua(value);
        }
    }

    record LuaStringCoercion(int targetType) implements Adapter {
        public static final int TARGET_TYPE_STRING = 0;
        public static final int TARGET_TYPE_BYTES = 1;

        public String toString() {
            return "StringCoercion(" + (targetType == TARGET_TYPE_STRING ? "String" : "byte[]") + ")";
        }

        public int score(LuaValue value) {
            return switch (value.type()) {
                case LuaValue.TSTRING -> 0;
                case LuaValue.TNIL -> SCORE_NULL_VALUE;
                case LuaValue.TUSERDATA ->
                        value.touserdata() instanceof String ? 0 : SCORE_UNCOERCIBLE;
                default -> SCORE_UNCOERCIBLE;
            };
        }

        public Object coerce(LuaValue value) {
            if (value.isnil())
                return null;
            if (targetType == TARGET_TYPE_STRING)
                return value.toJavaString();
            LuaString s = value.checkstring();
            byte[] b = new byte[s.shrlen];
            System.arraycopy(s.contents, 0, b, 0, s.shrlen);
            return b;
        }
    }

    static final class ArrayCoercion implements Adapter {
        final Class<?> componentType;
        final Adapter componentCoercion;

        public ArrayCoercion(Class<?> componentType) {
            this.componentType = componentType;
            this.componentCoercion = getCoercion(componentType);
        }

        public String toString() {
            return "ArrayCoercion(" + componentType.getName() + ")";
        }

        public int score(LuaValue value) {
            return switch (value.type()) {
                case LuaValue.TTABLE -> value.length() == 0 ? 0 : check(value);
                case LuaValue.TUSERDATA ->
                        inheritanceLevels(componentType, value.touserdata().getClass().getComponentType());
                case LuaValue.TNIL -> SCORE_NULL_VALUE;
                default -> SCORE_UNCOERCIBLE;
            };
        }

        private int check(LuaValue value) {
            int n = 0;
            int len = value.length();
            int s = 1;
            if (len > 10)
                s = len / 10;
            for (int i = 0; i < len; i += s) {
                // 用 get（rawget 元素）而非 arg - LuaTable 作 Varargs 时 arg(1) 返回自身
                int r = componentCoercion.score(value.get(i + 1));
                if (r > n)
                    n = r;
                if (r == SCORE_WRONG_TYPE)
                    break;
            }
            return n;
        }

        public Object coerce(LuaValue value) {
            return switch (value.type()) {
                case LuaValue.TTABLE -> {
                    int n = value.length();
                    Object a = Array.newInstance(componentType, n);
                    for (int i = 0; i < n; i++)
                        // 同 check，用 get 而非 arg（LuaTable 作 Varargs 时 arg 语义错误）
                        Array.set(a, i, componentCoercion.coerce(value.get(i + 1)));
                    yield a;
                }
                case LuaValue.TUSERDATA -> value.touserdata();
                case LuaValue.TNIL -> null;
                default -> null;
            };
        }
    }

    /**
     * Collection/Map 形参的共享转换器：table 按序填充容器，具体容器类型与填充策略由
     * 容器种类（枚举）决定。接口或抽象目标回落到 ArrayList/HashMap。
     */
    static final class ContainerCoercion implements Adapter {
        enum Kind {
            COLLECTION {
                @SuppressWarnings("unchecked")
                Object create(Class<?> type) throws ReflectiveOperationException {
                    Collection<Object> c = type.isInterface()
                            ? new ArrayList<>()
                            : (Collection<Object>) type.getDeclaredConstructor().newInstance();
                    return c;
                }

                void fill(Object container, LuaValue table, Adapter component) {
                    Collection<Object> c = (Collection<Object>) container;
                    int n = table.length();
                    for (int i = 0; i < n; i++)
                        c.add(component.coerce(table.get(i + 1)));
                }
            },
            MAP {
                @SuppressWarnings("unchecked")
                Object create(Class<?> type) throws ReflectiveOperationException {
                    Map<Object, Object> m = type.equals(Map.class)
                            ? new HashMap<>()
                            : (Map<Object, Object>) type.getDeclaredConstructor().newInstance();
                    return m;
                }

                void fill(Object container, LuaValue table, Adapter component) {
                    Map<Object, Object> m = (Map<Object, Object>) container;
                    Varargs ret = table.next(LuaValue.NIL);
                    // LuaTable.next 无条目返回 LuaValue.NONE（表结束哨兵）。
                    while (ret != LuaValue.NONE) {
                        LuaValue k = ret.arg1();
                        m.put(component.coerce(k), component.coerce(ret.arg(2)));
                        ret = table.next(k);
                    }
                }
            };

            /** 按目标类型实例化容器；无法实例化时上抛由调用方记日志。 */
            abstract Object create(Class<?> type) throws ReflectiveOperationException;

            /** 把 Lua 表内容填充进容器。 */
            abstract void fill(Object container, LuaValue table, Adapter component);
        }

        final Class<?> componentType;
        final Kind kind;
        final Adapter componentCoercion;

        ContainerCoercion(Kind kind, Class<?> componentType) {
            this.kind = kind;
            this.componentType = componentType;
            // 元素按 Object 宽松转换：componentType 是容器类型（List/ArrayList），
            //   不是元素泛型（运行时不可知）。拿容器类型当元素目标校验会让
            //   userdata 元素全部 optuserData 失败转成 null（字符串元素因
            //   toJavaString 无视目标侥幸正确），填进容器的全是 null。
            this.componentCoercion = new ObjectCoercion(Object.class);
        }

        public String toString() {
            return kind + "Coercion(" + componentType.getName() + ")";
        }

        public int score(LuaValue value) {
            return switch (value.type()) {
                case LuaValue.TTABLE -> 10;
                case LuaValue.TUSERDATA ->
                        inheritanceLevels(componentType, value.touserdata().getClass());
                case LuaValue.TNIL -> SCORE_NULL_VALUE;
                default -> SCORE_UNCOERCIBLE;
            };
        }

        public Object coerce(LuaValue value) {
            return switch (value.type()) {
                case LuaValue.TTABLE -> {
                    try {
                        Object container = kind.create(componentType);
                        kind.fill(container, value, componentCoercion);
                        yield container;
                    } catch (ReflectiveOperationException e) {
                        Loggers.get().e("Coercion", "error", e);
                        yield null;
                    }
                }
                case LuaValue.TUSERDATA -> value.touserdata();
                case LuaValue.TNIL -> null;
                default -> null;
            };
        }
    }

    record ObjectCoercion(Class<?> targetType) implements Adapter {

        public String toString() {
            return "ObjectCoercion(" + targetType.getName() + ")";
        }

        public int score(LuaValue value) {
            if (LuaValue.class.isAssignableFrom(targetType)) {
                return inheritanceLevels(targetType, value.getClass());
            }
            return switch (value.type()) {
                case LuaValue.TNUMBER ->
                        inheritanceLevels(targetType, value.isinteger() ? Integer.class : Double.class);
                case LuaValue.TBOOLEAN -> inheritanceLevels(targetType, Boolean.class);
                case LuaValue.TSTRING -> inheritanceLevels(targetType, String.class);
                case LuaValue.TUSERDATA ->
                        inheritanceLevels(targetType, value.touserdata().getClass());
                case LuaValue.TTABLE -> {
                    if (targetType.isInterface())
                        yield SCORE_WRONG_TYPE;
                    // Lua table 可转 Java 数组（int[] 等），沿袭既有行为
                    if (targetType.isArray())
                        yield new ArrayCoercion(targetType.getComponentType()).score(value);
                    yield inheritanceLevels(targetType, LuaTable.class);
                }
                case LuaValue.TFUNCTION -> {
                    if (targetType.isInterface())
                        yield SCORE_WRONG_TYPE;
                    yield inheritanceLevels(targetType, LuaFunction.class);
                }
                case LuaValue.TNIL -> SCORE_NULL_VALUE;
                default -> inheritanceLevels(targetType, value.getClass());
            };
        }

        /** Lua integer 是 64 位：超 int 值域必须装箱 Long，无条件 toint 会静默截断。 */
        private static Object boxInteger(LuaValue value) {
            long v = value.tolong();
            return (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) ? (Object) (int) v : (Object) v;
        }

        public Object coerce(LuaValue value) {
            if (LuaValue.class.isAssignableFrom(targetType))
                return value;
            return switch (value.type()) {
                case LuaValue.TNUMBER -> value.isinteger() ? boxInteger(value) : (Object) value.todouble();
                case LuaValue.TBOOLEAN -> value.toboolean() ? Boolean.TRUE : Boolean.FALSE;
                case LuaValue.TSTRING -> value.toJavaString();
                case LuaValue.TUSERDATA -> value.optuserdata(targetType, null);
                case LuaValue.TTABLE -> {
                    if (targetType.isInterface())
                        yield JavaLib.forRunningGlobals().createProxy(new Class<?>[]{targetType}, value).touserdata();
                    // Lua table -> Java 数组（int[] 等）
                    if (targetType.isArray())
                        yield new ArrayCoercion(targetType.getComponentType()).coerce(value);
                    yield value;
                }
                case LuaValue.TFUNCTION -> {
                    if (targetType.isInterface())
                        yield JavaLib.forRunningGlobals().createProxy(new Class<?>[]{targetType}, value).touserdata();
                    yield value;
                }
                case LuaValue.TNIL -> null;
                default -> value;
            };
        }
    }
}
