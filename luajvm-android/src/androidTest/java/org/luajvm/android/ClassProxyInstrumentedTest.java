package org.luajvm.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.luajvm.android.proxy.LuaClassProxy;
import org.luajvm.bind.Coercion;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

/**
 * luajava.override 的仪器化测试：dexmaker 的 ProxyBuilder 生成 dex 后经 DexClassLoader
 * 加载，需要 codeCacheDir 与 ART，纯 JVM 单测与 Robolectric 都到不了这条路径。
 *
 * <p><b>每个用例必须用独立目标类</b>：ProxyBuilder 的 generatedProxyClasses 是进程级
 * static 缓存，键为 (baseClass, interfaces, classLoader, sharedClassLoader)，共用目标类
 * 会让后跑的用例拿到前一个用例生成的代理类。
 */
@RunWith(AndroidJUnit4.class)
public class ClassProxyInstrumentedTest {

    // ==================== 目标类（一个用例一个，勿复用） ====================

    public static abstract class IntTarget {
        public int count() {
            return 7;
        }

        public abstract String name();
    }

    public static abstract class BoolTarget {
        public boolean flag() {
            return false;
        }

        public abstract String name();
    }

    public static abstract class UnlistedTarget {
        public int count() {
            return 7;
        }

        public abstract String name();
    }

    public static abstract class NilHandlerTarget {
        public int count() {
            return 7;
        }

        public abstract String name();
    }

    public static abstract class BadReturnTarget {
        public int count() {
            return 7;
        }

        public abstract String name();
    }

    public static abstract class IsProxyTarget {
        public abstract String name();
    }

    public static abstract class GrowingSetTarget {
        public int first() {
            return 1;
        }

        public int second() {
            return 2;
        }

        public abstract String name();
    }

    // ==================== 辅助 ====================

    private static LuaFunction fn(LuaValue result) {
        return new LuaFunction() {
            @Override
            public Varargs call(Varargs a) {
                return result;
            }
        };
    }

    /** 装配 handler 表并自检键真的写进去了——装配失败不得伪装成引擎缺陷。 */
    private static LuaTable handlerTable(String name, LuaValue result) {
        LuaTable t = new LuaTable();
        t.set(name, fn(result));
        assertFalse("前置：handler 表须真含 '" + name + "' 键，否则 onlyMethods 收不到该方法",
                t.get(name).isnil());
        return t;
    }

    private static Object override(Class<?> clazz, LuaTable methods) {
        return new LuaClassProxy(clazz).create(null, methods);
    }

    // ==================== 用例 ====================

    /** 基元 int 返回：Lua 给 number，dexmaker 的 cast+unbox 桥接须拿到 Integer。 */
    @Test
    public void primitiveIntReturnIsBoxedExactly() {
        IntTarget t = (IntTarget) override(IntTarget.class,
                handlerTable("count", LuaValue.valueOf(42)));
        assertEquals("override 的 int 返回值应精确装箱为 Integer", 42, t.count());
    }

    /** 基元 boolean 返回：走 LuaBoolCoercion，桥接须拿到 Boolean。 */
    @Test
    public void primitiveBooleanReturnIsBoxedExactly() {
        BoolTarget t = (BoolTarget) override(BoolTarget.class,
                handlerTable("flag", LuaValue.TRUE));
        assertTrue("override 的 boolean 返回值应精确装箱为 Boolean", t.flag());
    }

    /** 未列入 handler 表的方法保持原实现（空表 ⇒ 什么都不代理）。 */
    @Test
    public void unlistedMethodKeepsOriginalBehavior() {
        UnlistedTarget t = (UnlistedTarget) override(UnlistedTarget.class, new LuaTable());
        assertEquals("未列入 handler 表的方法应保持原实现", 7, t.count());
    }

    /** 键存在但值为 nil：走 zeroValueFor，不得抛。 */
    @Test
    public void nilHandlerValueFallsBackToZeroValue() {
        LuaTable methods = new LuaTable();
        methods.set("count", LuaValue.NIL);
        NilHandlerTarget t = (NilHandlerTarget) override(NilHandlerTarget.class, methods);
        // 键为 nil 时 Lua 表里等同不存在 ⇒ 与未列入同义，保持原实现
        assertEquals("nil handler 值应等同未列入", 7, t.count());
    }

    /**
     * 返回不可转换值：必须降级为零值或语义化 LuaError，不得让 NPE 逃进框架调用栈。
     * 非 Number 的 userdata 在 NumericCoercion 里取不到数字，判空缺失时会 unbox NPE。
     */
    @Test
    public void unconvertibleReturnDoesNotEscapeAsNpe() {
        LuaTable methods = handlerTable("count", Coercion.toLua(new Object()));
        BadReturnTarget t = (BadReturnTarget) override(BadReturnTarget.class, methods);
        try {
            assertEquals("不可转换返回值应降级为零值", 0, t.count());
        } catch (NullPointerException e) {
            fail("不可转换的返回值不得以 NPE 逃出代理：" + e);
        } catch (LuaError ignored) {
            // 语义化错误同样可接受
        }
    }

    /** 代理类由 dexmaker 在设备上生成，且 codeCacheDir 可用。 */
    @Test
    public void proxyClassIsGeneratedOnDevice() {
        IsProxyTarget t = (IsProxyTarget) override(IsProxyTarget.class, new LuaTable());
        assertTrue("dexmaker 生成的类应被 isProxy 识别", LuaClassProxy.isProxy(t.getClass()));
        assertNotNull("仪器化上下文应可用",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCodeCacheDir());
    }

    /**
     * 同一个类第二次 override 增加方法时，新增的方法必须生效。
     *
     * <p>ProxyBuilder.generatedProxyClasses 的键不含 methods，故第二次 build 直接命中
     * 首次生成的代理类，onlyMethods 被整体忽略 ⇒ 新增方法静默不被代理。
     * Lua 侧表现：先 override(Foo,{a=f}) 再 override(Foo,{a=f,b=g})，b 永不触发。
     */
    @Test
    public void secondOverrideWithMoreMethodsTakesEffect() {
        GrowingSetTarget one = (GrowingSetTarget) override(GrowingSetTarget.class,
                handlerTable("first", LuaValue.valueOf(11)));
        assertEquals("前置：首次 override 的 first 应生效", 11, one.first());
        assertEquals("前置：未列入的 second 应保持原实现", 2, one.second());

        LuaTable both = new LuaTable();
        both.set("first", fn(LuaValue.valueOf(11)));
        both.set("second", fn(LuaValue.valueOf(22)));
        GrowingSetTarget two = (GrowingSetTarget) override(GrowingSetTarget.class, both);
        assertEquals("第二次 override 的 first 应仍生效", 11, two.first());
        assertEquals("第二次 override 新增的 second 必须生效"
                + "（ProxyBuilder 缓存键不含 methods，命中缓存会让 onlyMethods 失效）",
                22, two.second());
    }
}
