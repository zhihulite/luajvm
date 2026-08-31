package org.luajvm.android.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 钉住 LuaUtil 的三个缺陷防回退。纯 JVM 可跑：只走成功路径，不触碰 android.util.Log。
 */
public class LuaUtilContractTest {

    /**
     * digest 不能用 {@code new BigInteger(1, md.digest()).toString(16)}：会吞掉前导零 ——
     * 首字节为 0x00 时 MD5 只剩 31 位。约 1/256 的输入命中。
     */
    @Test
    public void md5KeepsLeadingZeroAndIsAlways32Chars() throws Exception {
        // 找一个 MD5 首字节为 0x00 的输入，证明修复点真被覆盖（否则本测试空转）
        String seed = null;
        MessageDigest md = MessageDigest.getInstance("MD5");
        for (int i = 0; i < 100000; i++) {
            md.reset();
            String candidate = "probe-" + i;
            if ((md.digest(candidate.getBytes(StandardCharsets.UTF_8))[0] & 0xFF) == 0x00) {
                seed = candidate;
                break;
            }
        }
        assertNotNull("必须找到 MD5 首字节为 0x00 的输入，否则本门禁不具判别力", seed);

        String hex = LuaUtil.getFileMD5(new ByteArrayInputStream(seed.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(hex);
        assertEquals("MD5 十六进制必须恒为 32 位", 32, hex.length());
        assertTrue("首字节 0x00 必须保留前导零", hex.startsWith("00"));
    }

    @Test
    public void md5MatchesJdkForKnownInput() throws Exception {
        byte[] data = "hello luajvm".getBytes(StandardCharsets.UTF_8);
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] expected = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : expected) sb.append(String.format("%02x", b & 0xFF));

        assertEquals(sb.toString(), LuaUtil.getFileMD5(new ByteArrayInputStream(data)));
    }

    @Test
    public void sha1IsAlways40Chars() throws Exception {
        String hex = LuaUtil.getFileSha1(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        assertNotNull(hex);
        assertEquals(40, hex.length());
    }

    /**
     * getFileType 曾把 4 字节头转成 8 位十六进制再做**精确键**查表，于是魔数长度不等于 8 的
     * 类型永远匹配不上：jpg/gz（6 位）、bmp（4 位）、rtf/xml/html（10 位）、pdf（14 位）。
     */
    @Test
    public void detectsMagicsOfEveryLength() {
        assertEquals("jpg", typeOf(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A));   // 6 位
        assertEquals("gz", typeOf(0x1F, 0x8B, 0x08, 0x00, 0x00, 0x00, 0x00));    // 6 位
        assertEquals("bmp", typeOf(0x42, 0x4D, 0x36, 0x00, 0x00, 0x00, 0x00));   // 4 位
        assertEquals("png", typeOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A));   // 8 位
        assertEquals("gif", typeOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x00));   // 8 位
        assertEquals("rtf", typeOf(0x7B, 0x5C, 0x72, 0x74, 0x66, 0x31, 0x00));   // 10 位
        assertEquals("xml", typeOf(0x3C, 0x3F, 0x78, 0x6D, 0x6C, 0x20, 0x76));   // 10 位
        // pdf 魔数 255044462D312E = "%PDF-1." 共 7 字节，最长的一条
        assertEquals("pdf", typeOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E));
    }

    @Test
    public void unknownMagicStaysUnknown() {
        assertEquals("unknown", typeOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06));
    }

    /**
     * docx 与 wav 的魔数都是 8 位，前缀互不包含；此例确认按长度降序遍历没把短魔数
     * 误判成长魔数的前缀（504B0304 docx vs 无更长同前缀项）。
     */
    @Test
    public void longestPrefixWins() {
        assertEquals("docx", typeOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00));
    }

    /**
     * digest 与 getFileType 共用的 toHex 曾依赖静态共享状态；此例并发跑两者，
     * 结果必须与串行一致（也顺带压 LuaUtil 无静态可变缓冲）。
     */
    @Test
    public void concurrentDigestAndTypeDetectionAgreeWithSerial() throws Exception {
        final int threads = 8;
        final int rounds = 200;
        byte[] data = "concurrent-probe".getBytes(StandardCharsets.UTF_8);
        String expectedHex = LuaUtil.getFileMD5(new ByteArrayInputStream(data));
        assertNotNull(expectedHex);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final List<String> failures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < rounds; i++) {
                            String hex = LuaUtil.getFileMD5(new ByteArrayInputStream(data));
                            if (!expectedHex.equals(hex)) {
                                synchronized (failures) { failures.add("md5=" + hex); }
                            }
                            String type = typeOf(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A);
                            if (!"jpg".equals(type)) {
                                synchronized (failures) { failures.add("type=" + type); }
                            }
                        }
                    } catch (Exception e) {
                        synchronized (failures) { failures.add("ex=" + e); }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue("并发任务未在超时内结束", pool.awaitTermination(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals("并发结果与串行不一致: " + failures, 0, failures.size());
    }

    private static String typeOf(int... head) {
        byte[] bytes = new byte[head.length];
        for (int i = 0; i < head.length; i++) bytes[i] = (byte) head[i];
        InputStream in = new ByteArrayInputStream(bytes);
        return LuaUtil.getFileType(in);
    }
}
