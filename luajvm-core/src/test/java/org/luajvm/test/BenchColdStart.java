// java-only: 冷启动加载成本基准  -  源码编译 vs 预编译字节码 undump。
//
// 用 java.nio 读文件、直连 Parser/LuaChunk，不经 Lua io 层：
// RunLuaFile 会把 io.open 的路径按脚本所在目录再拼一次，绝对路径也被拼坏。
//
// 度量口径：仅测 load（编译或 undump），不执行模块体 - 真实 require 里执行模块体
// 会触发 Android API 调用，无法在 JVM 侧跑，且那部分成本与预编译无关。
package org.luajvm.test;

import org.luajvm.compiler.Parser;
import org.luajvm.core.Prototype;
import org.luajvm.vm.LuaChunk;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class BenchColdStart {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: BenchColdStart <asset-root>");
            System.exit(2);
        }
        Path root = Paths.get(args[0]);
        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".lua")).sorted().forEach(files::add);
        }
        System.out.printf("模块数 %d（%s）%n", files.size(), root);

        // -- 构造段（不计时）：读源码、预编译出字节码 --
        List<byte[]> srcs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<byte[]> bins = new ArrayList<>();
        List<byte[]> strips = new ArrayList<>();
        long srcBytes = 0, binBytes = 0, stripBytes = 0;
        int failed = 0;
        for (Path f : files) {
            byte[] src = Files.readAllBytes(f);
            try {
                Prototype p = parse(src, "@" + f.getFileName());
                byte[] bin = LuaChunk.dump(p, false);
                byte[] strip = LuaChunk.dump(p, true);
                // 自检：undump 回来必须成功
                LuaChunk.undump(bin);
                LuaChunk.undump(strip);
                srcs.add(src);
                names.add("@" + f.getFileName());
                bins.add(bin);
                strips.add(strip);
                srcBytes += src.length;
                binBytes += bin.length;
                stripBytes += strip.length;
            } catch (Throwable t) {
                failed++;
            }
        }
        int n = srcs.size();
        System.out.printf("可编译 %d（失败 %d），源码 %.1f KB%n", n, failed, srcBytes / 1024.0);
        System.out.printf("字节码 %.1f KB（%.2fx 源码），strip %.1f KB（%.2fx 源码）%n",
                binBytes / 1024.0, (double) binBytes / srcBytes,
                stripBytes / 1024.0, (double) stripBytes / srcBytes);

        double tSrc = bench("load_source", n, () -> {
            for (int i = 0; i < srcs.size(); i++) parse(srcs.get(i), names.get(i));
        });
        double tBin = bench("load_bytecode", n, () -> {
            for (byte[] b : bins) LuaChunk.undump(b);
        });
        double tStrip = bench("load_bc_strip", n, () -> {
            for (byte[] b : strips) LuaChunk.undump(b);
        });

        System.out.printf("%n源码 / 字节码      = %.2fx  => 省 %.1f%%%n", tSrc / tBin, (1 - tBin / tSrc) * 100);
        System.out.printf("源码 / strip字节码 = %.2fx  => 省 %.1f%%%n", tSrc / tStrip, (1 - tStrip / tSrc) * 100);
        System.out.printf("冷启动: %.1f ms -> %.1f ms（strip）%n", tSrc * 1000, tStrip * 1000);
    }

    private static Prototype parse(byte[] src, String name) {
        return Parser.parse(new InputStreamReader(
                new ByteArrayInputStream(src), StandardCharsets.ISO_8859_1), name);
    }

    // best-of-5，前两轮预热：JIT 未热的首轮读数普遍偏高
    private static double bench(String label, int n, Runnable r) {
        r.run();
        r.run();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            r.run();
            double e = (System.nanoTime() - t0) / 1e9;
            if (e < best) best = e;
        }
        System.out.printf("%-18s %.4fs (%.1f us/模块)%n", label, best, best * 1e6 / n);
        return best;
    }
}
