package org.luajvm.test;

import org.luajvm.bind.Platform;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaTable;
import org.luajvm.vm.LuaPlatform;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * java-only：丢弃的 {@link Globals} 必须可被 GC 回收。
 *
 * <p>C 的 {@code lua_State} 由 {@code lua_close} 显式销毁，不调是用户 bug。
 * Java 无析构函数，惯例是丢引用即回收 - Android 每个 Activity 建一个 Globals，
 * Activity 销毁时仅丢引用而不调 close。若登记表持强引用，每个被丢弃的 Globals
 * 将连带 registry、标准库表、allTables/allThreads/allFunctions 永久滞留，
 * 典型的长驻进程泄漏。
 *
 * <p>本测试创建若干 Globals，仅保留 {@link WeakReference}，丢弃强引用后验证：
 * (a) JVM 能否回收对象，(b) 登记表计数是否回落。
 *
 * <p>**两轮测试**：第二轮额外执行 luajava 反射调用。原因是 {@code bind/} 下有若干
 * 进程级 static 缓存（{@code ExecutableBinding.EXECUTABLES}、
 * {@code JavaClass.javaClassMethods}）存储 {@code LuaValue}，而每个 {@code LuaValue}
 * 都持有 {@code ownerGlobals} 字段 - 若任一值绑定到某 Globals，这些永不清理的
 * static Map 将钉死该状态，使弱引用登记表对使用 luajava 的宿主完全失效。
 *
 * <p>**第三轮：挂起协程 + {@link Globals#close()}**。挂起协程是"丢引用即回收"唯一的
 * 例外——线程模式下它占一个 park 着的 Java 线程，而 park 着的线程是 GC 根，其
 * {@code runCoroutine} 帧强持 {@code LuaThread} -> {@code l_G} -> 整个状态。这条无法
 * 自动化（检测"宿主已丢弃"要求状态不可达，而线程恰恰让它永远可达），故由
 * {@code close()} 显式收尾。本轮验证 close 后状态确实可回收。
 */
public final class GlobalsRetentionTest {
    private static final int STATES = 12;
    private static int failures;

    public static void main(String[] args) {
        run("plain", false);
        run("luajava", true);
        runSuspendedCoroutines();
        if (failures > 0) {
            System.err.println("GlobalsRetentionTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("GlobalsRetentionTest: PASS");
    }

    private static void run(String label, boolean useLuajava) {
        int baseline = LuaTable.activeGlobalsCount();
        System.out.println("[" + label + "] baseline activeGlobals=" + baseline);

        // 创建期必须持强引用：分配量大时 GC 会在循环中途回收先建的状态，
        //   "登记数 == 基线+STATES"断言无法验证，故先强持、断言、再释放。
        ArrayList<Globals> strong = new ArrayList<>();
        ArrayList<WeakReference<Globals>> refs = new ArrayList<>();
        for (int i = 0; i < STATES; i++) {
            // luajava 轮必须用 bind.Platform - 它在标准库外追加 JavaLib（装载 `luajava` 表）
            Globals g = useLuajava
                    ? Platform.standardGlobals()
                    : LuaPlatform.standardGlobals();
            strong.add(g);
            // 真实使用：建表、intern 短串、编译执行，确保状态完整初始化
            g.execute("local t = {} for j = 1, 50 do t['k'..j] = j end return t");
            if (useLuajava) {
                // 走 JavaClass/JavaMethod/JavaConstructor 三个进程级缓存
                g.execute("local sb = luajava.newInstance('java.lang.StringBuilder')"
                        + " sb:append('x') sb:append(1) return sb:toString()");
                g.execute("local Sys = luajava.bindClass('java.lang.System')"
                        + " return Sys:currentTimeMillis()");
            }
            refs.add(new WeakReference<>(g));
        }
        int afterCreate = LuaTable.activeGlobalsCount();
        System.out.println("[" + label + "] after creating " + STATES
                + " states: activeGlobals=" + afterCreate);
        check(label + ": registry counts the new states", afterCreate == baseline + STATES);

        // 释放强引用 - 此后仅 refs 持弱引用，泄漏则表现为进程级 static 钉住
        strong.clear();
        strong = null;

        // 丢弃全部强引用（refs 仅持弱引用），促使 JVM 回收
        for (int attempt = 0; attempt < 5; attempt++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        int collected = 0;
        for (WeakReference<Globals> r : refs) {
            if (r.get() == null) collected++;
        }
        System.out.println("[" + label + "] JVM collected " + collected
                + " of " + STATES + " dropped states");
        check(label + ": dropped Globals are collectable by the JVM", collected == STATES);

        int afterDrop = LuaTable.activeGlobalsCount();
        System.out.println("[" + label + "] after dropping all strong refs: activeGlobals="
                + afterDrop);
        check(label + ": registry count falls back to baseline (leaked "
                + (afterDrop - baseline) + ")", afterDrop == baseline);
    }

    /**
     * 带挂起协程的状态经 {@link Globals#close()} 后须可回收。
     *
     * <p>判别力：缺陷态（close 不终止协程线程）下 park 着的线程仍是 GC 根，
     * 12 个状态一个都回收不掉。前置断言确保协程**真的处于挂起态**——若脚本失手让它
     * 跑完，线程本就会自行退出，"可回收"变成恒真。
     */
    private static void runSuspendedCoroutines() {
        String label = "suspended-coro";
        int baseline = LuaTable.activeGlobalsCount();
        System.out.println("[" + label + "] baseline activeGlobals=" + baseline);

        ArrayList<Globals> strong = new ArrayList<>();
        ArrayList<WeakReference<Globals>> refs = new ArrayList<>();
        int suspended = 0;
        for (int i = 0; i < STATES; i++) {
            Globals g = LuaPlatform.standardGlobals();
            strong.add(g);
            g.execute("CO = coroutine.create(function()"
                    + "  local held = {}"
                    + "  coroutine.yield()"
                    + "  return held"
                    + " end)"
                    + " coroutine.resume(CO)");
            if ("suspended".equals(g.execute("return coroutine.status(CO)").arg1().toJavaString())) {
                suspended++;
            }
            refs.add(new WeakReference<>(g));
        }
        check(label + ": all coroutines are actually suspended (" + suspended + "/" + STATES + ")",
                suspended == STATES);

        for (Globals g : strong) g.close();
        strong.clear();

        int collected = 0;
        for (int attempt = 0; attempt < 8 && collected < STATES; attempt++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            collected = 0;
            for (WeakReference<Globals> r : refs) {
                if (r.get() == null) collected++;
            }
        }
        System.out.println("[" + label + "] JVM collected " + collected + " of " + STATES);
        check(label + ": states with suspended coroutines are collectable after close()",
                collected == STATES);
        check(label + ": registry count falls back to baseline (leaked "
                        + (LuaTable.activeGlobalsCount() - baseline) + ")",
                LuaTable.activeGlobalsCount() == baseline);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  OK: " : "  FAIL: ") + name);
        if (!ok) failures++;
    }
}
