package org.luajvm.android.host;

import android.app.Service;
import android.content.Intent;

import org.luajvm.android.engine.LuaEngine;

import org.luajvm.bind.JavaCall;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaValue;

import java.io.File;

/**
 * Service 委托类，继承 BaseDelegate 获得引擎生命周期管理能力。
 * 广播注册/注销与 Service 绑定三件套在 BaseDelegate。
 */
public class ServiceDelegate extends BaseDelegate {
    private String mLuaPath = "main.lua";

    public ServiceDelegate(Service service, String hostName) {
        super(service, new LuaEngine(service, service, hostName));
    }

    // ==================== 引擎生命周期 ====================

    public void initEngine() {
        String luaPath = getEngine().getLuaContext().getLuaPath();
        if (luaPath == null) {
            sendMsg("Lua path error: path is null");
            return;
        }
        var file = new File(luaPath);
        if (!file.canRead()) {
            sendMsg("Lua path error: file not readable");
            return;
        }
        mLuaPath = file.getAbsolutePath();
        setOnInitListener(new LuaEngine.OnInitListener() {
            @Override
            public void onSuccess() {
                runMainFunc();
            }

            @Override
            public void onError(Exception e) {
                sendError("Service Init", e);
            }
        });
        init(mLuaPath, null);
    }

    private void runMainFunc() {
        String name = new File(getEngine().getLuaPath()).getName();
        int idx = name.lastIndexOf(".");
        if (idx > 0) name = name.substring(0, idx);
        Globals g = getEngine().getLuaState();
        LuaValue f = g.get(name);
        if (!f.isfunction()) f = g.get("main");
        if (f.isfunction()) JavaCall.call(f);
    }

    // ==================== Service 生命周期回调 ====================
    // 以下三个包装当前零调用，可能经 Lua 桥接使用，保留。

    public void onNewIntent(Intent intent) {
        runFunc("onNewIntent", intent);
    }

    public void onStart() {
        runFunc("onStart");
    }

    public void onDestroy() {
        runFunc("onDestroy");
    }

    // ==================== 便捷方法 ====================

    public boolean runBooleanFunc(String name, Object... args) {
        return getEngine().runBooleanFunc(name, args);
    }

}
