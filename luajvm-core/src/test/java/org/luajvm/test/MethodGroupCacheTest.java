// java-only 门禁：JavaClass 仅为实际访问的方法名构建包装，负缓存必须有界。
package org.luajvm.test;

import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;

import java.lang.reflect.Field;
import java.util.Map;

public final class MethodGroupCacheTest {
    private static final int MISSING_KEYS = 2_000;
    private static final int MISSING_CACHE_MAX = 64;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Globals g = Platform.standardGlobals();

        g.execute("SB = luajava.newInstance('java.lang.StringBuilder')");
        Object javaClass = javaClass(g, StringBuilder.class);
        int afterConstructor = methodMapSize(javaClass);
        check(afterConstructor == 1,
                "构造后 methodMap 只应有 new（实测 " + afterConstructor + "）");

        g.execute("SB:append('a')");
        int afterAppend = methodMapSize(javaClass);
        check(afterAppend == afterConstructor + 1,
                "首次 append 只新增一个方法名包装（实测 " + afterAppend + "）");

        g.execute("assert(SB:length() == 1)\n"
                + "assert(SB:toString() == 'a')");
        int afterUsedMethods = methodMapSize(javaClass);
        check(afterUsedMethods <= afterAppend + 2,
                "只应为实际调用的 length/toString 新增包装（实测 " + afterUsedMethods + "）");

        g.execute("for i = 1, " + MISSING_KEYS
                + " do local _ = SB['missing_' .. i] end");
        int afterMissing = methodMapSize(javaClass);
        int missingCache = missingMethodCacheSize(javaClass);
        check(afterMissing == afterUsedMethods,
                "缺失方法不得写入 methodMap（实测 " + afterUsedMethods + " -> " + afterMissing + "）");
        check(missingCache <= MISSING_CACHE_MAX,
                "缺失方法负缓存必须 <= " + MISSING_CACHE_MAX + "（实测 " + missingCache + "）");

        g.execute("SB:append('b')\n"
                + "assert(SB:toString() == 'ab')\n"
                + "local String = luajava.bindClass('java.lang.String')\n"
                + "assert(String:getName() == 'java.lang.String')\n"
                + "local A = luajava.newInstance('java.util.ArrayList')\n"
                + "A:add('x') A:add('y')\n"
                + "assert(A:size() == 2 and A:get(1) == 'y')");
        check(true, "构造器、重载、Class 方法优先级与返回值语义正常");

        if (failures > 0) {
            System.out.println("MethodGroupCacheTest FAILED: " + failures + " 处");
            System.exit(1);
        }
        System.out.println("MethodGroupCacheTest OK");
    }

    private static Object javaClass(Globals g, Class<?> type) throws Exception {
        Object cache = field(g, "javaClassCache");
        if (!(cache instanceof Map<?, ?> map)) {
            throw new AssertionError("javaClassCache unavailable");
        }
        Object javaClass = map.get(type);
        if (javaClass == null) throw new AssertionError("JavaClass missing for " + type);
        return javaClass;
    }

    private static int methodMapSize(Object javaClass) throws Exception {
        Object value = field(javaClass, "methodMap");
        return value instanceof Map<?, ?> map ? map.size() : 0;
    }

    private static int missingMethodCacheSize(Object javaClass) throws Exception {
        Object value = field(javaClass, "missingMethodKeys");
        return value instanceof Map<?, ?> map ? map.size() : 0;
    }

    private static Object field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                // 继续父类。
            }
        }
        throw new NoSuchFieldException(name + " on " + owner.getClass());
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    private MethodGroupCacheTest() {
    }
}
