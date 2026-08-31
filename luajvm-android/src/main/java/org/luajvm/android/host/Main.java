package org.luajvm.android.host;

/**
 * 默认入口 Activity。
 */
public class Main extends LuaActivity {

    @Override
    protected void onVersionChanged(String newVersionName, String oldVersionName) {
        runFunc("onVersionChanged", newVersionName, oldVersionName);
    }
}
