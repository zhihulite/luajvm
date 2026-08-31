// 超版本 API 的兼容实现（构建期字节码重写的落点，只用 API 1 的 java.*）
package org.luajvm.android.compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

/** 超版本 API 的静态等价实现。 */
public final class JavaApiCompat {

    private JavaApiCompat() {
    }

    /** InputStream.readAllBytes（Java 9 / Android API 33）等价实现。 */
    public static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    /** OutputStream.nullOutputStream（Java 11 / Android API 33）等价实现。 */
    public static OutputStream nullOutputStream() {
        return new OutputStream() {
            @Override
            public void write(int b) {
            }
        };
    }

    /** Executable.getParameterTypes（API 26）分派到 Method/Constructor 的 API 1 接口。 */
    public static Class<?>[] parameterTypes(Member m) {
        if (m instanceof Method md) return md.getParameterTypes();
        if (m instanceof Constructor<?> c) return c.getParameterTypes();
        throw new IllegalArgumentException("unsupported member: " + m);
    }

    /** Executable.getModifiers（API 26）经 Member（API 1）。 */
    public static int modifiers(Member m) {
        return m.getModifiers();
    }

    /** ByteArrayOutputStream.toString(Charset)（API 33）等价实现。 */
    public static String toString(ByteArrayOutputStream baos, java.nio.charset.Charset charset) {
        return new String(baos.toByteArray(), charset);
    }

    /** ProcessBuilder.redirectXxx（API 26）等价：保持默认 PIPE 流向（Android 无控制台）。 */
    public static ProcessBuilder noRedirect(ProcessBuilder pb, Object ignored) {
        return pb;
    }

    /** ThreadLocal.withInitial(Supplier)（API 26）等价实现。 */
    public static <T> ThreadLocal<T> withInitial(java.util.function.Supplier<? extends T> supplier) {
        return new ThreadLocal<T>() {
            @Override
            protected T initialValue() {
                return supplier.get();
            }
        };
    }

    /** Arrays.equals(byte[],int,int,byte[],int,int)（API 33）等价实现。 */
    public static boolean equals(byte[] a, int aFrom, int aTo, byte[] b, int bFrom, int bTo) {
        int aLen = aTo - aFrom;
        if (aLen != bTo - bFrom) return false;
        for (int i = 0; i < aLen; i++) {
            if (a[aFrom + i] != b[bFrom + i]) return false;
        }
        return true;
    }

    /** MatchException（API 35）等价：模式匹配失败统一以 IllegalStateException 抛出。 */
    public static RuntimeException matchException(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    /** AccessibleObject.isAccessible/setAccessible（Member 经 AccessibleObject 分派）。 */
    public static void trySetAccessible(Member m) {
        if (m instanceof AccessibleObject ao && !ao.isAccessible()) {
            try {
                ao.setAccessible(true);
            } catch (Exception ignored) {
            }
        }
    }
}
