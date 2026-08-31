package org.luajvm.android.net;

import org.luajvm.android.api.LuaContext;
import org.luajvm.android.api.LuaGcable;
import org.luajvm.android.runtime.LuaLog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lua TCP 服务器
 */
@SuppressWarnings("unused")
public class LuaServer implements LuaGcable {

    private final Set<ClientConnection> mClients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    // volatile：acceptLoop 跑在池线程上，stop() 从 Lua/宿主线程改这个字段
    private volatile ServerSocket mServerSocket;
    private volatile OnReadLineListener mOnReadLineListener;
    private volatile boolean mIsGced;
    private volatile boolean mRunning;

    public LuaServer(LuaContext context) {
        if (context != null) context.regGc(this);
    }

    public LuaServer() {
    }

    /**
     * 启动服务器
     */
    public boolean start(int port) {
        if (mServerSocket != null) return false;
        ServerSocket server = null;
        try {
            server = new ServerSocket(port);
            mServerSocket = server;
            mRunning = true;
            mExecutor.submit(this::acceptLoop);
            return true;
        } catch (Exception e) {
            // 回滚：submit 被拒（gc() 已 shutdownNow）时若不关掉已 bind 的 ServerSocket，
            // 端口会一直占到进程退出，且开头的 mServerSocket 守卫让此后每次 start() 都返回 false
            closeQuietly(server);
            mServerSocket = null;
            mRunning = false;
            LuaLog.getInstance().addError("LuaServer", e);
            return false;
        }
    }

    private static void closeQuietly(ServerSocket server) {
        if (server == null) return;
        try {
            server.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 停止服务器
     */
    public boolean stop() {
        final ServerSocket server = mServerSocket;
        if (server == null) return false;
        mRunning = false;
        // 先摘字段再 close：acceptLoop 那边靠 null 检查干净退出，不会撞上「关闭中的 socket」
        mServerSocket = null;
        try {
            server.close();
        } catch (IOException e) {
            LuaLog.getInstance().addError("LuaServer", e);
        }
        for (ClientConnection client : mClients) client.close();
        mClients.clear();
        return true;
    }

    public void setOnReadLineListener(OnReadLineListener listener) {
        mOnReadLineListener = listener;
    }

    public boolean isRunning() {
        // 读进局部量：两次读之间 stop() 可能把字段置 null，直接二次读会把 NPE 抛给 Lua 调用方
        final ServerSocket server = mServerSocket;
        return server != null && !server.isClosed();
    }

    public Set<ClientConnection> getClients() {
        return Collections.unmodifiableSet(mClients);
    }

    /**
     * 向所有客户端广播消息
     */
    public void broadcast(String line) {
        for (ClientConnection client : mClients) {
            client.sendLine(line);
        }
    }

    @Override
    public void gc() {
        stop();
        mExecutor.shutdownNow();
        mIsGced = true;
    }

    @Override
    public boolean isGc() {
        return mIsGced;
    }

    // ==================== 内部逻辑 ====================

    private void acceptLoop() {
        while (mRunning) {
            // 读进局部量：过了 null 检查之后 stop() 才置 null 的话，直接用字段会 NPE，
            // 那个 NPE 会被下面的 catch 当成真错误写进 LuaLog，让「正常 stop()」看着像报错
            final ServerSocket server = mServerSocket;
            if (server == null || server.isClosed()) break;
            try {
                var socket = server.accept();
                var client = new ClientConnection(socket);
                mClients.add(client);
                mExecutor.submit(client::readLoop);
            } catch (SocketException ignored) {
                // 服务器关闭时正常退出
                break;
            } catch (Exception e) {
                if (mRunning) {
                    LuaLog.getInstance().addError("LuaServer", e);
                    // accept 异常即停摆：接受循环退出后不会再有新连接，此时须让
                    //   isRunning() 转为 false —— 它的判据是 socket，故除了置位
                    //   还要关掉并摘掉 socket，否则端口占着而服务已死
                    mRunning = false;
                    closeQuietly(mServerSocket);
                    mServerSocket = null;
                }
                break;
            }
        }
    }

    // ==================== 接口 ====================

    // Lua 侧靠 createProxy 把 Lua 函数转成本接口，单抽象方法是硬前提，交给 javac 守住
    @FunctionalInterface
    public interface OnReadLineListener {
        void onReadLine(LuaServer server, ClientConnection client, String line);
    }

    // ==================== 客户端连接 ====================

    public class ClientConnection implements AutoCloseable {
        private final Socket mSocket;
        // 写侧独占锁：write/newLine/flush 是可分步调用的公开 API，sendLine 又是三步复合操作，
        // 广播线程与读线程并发时不加锁会撕裂行边界。close() 刻意不参与这把锁，
        // 免得对端不收数据时 write 卡住导致 stop() 一起挂死。
        private final Object mWriteLock = new Object();
        // 构造期建好并 final：读线程赋值会留出「刚 accept 的客户端收不到 broadcast」的窗口
        private final BufferedWriter mWriter;
        private volatile boolean mClosed;

        public ClientConnection(Socket socket) {
            mSocket = socket;
            BufferedWriter writer = null;
            try {
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            } catch (IOException e) {
                LuaLog.getInstance().addError("LuaServer", e);
            }
            mWriter = writer;
        }

        private void readLoop() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(mSocket.getInputStream()))) {
                String line;
                while (!mClosed && (line = reader.readLine()) != null) {
                    final OnReadLineListener listener = mOnReadLineListener;
                    if (listener != null) {
                        listener.onReadLine(LuaServer.this, this, line);
                    }
                }
            } catch (Exception e) {
                if (!mClosed) LuaLog.getInstance().addError("LuaServer", e);
            } finally {
                close();
            }
        }

        public boolean write(String text) {
            if (mWriter == null || mClosed) return false;
            try {
                synchronized (mWriteLock) {
                    mWriter.write(text);
                }
                return true;
            } catch (Exception e) {
                LuaLog.getInstance().addError("LuaServer", e);
                return false;
            }
        }

        public boolean flush() {
            if (mWriter == null || mClosed) return false;
            try {
                synchronized (mWriteLock) {
                    mWriter.flush();
                }
                return true;
            } catch (Exception e) {
                LuaLog.getInstance().addError("LuaServer", e);
                return false;
            }
        }

        public boolean newLine() {
            if (mWriter == null || mClosed) return false;
            try {
                synchronized (mWriteLock) {
                    mWriter.newLine();
                    mWriter.flush();
                }
                return true;
            } catch (Exception e) {
                LuaLog.getInstance().addError("LuaServer", e);
                return false;
            }
        }

        public boolean sendLine(String line) {
            if (mWriter == null || mClosed) return false;
            try {
                // 三步必须整体原子，否则并发 broadcast 与回调里的 sendLine 会交叉写入
                synchronized (mWriteLock) {
                    mWriter.write(line);
                    mWriter.newLine();
                    mWriter.flush();
                }
                return true;
            } catch (Exception e) {
                LuaLog.getInstance().addError("LuaServer", e);
                return false;
            }
        }

        @Override
        public void close() {
            if (mClosed) return;
            mClosed = true;
            mClients.remove(this);
            // 先 flush：write() 与 flush() 是分开的两个 Lua 可见方法，
            // 「先 write 若干次、稍后再 flush」是被鼓励的用法，直接关 socket 会静默丢掉缓冲内容。
            // 对端已断时 flush 必然失败，这里吞掉，免得每次正常断连都给脚本推一条错误日志。
            if (mWriter != null) {
                try {
                    mWriter.flush();
                } catch (IOException ignored) {
                }
            }
            try {
                mSocket.close();
            } catch (IOException e) {
                LuaLog.getInstance().addError("LuaServer", e);
            }
        }

        public boolean isConnected() {
            return !mClosed && mSocket.isConnected() && !mSocket.isClosed();
        }
    }
}