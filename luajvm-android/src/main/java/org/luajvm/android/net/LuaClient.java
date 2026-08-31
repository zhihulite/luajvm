package org.luajvm.android.net;

import org.luajvm.android.api.LuaContext;
import org.luajvm.android.api.LuaGcable;
import org.luajvm.android.runtime.LuaConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Objects;

/**
 * Lua TCP 客户端
 * 供 Lua 脚本做 Socket 网络通信
 */
@SuppressWarnings("unused")
public class LuaClient implements LuaGcable {
    private OnReadLineListener mOnReadLineListener;
    // volatile：start/stop/gc 可能来自 Lua 线程或宿主销毁线程，读循环在自己的线程上读这些字段
    private volatile Socket mSocket;
    private volatile BufferedReader mReader;
    private volatile BufferedWriter mWriter;
    private volatile boolean mGc;
    // 读循环的结束原因：isConnected() 在对端 reset 后仍为 true，Lua 只能靠它发现链路已死
    private volatile String mLastError;

    public LuaClient(LuaContext context) {
        if (context != null) {
            context.regGc(this);
        }
    }

    public LuaClient() {
    }

    public boolean start(String host, int port) {
        // 复用 isConnected()：Socket.isConnected() 只表示「曾经连上过」，close() 不会清它，
        // 单看它会让 stop() 之后的重连永远被拒。
        if (isConnected())
            return false;

        Socket socket = null;
        try {
            socket = new Socket(host, port);
            mSocket = socket;
            mLastError = null;
            mReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            mWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            new Thread(this::readLoop, "LuaClient-read").start();
            return true;
        } catch (IOException e) {
            // 回滚：socket 已连上但取流失败时既要关掉（否则漏 fd），也要摘掉字段（否则对象永久不可重连）
            closeQuietly(socket);
            mSocket = null;
            LuaConfig.logError("LuaClient", e);
            return false;
        }
    }

    public boolean stop() {
        final Socket socket = mSocket;
        if (socket == null)
            return false;
        // 先 flush：BufferedWriter 里未 flush 的内容会随 socket.close() 一起丢
        final BufferedWriter writer = mWriter;
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                LuaConfig.logError("LuaClient", e);
            }
        }
        try {
            socket.close();
            return true;
        } catch (IOException e) {
            LuaConfig.logError("LuaClient", e);
            return false;
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public void setOnReadLineListener(OnReadLineListener listener) {
        mOnReadLineListener = listener;
    }

    @Override
    public void gc() {
        stop();
        mGc = true;
    }

    @Override
    public boolean isGc() {
        return mGc;
    }

    public boolean write(String text) {
        final BufferedWriter writer = mWriter;
        if (writer == null) return false;
        try {
            writer.write(text);
            return true;
        } catch (Exception e) {
            LuaConfig.logError("LuaClient", e);
            return false;
        }
    }

    public boolean flush() {
        final BufferedWriter writer = mWriter;
        if (writer == null) return false;
        try {
            writer.flush();
            return true;
        } catch (Exception e) {
            LuaConfig.logError("LuaClient", e);
            return false;
        }
    }

    public boolean newLine() {
        final BufferedWriter writer = mWriter;
        if (writer == null) return false;
        try {
            writer.newLine();
            writer.flush();
            return true;
        } catch (Exception e) {
            LuaConfig.logError("LuaClient", e);
            return false;
        }
    }

    public boolean isConnected() {
        final Socket socket = mSocket;
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    // Lua 侧靠 createProxy 把 Lua 函数转成本接口，单抽象方法是硬前提，交给 javac 守住
    @FunctionalInterface
    public interface OnReadLineListener {
        void onReadLine(String line);
    }

    /**
     * 读循环：跑在 LuaClient-read 线程上，逐行 readLine 并回调 Lua。
     */
    private void readLoop() {
        // 读进局部量：重连时 start() 会换掉字段，本线程只能读自己那一份
        final BufferedReader reader = mReader;
        final Socket socket = mSocket;
        if (reader == null) return;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                final OnReadLineListener listener = mOnReadLineListener;
                if (listener != null)
                    listener.onReadLine(line);
            }
            // 对端正常关闭：readLine 返回 null，isConnected() 仍为 true，记下来供 getLastError 查
            mLastError = "closed by peer";
        } catch (Exception e) {
            // stop()/gc() 主动断开是正常路径，静默退出；真实故障才进日志。
            //   异常文本不回调 onReadLine —— Lua 侧会把它当普通数据行消费。
            //   判的是本循环自己那个 socket：读字段会在重连换掉字段后把真故障误判成正常停止
            if (mGc || socket == null || socket.isClosed()) return;
            mLastError = Objects.requireNonNullElse(e.getMessage(), e.toString());
            LuaConfig.logError("LuaClient", e);
        }
    }

    /**
     * 最近一次读循环的结束原因，Lua 侧据此判断链路是否已死。
     * 无异常结束返回 {@code "closed by peer"}，从未断开返回 nil。
     */
    public String getLastError() {
        return mLastError;
    }
}
