// java-only: 活动 Globals 的中立登记表。
package org.luajvm.core;

import java.lang.ref.WeakReference;

/**
 * 活动 {@link Globals} 的登记表。
 *
 * <p>存在的唯一理由是打断静态初始化环：{@code LuaValue.<clinit>} 经
 * {@code Metamethod} 创建固定字符串，进入 {@code LuaString} 的分配路径；
 * 该路径若引用 {@code LuaTable}（或其子类 {@code Globals}）取当前状态，就触发
 * {@code LuaTable.<clinit>}，而后者依赖尚未初始化完的 {@code Metamethod} ⇒ NPE。
 *
 * <p>本类不引用任何其他 luajvm 类型的静态成员，故可在字符串分配路径上安全调用。
 *
 * <h2>为什么持弱引用</h2>
 *
 * <p>"建一个 Globals 再丢掉引用"必须可回收：注册表、全部标准库表、
 * `allTables`/`allThreads`/`allProtos` 等对象链都挂在状态上，强引用登记会让它们
 * 全部滞留——这正是 Android 每个 Activity 建一个状态再销毁的模式，属无界泄漏。
 *
 * <p>持 {@link WeakReference} 后，"Java 侧再无强引用"即可回收 - 与 C 的
 * {@code lua_close} 不同，Java 不要求宿主显式关闭。正在执行 Lua 的状态不会被回收：
 * 执行中的 {@code LuaThread} 经 {@code l_G} 强引用其 {@code Globals}，而该线程本身
 * 是活的 Java 对象。故"可回收"严格蕴含"无人再用"。
 *
 * <h2>并发</h2>
 *
 * <p>快照数组 {@code volatile}，读路径（{@link #owner()} 等）无锁：仅读一次数组引用。
 * 写路径（注册/注销/清理）在 {@link #WRITE_LOCK} 下 copy-on-write——
 * {@code Globals} 构造可发生在任意线程，读者须恒看到完整快照。
 */
final class LuaStates {
    /** 空快照复用，避免注销到零时反复分配。 */
    private static final WeakReference<Globals>[] EMPTY = newArray(0);

    private static volatile WeakReference<Globals>[] active = EMPTY;
    private static final Object WRITE_LOCK = new Object();

    private LuaStates() {
    }

    @SuppressWarnings("unchecked")
    private static WeakReference<Globals>[] newArray(int n) {
        return (WeakReference<Globals>[]) new WeakReference<?>[n];
    }

    static void register(Globals g) {
        if (g == null) return;
        synchronized (WRITE_LOCK) {
            WeakReference<Globals>[] cur = active;
            // 顺带丢弃已回收的条目：注册是唯一必然随状态数增长的操作，
            //   在此清理使表规模跟随存活状态数，无需独立的清理时机。
            int live = 0;
            for (WeakReference<Globals> r : cur) {
                Globals o = r.get();
                if (o == g) return;   // 已登记
                if (o != null) live++;
            }
            WeakReference<Globals>[] next = newArray(live + 1);
            int i = 0;
            for (WeakReference<Globals> r : cur) {
                if (r.get() != null) next[i++] = r;
            }
            next[i] = new WeakReference<>(g);
            active = next;
        }
    }

    static void unregister(Globals g) {
        if (g == null) return;
        synchronized (WRITE_LOCK) {
            WeakReference<Globals>[] cur = active;
            int live = 0;
            boolean found = false;
            for (WeakReference<Globals> r : cur) {
                Globals o = r.get();
                if (o == g) {
                    found = true;
                } else if (o != null) {
                    live++;
                }
            }
            if (!found && live == cur.length) return;   // 无变化
            if (live == 0) {
                active = EMPTY;
                return;
            }
            WeakReference<Globals>[] next = newArray(live);
            int i = 0;
            for (WeakReference<Globals> r : cur) {
                Globals o = r.get();
                if (o != null && o != g) next[i++] = r;
            }
            active = next;
        }
    }

    /**
     * 存活状态数。
     *
     * <p>需逐个 {@code get()}：数组长度含尚未清理的已回收条目。
     * 仅用于诊断与 ltests 的进程级计数，不在热路径上。
     */
    static int count() {
        int n = 0;
        for (WeakReference<Globals> r : active) {
            if (r.get() != null) n++;
        }
        return n;
    }

    /** 存活状态的强引用快照（调用期间不会被回收）。 */
    static Globals[] snapshot() {
        WeakReference<Globals>[] cur = active;
        Globals[] out = new Globals[cur.length];
        int n = 0;
        for (WeakReference<Globals> r : cur) {
            Globals g = r.get();
            if (g != null) out[n++] = g;
        }
        if (n == out.length) return out;
        Globals[] exact = new Globals[n];
        System.arraycopy(out, 0, exact, 0, n);
        return exact;
    }

    /**
     * 取"此刻拥有新建可回收对象"的状态。
     *
     * <p>优先返回有线程正在其中执行的状态 - 这不是猜测：对象正是在该状态的执行区内
     * 创建的。判据是执行中的线程必有非 base 的 CI 帧：{@code running} 在建状态时即
     * 预指主线程且执行结束不复位，只判 {@code running != null} 会恒选首个登记状态
     * （多状态进程中错误快照、GC 归属全部错到旧状态上）。无执行状态时返回首个存活
     * 登记状态。
     *
     * <p>处于字符串/对象分配路径上，故不分配、不加锁：仅读一次 volatile 数组。
     */
    static Globals owner() {
        WeakReference<Globals>[] cur = active;
        if (cur.length == 0) return null;
        if (cur.length == 1) return cur[0].get();
        Globals first = null;
        for (WeakReference<Globals> r : cur) {
            Globals g = r.get();
            if (g == null) continue;
            LuaThread t = g.running;
            if (t != null) {
                if (t.ci != t.base_ci) return g;
            }
            if (first == null) first = g;
        }
        return first;
    }

    /**
     * 当前 Java 线程正在其执行区的状态。执行区（{@code Globals.invoke} 族经
     * executionLock 进入）是"正在执行"的权威标记；多状态进程中 owner() 的判据
     * 仍是推断，错误快照等需要精确归属的调用方走本方法。无命中时回退 {@link #owner()}。
     */
    static Globals executingOwner() {
        WeakReference<Globals>[] cur = active;
        if (cur.length > 1) {
            for (WeakReference<Globals> r : cur) {
                Globals g = r.get();
                if (g != null && g.isExecutingOnCurrentThread()) return g;
            }
        }
        return owner();
    }
}
