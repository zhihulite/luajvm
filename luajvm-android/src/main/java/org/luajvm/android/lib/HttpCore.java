package org.luajvm.android.lib;

import androidx.annotation.NonNull;

import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.runtime.LuaScheduler;
import org.luajvm.android.util.LuaUtil;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class HttpCore {
    public static Map<String, String> getDefaultHeaders() {
        return RequestExecutor.sDefaultHeaders;
    }

    // URLEncoder.encode(String, Charset) 是 API 33 起才有的重载，minSdk 24 上会 NoSuchMethodError；
    //   用抛检查异常的字符集名形态（API 1 起就有），UTF-8 是必备字符集，catch 分支不可达
    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    public static void setDefaultHeaders(Map<String, String> headers) {
        RequestExecutor.sDefaultHeaders = headers;
    }

    // ==================== 工厂方法 ====================

    public static HttpTask get(String url) {
        return request("GET", url, null, null);
    }

    public static HttpTask get(String url, Map<String, String> headers) {
        return request("GET", url, null, headers);
    }

    public static HttpTask head(String url) {
        return request("HEAD", url, null, null);
    }

    public static HttpTask head(String url, Map<String, String> headers) {
        return request("HEAD", url, null, headers);
    }

    public static HttpTask post(String url, Object body) {
        return request("POST", url, body, null);
    }

    public static HttpTask post(String url, Object body, Map<String, String> headers) {
        return request("POST", url, body, headers);
    }

    public static HttpTask put(String url, Object body) {
        return request("PUT", url, body, null);
    }

    public static HttpTask put(String url, Object body, Map<String, String> headers) {
        return request("PUT", url, body, headers);
    }

    /**
     * Lua 门面（{@link Http}/{@link SyncHttp}）的请求体取值。
     *
     * <p>字符串 body 直取原始 {@code byte[]}：{@code Object} 通用路径会经
     * {@code toJavaString()} 做 UTF-8 解码，二进制体在入口即被打坏；文本体的
     * 字节与其 UTF-8 编码相同，不受影响。
     *
     * <p>其余类型走通用转换。userdata 取出所载 Java 对象，故 {@code File} 载荷进
     * {@code formatBody} 的 File 分支。Lua 表经 {@code ObjectCoercion} 原样返回
     * {@code LuaTable}（不是 {@code Map}），{@code formatBody} 无对应分支即落
     * default 返回 null —— 表单体须用 {@code post(url, data, files)} 的 data 形参，
     * 那条路径按 Map 编码。
     */
    static Object luaBody(LuaValue body) {
        if (body == null || body.isnil()) return null;
        if (body.type() == LuaValue.TSTRING) return Coercion.toJava(body, byte[].class);
        return Coercion.toJava(body, Object.class);
    }

    public static HttpTask delete(String url) {
        return request("DELETE", url, null, null);
    }

    public static HttpTask delete(String url, Map<String, String> headers) {
        return request("DELETE", url, null, headers);
    }

    public static HttpTask upload(String url, Map<String, String> data, Map<String, String> files) {
        return uploadRequest(url, data, files, null);
    }

    public static HttpTask upload(String url, Map<String, String> data, Map<String, String> files, Map<String, String> headers) {
        return uploadRequest(url, data, files, headers);
    }

    // ==================== 内部统一入口 ====================

    private static HttpTask request(String method, String url, Object body, Map<String, String> headers) {
        return new HttpTask(method, url, headers, body, false);
    }

    private static HttpTask uploadRequest(String url, Map<String, String> data, Map<String, String> files, Map<String, String> headers) {
        return new HttpTask("POST", url, headers, new Object[]{data, files}, true);
    }

    // ==================== HttpTask ====================

    public static class HttpTask {
        final String method, url;
        final Map<String, String> headers;
        final Object body;
        final boolean multipart;

        HttpTask(String m, String url, Map<String, String> h, Object b, boolean multi) {
            method = m;
            this.url = url;
            headers = h;
            body = b;
            multipart = multi;
        }

        public HttpResult sync() {
            return HttpResult.from(RequestExecutor.execute(method, url, headers, body, multipart));
        }

        public void async(LuaValue cb) {
            LuaScheduler.getInstance().runOnIo(this::sync, r -> {
                try {
                    JavaCall.call(cb, r);
                } catch (Exception e) {
                    LuaConfig.logError("HttpCore", e);
                }
            });
        }
    }

    // ==================== HttpResult ====================

    public static class HttpResult extends Varargs {
        public final int code;
        public final String text;
        public final byte[] bytes;
        public final String cookies;
        public final Map<String, List<String>> headers;
        public final String contentType;
        private LuaValue mHeadersTable; // 惰性缓存：headers→Lua 表的转换只在首次 arg(4) 做一次

        HttpResult(int code, String text, byte[] bytes, String cookies,
                   Map<String, List<String>> headers, String contentType) {
            this.code = code;
            this.text = text;
            this.bytes = bytes;
            this.cookies = cookies != null ? cookies : "";
            this.headers = headers;
            this.contentType = contentType;
        }

        static HttpResult from(Response r) {
            return new HttpResult(r.code, r.textBody, r.rawBody, r.cookies, r.headers, r.contentType);
        }

        public boolean isOk() {
            return code >= 200 && code < 300;
        }

        public boolean isText() {
            return text != null;
        }

        public boolean isBinary() {
            return bytes != null;
        }

        @Override
        public int narg() {
            return 4;
        }

        @Override
        public LuaValue arg1() {
            return LuaValue.valueOf(text != null ? text : "");
        }

        @Override
        public LuaValue arg(int i) {
            return switch (i) {
                case 1 -> LuaValue.valueOf(text != null ? text : "");
                case 2 -> LuaValue.valueOf(cookies);
                case 3 -> LuaValue.valueOf(code);
                case 4 -> headersTable();
                default -> LuaValue.NIL;
            };
        }

        private LuaValue headersTable() {
            LuaValue t = mHeadersTable;
            if (t == null) {
                t = Coercion.toLua(headers);
                mHeadersTable = t;
            }
            return t;
        }

        @NonNull
        @Override
        public String toString() {
            return text != null ? text : "[binary " + (bytes != null ? bytes.length : 0) + " bytes]";
        }

        @Override
        public Varargs subargs(int start) {
            if (start < 1 || start > 4) return LuaValue.NIL;
            LuaValue[] v = new LuaValue[5 - start];
            for (int i = 0; i < v.length; i++) v[i] = arg(start + i);
            return LuaValue.varargsOf(v);
        }
    }

    // ==================== Response ====================

    static class Response {
        final int code;
        final String textBody;
        final byte[] rawBody;
        final String cookies;
        final Map<String, List<String>> headers;
        final String contentType;

        Response(int code, byte[] raw, String cookies, Map<String, List<String>> headers) {
            this.code = code;
            this.rawBody = raw;
            this.cookies = cookies;
            this.headers = headers;
            this.contentType = extractContentType(headers);
            this.textBody = isText() ? new String(raw, detectCharset(headers)) : null;
        }

        private static String extractContentType(Map<String, List<String>> h) {
            List<String> ct = headerValues(h, "Content-Type");
            if (ct == null || ct.isEmpty()) return null;
            String s = ct.get(0);
            int i = s.indexOf(';');
            return i == -1 ? s : s.substring(0, i);
        }

        private static Charset detectCharset(Map<String, List<String>> h) {
            List<String> ct = headerValues(h, "Content-Type");
            if (ct != null) {
                for (String s : ct) {
                    int i = s.indexOf("charset=");
                    if (i != -1) {
                        i += 8;
                        int e = s.indexOf(";", i);
                        try {
                            return Charset.forName(s.substring(i, e == -1 ? s.length() : e));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            return StandardCharsets.UTF_8;
        }

        boolean isText() {
            if (contentType == null) return true;
            String ct = contentType.toLowerCase();
            return ct.startsWith("text/")
                    || ct.contains("json")
                    || ct.contains("xml")
                    || ct.contains("javascript")
                    || ct.contains("x-www-form-urlencoded");
        }
    }

    // ==================== 执行器 ====================

    // 大小写无关取头：非规范服务器回 content-type/set-cookie 小写形态时精确 get 会静默丢失；
    //   部分实现把 status line 放在 null 键里，equalsIgnoreCase(null) 返回 false 天然安全
    private static List<String> headerValues(Map<String, List<String>> h, String name) {
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

        // 名为 RequestExecutor：避免与 java.util.concurrent.Executor 撞名，被误读为线程池提交。
    static class RequestExecutor {
        static final String BOUNDARY = "----qwertyuiopasdfghjklzxcvbnm";
        // volatile：Lua 线程 setDefaultHeaders、IO 线程 execute 读
        static volatile Map<String, String> sDefaultHeaders;

        static final TrustManager[] TRUST_ALL = new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] a, String b) {
            }

            public void checkServerTrusted(X509Certificate[] a, String b) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};

        static volatile SSLSocketFactory sTrustAllFactory;

        /** 惰性构建 trust-all factory，全程只建一次；失败返回 null 回落平台默认信任。 */
        static SSLSocketFactory trustAllFactory() {
            SSLSocketFactory f = sTrustAllFactory;
            if (f == null) {
                synchronized (RequestExecutor.class) {
                    f = sTrustAllFactory;
                    if (f == null) {
                        try {
                            SSLContext c = SSLContext.getInstance("TLS");
                            c.init(null, TRUST_ALL, new SecureRandom());
                            f = c.getSocketFactory();
                        } catch (Exception e) {
                            LuaConfig.logError("HttpCore", e);
                            return null;
                        }
                        sTrustAllFactory = f;
                    }
                }
            }
            return f;
        }

        static Response execute(String m, String url, Map<String, String> h, Object b, boolean multipart) {
            HttpURLConnection conn = null;
            Map<String, Long> sizes = new LinkedHashMap<>();
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                // trust-all 只在配置开启时逐连接装配，不做进程级 setDefaultSSLSocketFactory：
                //   全局替换会波及宿主自身网络栈，且静态块时机早于宿主写入配置
                if (conn instanceof HttpsURLConnection https && LuaConfig.isSslTrustAll()) {
                    SSLSocketFactory f = trustAllFactory();
                    if (f != null) https.setSSLSocketFactory(f);
                }
                conn.setConnectTimeout(LuaConfig.getHttpTimeout());
                // 无 readTimeout 时 socket 读可以无限挂起：future.cancel(true) 打不断
                // 阻塞读（interrupt 不碰 socket），cachedThreadPool 侧线程/连接无上界堆积
                conn.setReadTimeout(LuaConfig.getHttpTimeout());
                conn.setDoInput(true);
                put(conn, sDefaultHeaders);
                put(conn, h);
                conn.setRequestMethod(m);
                byte[] post = null;
                Object[] multi = (multipart && b instanceof Object[] arr) ? arr : null;
                if (multi != null) {
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
                } else if (b != null) {
                    post = formatBody(b);
                    if (b instanceof Map) {
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    }
                }
                if (post != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Length", String.valueOf(post.length));
                } else if (multi != null) {
                    // 流式写出：setFixedLengthStreamingMode 需先按文件实际大小算总长，
                    //   避免把整个 multipart 体（尤其文件部分）全量读进内存。
                    //   算长度时把每个文件的大小快照进 sizes，写出时按快照写足，
                    //   否则文件在两步之间被追加/截短就会以"写多了/写少了"断连
                    conn.setDoOutput(true);
                    conn.setFixedLengthStreamingMode(multipartLength(multi, sizes));
                }
                conn.connect();
                if (post != null) try (OutputStream os = conn.getOutputStream()) {
                    os.write(post);
                } else if (multi != null) try (OutputStream os = conn.getOutputStream()) {
                    writeMultipart(os, multi, sizes);
                }
                return handleResponse(conn);
            } catch (Exception e) {
                // 空消息回退 e.toString()：message 为 null 时不能破坏「code=-1 带错误文本」契约
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                return new Response(-1, msg.getBytes(StandardCharsets.UTF_8), null, Collections.emptyMap());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private static Response handleResponse(HttpURLConnection c) throws IOException {
            int code = c.getResponseCode();
            Map<String, List<String>> h = c.getHeaderFields();
            byte[] body = readAllBytes(c);
            return new Response(code, body, cookies(h), h);
        }

        private static byte[] readAllBytes(HttpURLConnection c) throws IOException {
            try (InputStream i = c.getInputStream()) {
                return readStream(i);
            } catch (IOException e) {
                try (InputStream i = c.getErrorStream()) {
                    if (i != null) return readStream(i);
                    throw e;
                }
            }
        }

        private static byte[] readStream(InputStream is) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }

        private static String cookies(Map<String, List<String>> h) {
            List<String> l = headerValues(h, "Set-Cookie");
            if (l == null || l.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String x : l) sb.append(x).append(";");
            return sb.toString();
        }

        private static byte[] formatBody(Object b) throws IOException {
            switch (b) {
                case String s -> {
                    return s.getBytes(StandardCharsets.UTF_8);
                }
                case byte[] bytes -> {
                    return bytes;
                }
                case File file -> {
                    try (InputStream in = new FileInputStream(file)) {
                        return LuaUtil.readAll(in);
                    }
                }
                case Map<?, ?> ignored -> {
                    @SuppressWarnings("unchecked") Map<String, String> m = (Map<String, String>) b;
                    return formatMap(m).getBytes(StandardCharsets.UTF_8);
                }
                case null, default -> {
                    return null;
                }
            }
        }

        // form 体按 application/x-www-form-urlencoded 编码：裸拼会让 &/=/%/非 ASCII 静默错乱
        private static String formatMap(Map<String, String> m) {
            if (m == null || m.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet())
                sb.append(urlEncode(e.getKey())).append("=")
                        .append(urlEncode(e.getValue())).append("&");
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }

        private static String dataPartHeader(String name) {
            return String.format("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n", BOUNDARY, name);
        }

        private static String filePartHeader(String name, String filename) {
            return String.format("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n",
                    BOUNDARY, name, filename, "application/octet-stream");
        }

        private static String endBoundary() {
            return String.format("--%s--\r\n", BOUNDARY);
        }

        private static int utf8Length(String s) {
            return s.getBytes(StandardCharsets.UTF_8).length;
        }

        /** 总长须与 writeMultipart 写出的字节数严格一致：文件大小快照进 sizes，不读内容。 */
        @SuppressWarnings("unchecked")
        private static long multipartLength(Object[] arr, Map<String, Long> sizes) throws IOException {
            Map<String, String> data = (Map<String, String>) arr[0];
            Map<String, String> files = (Map<String, String>) arr[1];
            long len = 0;
            if (data != null) {
                for (Map.Entry<String, String> e : data.entrySet()) {
                    len += utf8Length(dataPartHeader(e.getKey()))
                            + utf8Length(e.getValue()) + utf8Length("\r\n");
                }
            }
            if (files != null) {
                for (Map.Entry<String, String> e : files.entrySet()) {
                    String path = e.getValue();
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    File f = new File(path);
                    if (!f.isFile() || !f.canRead()) throw new FileNotFoundException(path);
                    long size = f.length();
                    sizes.put(path, size);
                    len += utf8Length(filePartHeader(e.getKey(), name)) + size + utf8Length("\r\n");
                }
            }
            return len + utf8Length(endBoundary());
        }

        @SuppressWarnings("unchecked")
        private static void writeMultipart(OutputStream os, Object[] arr, Map<String, Long> sizes) throws IOException {
            Map<String, String> data = (Map<String, String>) arr[0];
            Map<String, String> files = (Map<String, String>) arr[1];
            if (data != null) {
                for (Map.Entry<String, String> e : data.entrySet()) {
                    os.write(dataPartHeader(e.getKey()).getBytes(StandardCharsets.UTF_8));
                    os.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    os.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
            }
            if (files != null) {
                for (Map.Entry<String, String> e : files.entrySet()) {
                    String path = e.getValue();
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    os.write(filePartHeader(e.getKey(), name).getBytes(StandardCharsets.UTF_8));
                    Long size = sizes.containsKey(path) ? sizes.get(path) : Long.valueOf(0L);
                    writeExact(os, path, size);
                    os.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
            }
            os.write(endBoundary().getBytes(StandardCharsets.UTF_8));
        }

        /** 按算长度时的快照写足 declared 字节：文件被追加则截断，被截短则补零，连接不断。 */
        private static void writeExact(OutputStream os, String path, long declared) throws IOException {
            long written = 0;
            byte[] buf = new byte[8192];
            try (InputStream in = new FileInputStream(path)) {
                while (written < declared) {
                    int want = (int) Math.min(buf.length, declared - written);
                    int n = in.read(buf, 0, want);
                    if (n == -1) break;
                    os.write(buf, 0, n);
                    written += n;
                }
            }
            if (written < declared) {
                LuaConfig.log("HttpCore multipart: " + path + " 变短，补 " + (declared - written) + " 字节");
                byte[] pad = new byte[8192];
                while (written < declared) {
                    int n = (int) Math.min(pad.length, declared - written);
                    os.write(pad, 0, n);
                    written += n;
                }
            }
        }

        private static void put(HttpURLConnection c, Map<String, String> h) {
            if (h != null) for (Map.Entry<String, String> e : h.entrySet())
                c.setRequestProperty(e.getKey(), e.getValue());
        }
    }
}
