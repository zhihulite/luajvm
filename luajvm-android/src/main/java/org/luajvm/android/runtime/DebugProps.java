// java-only: 设备侧运行时开关注入（供同一份 APK 做 A/B）
package org.luajvm.android.runtime;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

/**
 * 从 app 私有目录读取 {@code luajvm.props}，把其中的 {@code luajvm.*} 键写进
 * {@link System#setProperty}，使**同一份 APK** 能切换引擎的 A/B 开关。
 *
 * <p><b>为什么需要</b>：引擎的 A/B 开关都读 {@code System.getProperty("luajvm.*")}
 * （见 {@code InvokeSupport}、{@code LuaString}、{@code JavaClass} 等）。JVM 侧可以用
 * {@code -Dluajvm.x=y} 传，但 Android 没有这个入口 —— {@code adb shell setprop} 设的是
 * Android 自己的 property，不是 JVM system property。而 A/B 规程要求两侧必须用
 * **同一份 class 输出**切基线，不允许比较两个不同构建。
 * 本类补上这个入口。
 *
 * <p><b>时机</b>：必须在引擎的开关类被初始化之前调用，因为那些开关是
 * {@code static final}、在类初始化时求值一次。主调用点是
 * {@code LuaActivity.attachBaseContext} —— Activity 生命周期里第一个能拿到
 * {@code Context} 的回调；{@link org.luajvm.android.engine.LuaEngine#LuaEngine(Context, Object, String)}
 * 里另有一次幂等调用作兜底（Service 等非 Activity 宿主）。
 *
 * <p>实测教训：只放在 {@code LuaEngine} 构造器里**太晚** —— 主题、EdgeToEdge 等在
 * {@code onCreate} 早期就可能已经触碰引擎类，那时开关已求值完毕。为了让"注入是否赶上"
 * 可验证，注入后会立刻回读几个代表性开关的实际值并打进 logcat：
 * **如果日志里出现 {@code USE_SPREADER=false(want true)} 这种不一致，
 * 说明引擎类在注入前就已被加载，那一轮 A/B 数据无效，不能采用。**
 * 入口还会无条件打一行日志，使"没调用"与"调用了但文件不存在"可区分。
 *
 * <p><b>默认零影响</b>：文件不存在时什么都不做（生产设备上不会有这个文件）。
 *
 * <p><b>安全边界</b>：只读 {@code Context.getFilesDir()} 下的固定文件名 —— 写它需要
 * root 或 app 自身权限，普通应用与用户无法注入；且只接受 {@code luajvm.} 前缀的键，
 * 不会污染其它系统属性。
 *
 * <p>Java 特有：C 无对应。
 */
public final class DebugProps {
    private static final String TAG = "LuajvmProps";
    private static final String FILE_NAME = "luajvm.props";
    private static final String PREFIX = "luajvm.";
    /** 单个回读探针：目标类、静态字段名、对应的 {@code luajvm.*} 属性键 */
    private record Probe(String className, String fieldName, String propKey) {
    }

    /**
     * 回读用的开关清单。
     * 覆盖全部四个引擎开关与三个不同宿主类 —— 任一项 {@code effective != want}
     * 就说明该类在注入前已被初始化，那一轮 A/B 数据无效。
     */
    private static final List<Probe> PROBES = List.of(
            new Probe("org.luajvm.bind.InvokeSupport", "FAST_CALL", "luajvm.bindfastcall"),
            new Probe("org.luajvm.core.LuaString", "STR_JCACHE", "luajvm.strjcache"),
            new Probe("org.luajvm.bind.JavaClass", "INNER_LAZY", "luajvm.bindinnerlazy"),
            new Probe("org.luajvm.bind.JavaMethod", "ENGINE_LOADER_CACHE", "luajvm.bindloadercache"),
            new Probe("org.luajvm.android.engine.WebViewPrewarm", "ENABLED", "luajvm.webviewprewarm"));

    private static boolean loaded;

    private DebugProps() {
    }

    /**
     * 幂等：进程内只读一次。
     *
     * <p>入口无条件打一行日志：注入失败时最需要知道的就是"到底调到了没有、
     * 什么时候调的、看的是哪个目录"。静默 return 会让排查退化成猜。
     */
    public static synchronized void loadOnce(Context context) {
        if (loaded) return;
        // [关键]拿不到 files 目录时**不能**标记 loaded，否则会把后面那次能成功的调用挡掉。
        //   LuaActivity 字段初始化阶段 new LuaEngine 时 base context 未 attach，getFilesDir()
        //   抛 NPE；若在那里置 loaded=true，attachBaseContext 那次（时机正确）永不执行，极难定位。
        if (context == null) {
            Log.i(TAG, "loadOnce: context==null, will retry at a later call site");
            return;
        }
        File file;
        try {
            File dir = context.getFilesDir();
            if (dir == null) {
                Log.i(TAG, "loadOnce: getFilesDir()==null, will retry at a later call site");
                return;
            }
            file = new File(dir, FILE_NAME);
        } catch (Exception e) {
            Log.i(TAG, "loadOnce: getFilesDir() threw (base context not attached yet?)"
                    + " will retry at a later call site: " + e);
            return;
        }
        // 走到这里才算真正尝试过一次
        loaded = true;
        if (!file.isFile()) {
            // 生产设备的常态。仍打一行，否则"没注入"和"没调用"无法区分。
            Log.i(TAG, "loadOnce: no " + file + " (normal on production), skip");
            return;
        }

        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (Exception e) {
            Log.w(TAG, "read " + file + " failed", e);
            return;
        }
        TreeSet<String> applied = new TreeSet<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith(PREFIX)) continue;   // 只接受 luajvm.* 前缀
            String value = props.getProperty(key);
            if (value == null) continue;
            System.setProperty(key, value.trim());
            applied.add(key + "=" + value.trim());
        }
        Log.i(TAG, "applied from " + file + ": " + applied);
        logEffective();
    }

    /**
     * 回读引擎里实际生效的开关值。这一步是**注入时机的验证**，不是装饰：
     * 读取会触发对应类的初始化，此刻其 {@code static final} 才求值，
     * 因此日志里的值就是本轮真正生效的值。
     */
    private static void logEffective() {
        StringBuilder sb = new StringBuilder("effective:");
        for (Probe probe : PROBES) {
            String effective;
            try {
                Field field = Class.forName(probe.className()).getDeclaredField(probe.fieldName());
                field.setAccessible(true);
                effective = String.valueOf(field.get(null));
            } catch (Throwable t) {
                effective = "<unreadable:" + t.getClass().getSimpleName() + ">";
            }
            String wanted = System.getProperty(probe.propKey());
            sb.append(' ').append(probe.fieldName()).append('=').append(effective);
            if (wanted != null) sb.append("(want ").append(wanted).append(')');
        }
        Log.i(TAG, sb.toString());
    }
}
