package org.luajvm.android.host;

/**
 * 新文档模式 Activity。
 * <p>
 * 每次启动在最近任务列表中创建独立条目，
 * 适用于多窗口编辑等场景。
 */
public class LuaActivityX extends LuaActivity {

    @Override
    public void finish() {
        finishAndRemoveTask();
    }

    @Override
    public void finish(boolean finishTask) {
        if (finishTask) finishAndRemoveTask();
        else super.finish();
    }
}
