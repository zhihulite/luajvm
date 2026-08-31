package org.luajvm.test;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaGC;
import org.luajvm.core.LuaTable;
import org.luajvm.vm.LuaPlatform;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个<b>长期存活</b> Globals 内部的 GC 对象登记表是否有界。
 *
 * <p>与 {@link GlobalsRetentionTest} 的分工：那个测"Globals 整体能否被回收"
 * （宿主换 state 的场景）；本测测"Globals <b>不</b>被回收时，它内部的登记表会不会
 * 无界增长"。后者才是 Android 单 Activity 长驻脚本的真实形态  -
 * 一个 Globals 活几小时，反复 load()/执行几万次，若 {@code gc.allTables} /
 * {@code allProtos} / {@code allClosures} / {@code longStrings} 仅 add 不 remove，
 * 就是真泄漏，而 GlobalsRetentionTest 完全测不到（它从不让单个 state 长期工作）。
 *
 * <p><b>判别力前置</b>：先断言"反复 load 确实让登记表涨上去了"，
 * 否则用例可能因登记表恒为 0 而恒真通过。只有峰值确实远高于基线，
 * "GC 后回落"这条断言才有意义。
 */
public final class LongLivedGlobalsTest {
    /** 轮数：每轮编译并执行一个独立 chunk，产生 Prototype/Closure/Table/长串各若干。 */
    private static final int ROUNDS = 3000;
    private static int failures;

    public static void main(String[] args) throws Exception {
        Globals g = LuaPlatform.standardGlobals();

        int[] base = registrySizes(g);
        System.out.println("基线登记数: " + fmt(base));

        // -- 反复 load + 执行：模拟长驻宿主里不断跑新脚本 --
        // 每轮都是不同源码，故必然产生新 Prototype（不会被 chunk 缓存复用）；
        // 建表 + 拼长串确保 allTables / longStrings 也进登记表。
        // [峰值必须在循环内取样]增量 GC 在循环期间就持续回收，循环结束后再取样
        //   峰值恒为个位数 => 前置断言失败、用例无判别力。故逐轮记 running max。
        int[] peak = base.clone();
        for (int i = 0; i < ROUNDS; i++) {
            g.execute("local t = {}\n"
                    + "for j = 1, 8 do t[j] = 'v" + i + "_' .. j end\n"
                    + "local f = function() return t end\n"
                    + "return f() ~= nil\n");
            if ((i & 63) == 0) {   // 每 64 轮取样一次（反射取样有成本）
                int[] cur = registrySizes(g);
                for (int j = 0; j < peak.length; j++) {
                    if (cur[j] > peak[j]) peak[j] = cur[j];
                }
            }
        }

        System.out.println("峰值登记数(循环内取样): " + fmt(peak));

        // 前置断言：登记表必须确实涨过，否则本用例无判别力（可能登记逻辑未跑到）。
        //   注意阈值不能按 ROUNDS 定  -  增量 GC 会持续回收，稳态占用与 ROUNDS 无关。
        //   这里仅要求"确实有对象进过登记表"。
        check(peak[2] > base[2],
                "前置：" + ROUNDS + " 轮应有 Closure 进过登记表（基线 " + base[2]
                        + " -> 峰值 " + peak[2] + "）");
        check(peak[3] > base[3],
                "前置：" + ROUNDS + " 轮应有 Prototype 进过登记表（基线 " + base[3]
                        + " -> 峰值 " + peak[3] + "）");

        // -- 核心断言：稳态有界 --
        // 这才是"长驻 Globals 不泄漏"的判据：跑 3000 轮后登记表不应与轮数同阶增长。
        // 若 sweep 的 removeIf 失效，allProtos 会累积至 ~3000。
        int[] steady = registrySizes(g);
        System.out.println("稳态登记数(循环后): " + fmt(steady));
        check(steady[3] < ROUNDS / 10,
                "稳态 Prototype 登记数应远小于轮数（" + ROUNDS + " 轮 -> 实测 "
                        + steady[3] + "，若接近轮数则 sweep 未摘除）");
        check(steady[0] < base[0] + ROUNDS / 10,
                "稳态 allTables 应有界（基线 " + base[0] + " -> 实测 " + steady[0] + "）");

        // -- 丢弃所有可达引用后 fullGC：登记表应回落 --
        // 那些 Prototype/Closure/Table 在 execute 返回后已无 Lua 侧可达路径，
        // sweep 的 removeIf 应将它们摘掉。
        for (int i = 0; i < 4; i++) LuaGC.fullGC(g, false);

        int[] after = registrySizes(g);
        System.out.println("GC 后登记数: " + fmt(after));

        // fullGC 后应回到基线附近：那些 Prototype/Closure/Table 在 execute 返回后
        // 已无 Lua 侧可达路径，sweep 的 removeIf 应将它们全部摘掉。
        check(after[3] <= base[3] + 2,
                "fullGC 后 Prototype 登记表应回到基线附近（基线 " + base[3]
                        + " -> 实测 " + after[3] + "）");
        check(after[2] <= base[2] + 2,
                "fullGC 后 allClosures 应回到基线附近（基线 " + base[2]
                        + " -> 实测 " + after[2] + "）");
        check(after[0] <= base[0] + 2,
                "fullGC 后 allTables 应回到基线附近（基线 " + base[0]
                        + " -> 实测 " + after[0] + "）");

        // Globals 本身仍强可达（本方法持有 g） - 确认我们测的确实是"长期存活"场景，
        // 而非 Globals 已被回收导致登记表整体消失的假通过。
        check(LuaTable.activeGlobalsCount() >= 1,
                "前置：Globals 应仍存活（否则本用例测的不是长驻场景）");

        if (failures > 0) {
            System.err.println("LongLivedGlobalsTest: " + failures + " FAILED");
            System.exit(1);
        }
        System.out.println("LongLivedGlobalsTest: PASS");
    }

    /** 反射读 gc 子结构里的四张登记表长度：{allTables, longStrings, allClosures, allProtos}。 */
    private static int[] registrySizes(Globals g) throws Exception {
        Object gc = get(g, "gc");
        return new int[]{
                sizeOf(gc, "allTables"),
                sizeOf(gc, "longStrings"),
                sizeOf(gc, "allClosures"),
                sizeOf(gc, "allProtos"),
        };
    }

    private static Object get(Object owner, String name) throws Exception {
        Field f = findField(owner.getClass(), name);
        f.setAccessible(true);
        return f.get(owner);
    }

    private static int sizeOf(Object gc, String name) throws Exception {
        Object v = get(gc, name);
        return ((List<?>) v).size();
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static String fmt(int[] a) {
        return "allTables=" + a[0] + " longStrings=" + a[1]
                + " allClosures=" + a[2] + " allProtos=" + a[3];
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("  OK: " + what);
        } else {
            System.out.println("  FAIL: " + what);
            failures++;
        }
    }

    private LongLivedGlobalsTest() {
    }

    // 未使用但保留：便于将来扩展成多 state 并发长驻场景
    private static final ArrayList<Globals> KEEP = new ArrayList<>();
}
