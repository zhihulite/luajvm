package org.luajvm.android.lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 钉住 file 模块的 resolver 不得回退成 static —— 那是一条 Activity 泄漏路径。
 *
 * <p>纯 JVM 可跑：只碰 file 的构造器与反射，不触碰 android.util.Log。
 */
public class FilePathResolverContractTest {

    /**
     * resolver 必须是实例字段。resolver 实际是 {@code LuaEngine::findFile} 方法引用，
     * 强持 LuaEngine -&gt; Activity；放进 static 字段则永不随 Activity 销毁释放，
     * 且多宿主并存时互相覆盖。
     */
    @Test
    public void resolverIsNotHeldStatically() {
        List<String> offenders = new ArrayList<>();
        for (Field f : file.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            // 允许 static 常量（如 EMPTY_NAMES）；不允许任何 static 的 PathResolver
            if (file.PathResolver.class.isAssignableFrom(f.getType())) {
                offenders.add(f.getName() + " : " + f.getType().getName());
            }
        }
        assertTrue("resolver 不得放在 static 字段上（Activity 泄漏路径）: " + offenders,
                offenders.isEmpty());
    }

    /** 不得再提供把 resolver 装到进程级状态上的静态入口。 */
    @Test
    public void noStaticResolverSetter() {
        for (var m : file.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            boolean takesResolver = m.getParameterCount() == 1
                    && file.PathResolver.class.isAssignableFrom(m.getParameterTypes()[0]);
            assertFalse("不得有静态 resolver setter: " + m, takesResolver);
        }
    }

    /** 两个 file 实例的 resolver 互不干扰（static 版会互相覆盖）。 */
    @Test
    public void instancesDoNotShareResolver() {
        file a = new file(path -> "/A/" + path);
        file b = new file(path -> "/B/" + path);

        assertEquals("/A/x.lua", invokeResolve(a, "x.lua"));
        assertEquals("/B/x.lua", invokeResolve(b, "x.lua"));
        // 再取一次，确认 b 的构造没把 a 的 resolver 顶掉
        assertEquals("/A/x.lua", invokeResolve(a, "x.lua"));
    }

    /** resolver 为 null 时原样返回，不抛异常。 */
    @Test
    public void nullResolverPassesPathThrough() {
        assertEquals("plain.lua", invokeResolve(new file(null), "plain.lua"));
    }

    /**
     * file 实例被丢弃后，其 resolver 捕获的对象必须可回收 —— 证明没有任何进程级强引用留存。
     */
    @Test
    public void discardedInstanceReleasesResolverTarget() throws Exception {
        WeakReference<Object> ref = createAndDiscard();

        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull("file 实例丢弃后 resolver 捕获的对象仍不可回收 ⇒ 存在静态强引用", ref.get());
    }

    /**
     * 在独立栈帧里建 host + file，返回后局部变量随帧消失，只留 WeakReference。
     * 若把它们写在测试方法里，{@code final Object captured} 本身就是强引用，门禁会假红。
     */
    private static WeakReference<Object> createAndDiscard() {
        Object host = new Object();
        // resolver 捕获 host，模拟 LuaEngine::findFile 捕获引擎自身
        file f = new file(path -> host + path);
        assertTrue("resolver 应真的被调用到（否则捕获关系不成立）",
                invokeResolve(f, "/probe").endsWith("/probe"));
        return new WeakReference<>(host);
    }

    private static String invokeResolve(file target, String path) {
        try {
            var m = file.class.getDeclaredMethod("resolvePath", String.class);
            m.setAccessible(true);
            return (String) m.invoke(target, path);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("resolvePath(String) 应存在于 file 上", e);
        }
    }
}
