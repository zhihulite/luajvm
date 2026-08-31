// ref: lutf8lib.c
// diff: byte[]替代const char*; int[] result替代指针参数; long处理码点
package org.luajvm.lib;

public final class Utf8Support {
    private static final long[] LIMITS = {0xFFFFFFFFL, 0x80L, 0x800L, 0x10000L, 0x200000L, 0x4000000L};

    private Utf8Support() {
    }

    // lutf8lib.c: utf8_decode
    // java diff: C 加 `c >= 0xfe` 早返回避免 shift 溢出，并移除 count > 5 检查
    static int utf8Decode(byte[] bytes, int offset, int limit, int[] result, boolean strict) {
        int len = limit;
        if (offset >= len) {
            result[0] = 0;
            return -1;
        }
        int c = bytes[offset] & 0xFF;
        if (c < 0x80) {
            result[0] = 1;
            return c;
        }
        // lutf8lib.c  -  c >= 1111 1110b 需要 6+ continuation bytes，早返回
        if (c >= 0xfe) {
            result[0] = 0;
            return -1;
        }
        // 多字节序列
        int count = 0;
        long res = 0;
        while ((c & 0x40) != 0) {
            count++;
            if (offset + count >= len) {
                result[0] = 0;
                return -1;
            }
            int cc = bytes[offset + count] & 0xFF;
            if ((cc & 0xC0) != 0x80) {
                result[0] = 0;
                return -1;
            }
            res = (res << 6) | (cc & 0x3F);
            c <<= 1;
        }
        // lutf8lib.c  -  lua_assert(count <= 5)（由 0xfe 早返回保证）
        res |= ((long) (c & 0x7F) << (count * 5));
        if (res > 0x7FFFFFFFL || res < LIMITS[count]) {
            result[0] = 0;
            return -1;
        }
        if (strict) {
            if (res > 0x10FFFFL || (res >= 0xD800L && res <= 0xDFFFL)) {
                result[0] = 0;
                return -1;
            }
        }
        result[0] = count + 1;
        return (int) res;
    }
}
