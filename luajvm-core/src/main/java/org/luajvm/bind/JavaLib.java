// java-only: Java库注册
package org.luajvm.bind;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaUserdata;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;
import org.luajvm.spi.LuaJavaContext;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;

@SuppressWarnings("rawtypes")
public class JavaLib extends LuaFunction {

    /** Lua 侧 luajava.* 函数表，一张 enum 同时携带名字与实现（消灭 opcode/名字两张平行表）。 */
    enum LibOp {
        BINDCLASS("bindClass") {
            @Override
            Varargs invoke(JavaLib lib, Varargs args) throws Exception {
                return JavaClass.forClass(lib.ownerGlobals, lib.classForName(args.checkJavaString(1)));
            }
        },
        NEWINSTANCE("newInstance") {
            @Override
            Varargs invoke(JavaLib lib, Varargs args) throws Exception {
                return lib.constructByName(args.checkvalue(1).toJavaString(), args.subargs(2));
            }
        },
        NEW("new") {
            @Override
            Varargs invoke(JavaLib lib, Varargs args) throws Exception {
                Class<?> clazz = (Class) args.checkvalue(1).checkuserdata(Class.class);
                return lib.constructByClass(clazz, args.subargs(2));
            }
        },
        CREATEPROXY("createProxy") {
            @Override
            Varargs invoke(JavaLib lib, Varargs args) throws Exception {
                int niface = args.narg() - 1;
                if (niface <= 0) LuaErrors.error("no interfaces");
                LuaValue lobj = args.checkvalue(niface + 1);
                Class[] ifaces = new Class[niface];
                for (int i = 0; i < niface; i++) {
                    LuaValue arg = args.arg(i + 1);
                    ifaces[i] = arg.isstring() ? lib.classForName(arg.checkJavaString()) : (Class) arg.checkuserdata(Class.class);
                }
                return lib.createProxy(ifaces, lobj);
            }
        },
        LOADLIB("loadLib") {
            @Override
            Varargs invoke(JavaLib lib, Varargs args) throws Exception {
                String classname = args.checkJavaString(1);
                String methodname = args.checkJavaString(2);
                Class<?> clazz = lib.classForName(classname);
                Method method = clazz.getMethod(methodname);
                Object result = method.invoke(clazz);
                return result instanceof LuaValue lv ? lv : LuaValue.NIL;
            }
        },
        ASTABLE("astable") {
            @Override
            Varargs invoke(JavaLib lib, Varargs args) {
                if (args.istable(1)) return args.checktable(1);
                return asTable(args.checkuserdata(), args.optboolean(2, false));
            }
        },
        INSTANCEOF("instanceof") {
            @Override
            Varargs invoke(JavaLib lib, Varargs args) {
                Class cls = args.arg(2).touserdata(Class.class);
                return LuaValue.valueOf(cls.isInstance(args.checkuserdata()));
            }
        },
        OVERRIDE("override") {
            @Override
            Varargs invoke(JavaLib lib, Varargs args) throws Exception {
                LuaValue first = args.arg(1);
                Class cls = first.isstring() ? lib.classForName(first.checkJavaString()) : (Class) first.checkuserdata(Class.class);
                return lib.override(cls, args.subargs(2));
            }
        },
        CLEAR("clear") {
            @Override
            Varargs invoke(JavaLib lib, Varargs args) {
                // luajava.clear(obj) - 主动释放 userdata 包装的 Java 引用
                //   （udatamem -> null），帮助 GC 回收。
                //   调用方需确保 clear 后不再使用该 userdata。
                LuaValue v = args.arg(1);
                if (v instanceof LuaUserdata lu) lu.udatamem = null;
                return LuaValue.NIL;
            }
        },
        NEWARRAY("newArray") {
            @Override
            Varargs invoke(JavaLib lib, Varargs args) {
                // luajava.newArray(Class, size) -> java.lang.reflect.Array.newInstance。
                //   返回数组经 Coercion.toLua 包成 JavaCollection（userdata，Lua 式索引/长度），
                //   含 byte[]——保持 Java 引用语义，可当 InputStream.read 的输出缓冲。
                Class<?> component = (Class<?>) args.arg(1).checkuserdata(Class.class);
                int size = args.checkint(2);
                if (size < 0) LuaErrors.error("negative array size");
                return Coercion.toLua(Array.newInstance(component, size));
            }
        };

        final String name;

        LibOp(String name) {
            this.name = name;
        }

        abstract Varargs invoke(JavaLib lib, Varargs args) throws Exception;
    }

    static final int METHOD_MODIFIERS_VARARGS = Modifier.TRANSIENT;

    // java diff: 实例字段而非 static - 每个 Globals（即每个 Android Activity）持有
    //   自己的 JavaLib 实例，可绑定不同的 LuaJavaContext（不同 ClassLoader 的 Activity
    //   场景）；static 会被最后一个 setLuaContext 的 Activity 覆盖，串到其他 Activity。
    private LuaJavaContext mLuaContext;
    public ArrayList<ClassLoader> classLoaders = new ArrayList<>();

    public JavaLib() {
    }

    /**
     * SPI 加载的进程级共享 Context。
     *
     * <p>java-only: 惰性 holder 惯用法 - JVM 规范保证类初始化线程安全且恰好执行一次，
     * 且只在首次访问 {@code SpiHolder.CONTEXT} 时触发（显式 setLuaContext 的调用方
     * 永不触发 SPI 扫描）。
     */
    private static final class SpiHolder {
        static final LuaJavaContext CONTEXT = load();

        private static LuaJavaContext load() {
            try {
                Iterator<LuaJavaContext> it =
                        ServiceLoader.load(LuaJavaContext.class).iterator();
                if (it.hasNext()) return it.next();
            } catch (Throwable ignored) {
                // ServiceLoader 失败（如 Android 早期版本权限问题）-> null，走 "not set" 错误路径
            }
            return null;
        }
    }

    // SPI 自动加载 LuaJavaContext。
    //   Android 端通过 META-INF/services/org.luajvm.spi.LuaJavaContext 注册
    //   AndroidLuaJavaContext，ServiceLoader 自动发现，无需显式 setLuaContext。
    private void ensureContext() {
        if (mLuaContext == null) mLuaContext = SpiHolder.CONTEXT;
    }

    public LuaUserdata createProxy(Class<?>[] ifaces, LuaValue lobj) {
        ensureContext();
        if (mLuaContext == null) LuaErrors.error("LuaJavaContext not set.");
        Object proxy = mLuaContext.createProxy(ifaces, lobj);
        return LuaValue.userdataOf(proxy);
    }

    public LuaValue override(Class<?> clazz, Varargs arg) {
        ensureContext();
        if (mLuaContext == null) LuaErrors.error("LuaJavaContext not set.");
        if (!(arg.arg1() instanceof LuaTable)) LuaErrors.error("override methods must be a table");
        Object proxy = mLuaContext.override(clazz, arg);
        return new JavaObject(proxy);
    }

    public LuaJavaContext getLuaContext() {
        return mLuaContext;
    }

    public void setLuaContext(LuaJavaContext ctx) {
        // 显式设置即生效；ensureContext 见 mLuaContext != null 便不再触发 SpiHolder 类初始化
        mLuaContext = ctx;
    }

    /**
     * java-only: 取指定状态装配的 JavaLib 实例（未装 luajava 时返回 null）。
     *
     * <p>{@code Globals.luajavaLib} 的声明类型是 {@link LuaValue} 而非 {@code JavaLib} ——
     * core 对该字段零方法调用，纯粹是 per-Globals 注册处，写成具体类只会让 {@code core}
     * 反向依赖 {@code bind}（分层门禁的债务条目）。类型还原集中在此，宿主与测试经本入口取回。
     */
    public static JavaLib forGlobals(Globals g) {
        return (g != null && g.luajavaLib instanceof JavaLib lib) ? lib : null;
    }

    /**
     * java-only: 取当前运行状态的 JavaLib 实例。
     *
     * <p>Coercion / JavaClass / JavaObject 里需要 createProxy / override 的调用点
     * 无法直接持有 JavaLib 引用，通过此入口从运行中的 Globals 取 per-Globals 实例。
     * 若当前无运行状态（例如测试辅助路径），返回一个临时实例。
     */
    public static JavaLib forRunningGlobals() {
        JavaLib lib = forGlobals(LuaTable.runningGlobalsForGC());
        if (lib != null) return lib;
        return new JavaLib();   // fallback：临时实例，SPI 加载路径
    }

    public static LuaValue asTable(Object obj, boolean deep) {
        // 单一实现：deep 递归展开嵌套容器，shallow 经 Coercion.toLua（JavaObject 包装）
        LuaTable tab = new LuaTable();
        if (obj.getClass().isArray()) {
            int n = Array.getLength(obj);
            for (int i = 0; i < n; i++)
                tab.set(i + 1, asTable(Array.get(obj, i), deep));
        } else if (obj instanceof Collection list) {
            int i = 1;
            for (Object v : list) tab.set(i++, asTable(v, deep));
        } else if (obj instanceof Map map) {
            for (Object o : map.entrySet()) {
                Map.Entry entry = (Map.Entry) o;
                tab.set(Coercion.toLua(entry.getKey()), asTable(entry.getValue(), deep));
            }
        } else {
            return Coercion.toLua(obj);
        }
        return tab;
    }

    @Override
    public Varargs call(Varargs args) {
        // JavaLib 本身只处理 INIT（库加载）
        try {
            LuaValue env = args.arg(2);
            Globals globals = env.checkglobals();
            globals.luajavaLib = this;
            this.ownerGlobals = globals;   // java-only: 记录归属，dispatch 用于按状态缓存 JavaClass
            LuaTable t = new LuaTable();
            // 直接设置库函数
            for (LibOp op : LibOp.values()) {
                t.set(op.name, new JavaLibFunc(op, this));
            }
            env.set("luajava", t);
            env.get("package").get("loaded").set("luajava", t);
            env.set("boolean", JavaClass.forClass(globals, Boolean.TYPE));
            env.set("byte",    JavaClass.forClass(globals, Byte.TYPE));
            env.set("char",    JavaClass.forClass(globals, Character.TYPE));
            env.set("short",   JavaClass.forClass(globals, Short.TYPE));
            env.set("int",     JavaClass.forClass(globals, Integer.TYPE));
            env.set("long",    JavaClass.forClass(globals, Long.TYPE));
            env.set("float",   JavaClass.forClass(globals, Float.TYPE));
            env.set("double",  JavaClass.forClass(globals, Double.TYPE));
            env.set("import", new LuaFunction() {
                public Varargs call(Varargs args) {
                    String name = args.checkJavaString(1);
                    LuaValue result;
                    try {
                        result = bindClassForName(globals, name);
                    } catch (Exception e) {
                        // loadlib.c: require 由 luaopen_package 注册为全局（C 侧同样从
                        //   全局/registry 取，不持 PackageLib 的 Java 引用）。经 globals 调用，
                        //   bind 与 lib 同级互不依赖（分层门禁）。
                        result = LuaCall.callLua(globals.get("require"), LuaString.newStr(name)).arg1();
                        if (result.isboolean()) result = globals.get(name);
                    }
                    if (!result.isnil()) {
                        String shortName = name.replaceFirst(".*?[$.]([^$.]*)$", "$1");
                        env.set(shortName, result);
                        return result;
                    }
                    LuaErrors.error("Cannot import: " + name);
                    return LuaValue.NIL;
                }
            });
            return t;
        } catch (LuaError e) {
            throw e;
        } catch (Exception e) {
            LuaErrors.error(e.getMessage(), e);
        }
        return LuaValue.NIL;
    }

    // dispatch
    Varargs dispatch(LibOp op, Varargs args) {
        try {
            return op.invoke(this, args);
        } catch (LuaError e) {
            throw e;
        } catch (InvocationTargetException ite) {
            LuaErrors.error(ite.getTargetException().getMessage(), ite.getTargetException());
        } catch (Exception e) {
            LuaErrors.error(e.getMessage(), e);
        }
        return LuaValue.NIL;
    }

    /** NEWINSTANCE/NEW 的公共构造路径：按类名解析后走 constructByClass。 */
    private Varargs constructByName(String className, Varargs ctorArgs) throws Exception {
        return constructByClass(classForName(className), ctorArgs);
    }

    /**
     * 构造器匹配/打分/参数转换统一走 JavaClass.getConstructor() -> JavaConstructor.Overload
     * （已支持 varargs、Coercion score 匹配）。
     * JavaConstructor.Overload 返回 new JavaObject(...)；构造器可能返回 LuaValue
     * 自身（如 LuaClosure 子类） - JavaObject 包装 LuaValue 会失去 Lua 语义，
     * 故对 LuaValue 结果解包返回。
     */
    private Varargs constructByClass(Class<?> clazz, Varargs ctorArgs) {
        LuaValue result = LuaCall.callLua(
                JavaClass.forClass(ownerGlobals, clazz).getConstructor(), ctorArgs).arg1();
        return result instanceof JavaObject jo && jo.udatamem instanceof LuaValue lv ? lv : result;
    }

    protected Class<?> classForName(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    /** java-only: 按状态解析类名  -  JavaClass 缓存归属 {@code g}，不可跨 Globals 复用。 */
    public LuaValue bindClassForName(Globals g, String name) throws ClassNotFoundException {
        try {
            return JavaClass.forName(g, name);
        } catch (Exception ignored) {
        }
        for (ClassLoader loader : classLoaders) {
            try {
                return JavaClass.forName(g, name, loader);
            } catch (Exception ignored) {
            }
        }
        throw new ClassNotFoundException(name);
    }

    // JavaLibFunc
    static class JavaLibFunc extends LuaFunction {
        final LibOp opcode;
        final JavaLib lib;

        JavaLibFunc(LibOp opcode, JavaLib lib) {
            this.opcode = opcode;
            this.lib = lib;
        }

        @Override
        public Varargs call(Varargs args) {
            return lib.dispatch(opcode, args);
        }
    }

    /**
     * java-only: 惰性 Java 包解析（{@code java.util.ArrayList} 形式的逐段 get）。
     *
     * <p>必须携带所属 {@code Globals}：解析结果是 {@code JavaClass}（{@code LuaUserdata}，
     * 带 {@code ownerGlobals}），缓存与子 Package 都只能属于本状态。
     */
    public static class Package extends LuaValue {
        private final String name;
        private final Globals owner;
        private final HashMap<String, LuaValue> cache = new HashMap<>();

        public Package(Globals owner, String name) {
            // tt_ 必须与 type()（TUSERDATA）一致：VM 的 tag dispatch 按 tt_ 分派，
            //   否则 LuaValue 无参构造会留 tt_=0，与 type() 不符。
            //   不带 BIT_ISCOLLECTABLE - Package 不经 bindGlobals 登记到任何 Globals，
            //   置可收集位会让 GC 试图管理未登记对象。
            super(LUA_VUSERDATA);
            this.owner = owner;
            this.name = name;
        }

        @Override
        public LuaValue get(String key) {
            LuaValue ret = cache.get(key);
            if (ret != null) return ret;
            String fullName = name + "." + key;
            try {
                ret = JavaClass.forClass(owner, Class.forName(fullName));
            } catch (Exception e) {
                ret = new Package(owner, fullName);
            }
            cache.put(key, ret);
            return ret;
        }

        @Override
        public LuaValue get(LuaValue key) {
            return get(key.toJavaString());
        }

        @Override
        public int type() {
            return LuaValue.TUSERDATA;
        }

        @Override
        public String typeName() {
            return "userdata";
        }

        @Override
        public String toJavaString() {
            return "JavaPackage: " + name;
        }
    }
}
