package org.luajvm.android.lib;

import android.content.Intent;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.luajvm.android.api.CallLuaFunction;
import org.luajvm.android.api.LuaSafHost;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.util.LuaUtil;

import org.luajvm.bind.Coercion;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

@SuppressWarnings("unused")
public class saf {
    private final LuaSafHost mHost;
    private DocumentFile mDocumentFile;

    public saf(LuaSafHost host) {
        mHost = host;
        String treeUri = (String) mHost.getSharedData("_DOCUMENT_TREE", null);
        if (treeUri != null) {
            try {
                mDocumentFile = DocumentFile.fromTreeUri(mHost.getContext(), Uri.parse(treeUri));
            } catch (Exception e) {
                LuaConfig.logError("saf", e);
            }
        }
    }

    public DocumentFile get() {
        return mDocumentFile;
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "已授权时同步回调；未授权时先经 select() 的选择器回调")
    public void list(LuaFunction callback) {
        if (mDocumentFile == null) {
            select(new LuaFunction() {
                @Override
                public Varargs call(Varargs args) {
                    // 授权失败/用户取消时 mDocumentFile 仍为 null，此时不能重试：
                    // 无条件 list(callback) 会再次弹选择器，用户按返回键就出不去
                    if (mDocumentFile == null) return LuaValue.NONE;
                    list(callback);
                    return LuaValue.NONE;
                }
            });
            return;
        }

        LuaCall.invoke(callback, Coercion.toLua(mDocumentFile.listFiles()));
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "经 LuaActivity.openDocumentTree 的 onActivityResult")
    public void select(LuaFunction callback) {
        mHost.openDocumentTree(new LuaFunction() {
            @Override
            public Varargs call(Varargs args) {
                LuaValue resultArg = args.arg1();
                Intent intent = (Intent) resultArg.touserdata();
                if (intent == null || intent.getData() == null) {
                    LuaCall.invoke(callback, LuaValue.NIL);
                    return LuaValue.NONE;
                }
                try {
                    mDocumentFile = DocumentFile.fromTreeUri(mHost.getContext(), intent.getData());
                    mHost.setSharedData("_DOCUMENT_TREE", intent.getData().toString());
                    LuaCall.invoke(callback, LuaValue.varargsOf(Coercion.toLua(intent), Coercion.toLua(mDocumentFile)));
                } catch (Exception e) {
                    LuaConfig.logError("saf", e);
                    LuaCall.invoke(callback, LuaValue.NIL);
                }
                return LuaValue.NONE;
            }
        });
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "经 LuaActivity.getDocument 的 onActivityResult")
    public void read(LuaFunction callback) {
        mHost.getDocument("*/*", new LuaFunction() {
            @Override
            public Varargs call(Varargs args) {
                LuaValue resultArg = args.arg1();
                Intent intent = (Intent) resultArg.touserdata();
                if (intent == null || intent.getData() == null) {
                    LuaCall.invoke(callback, LuaValue.NIL);
                    return LuaValue.NONE;
                }
                try (InputStream in = mHost.getContext().getContentResolver().openInputStream(intent.getData())) {
                    byte[] content = LuaUtil.readAll(Objects.requireNonNull(in));
                    LuaCall.invoke(callback, LuaValue.varargsOf(Coercion.toLua(intent), LuaString.valueOf(content)));
                } catch (Exception e) {
                    LuaConfig.logError("saf", e);
                    LuaCall.invoke(callback, LuaValue.NIL);
                }
                return LuaValue.NONE;
            }
        });
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "经 LuaActivity.createDocument 的 onActivityResult")
    public void save(String name, LuaString bs, LuaFunction function) {
        mHost.createDocument("*/*", name, new LuaFunction() {
            @Override
            public Varargs call(Varargs args) {
                LuaValue arg = args.arg1();
                Intent intent = (Intent) arg.touserdata();
                if (intent == null || intent.getData() == null) {
                    // 用户取消：只回 nil，无第二个参数
                    LuaCall.invoke(function, LuaValue.NIL);
                    return LuaValue.NONE;
                }
                try (OutputStream out = mHost.getContext().getContentResolver().openOutputStream(intent.getData())) {
                    // LuaUtil.save 返回真实写出结果：写失败不再把 intent 当成功回传。
                    //   失败一律 (nil, 原因)：把错误文本放 arg1 会被"arg1 非 nil 即成功"的脚本读成成功
                    if (out != null && LuaUtil.save(out, bs)) {
                        LuaCall.invoke(function, Coercion.toLua(intent));
                    } else {
                        LuaCall.invoke(function, Varargs.of(LuaValue.NIL,
                                LuaValue.valueOf(out == null ? "openOutputStream returned null" : "write failed")));
                    }
                } catch (Exception e) {
                    LuaConfig.logError("saf", e);
                    LuaCall.invoke(function, Varargs.of(LuaValue.NIL, LuaValue.valueOf(e.toString())));
                }
                return LuaValue.NONE;
            }
        });
    }

    public LuaValue read(String name) {
        if (mDocumentFile == null) return LuaValue.NIL;
        DocumentFile f = mDocumentFile.findFile(name);
        if (f == null) return LuaValue.NIL;
        try (InputStream in = mHost.getContext().getContentResolver().openInputStream(f.getUri())) {
            if (in != null) return LuaString.valueOf(LuaUtil.readAll(in));
        } catch (Exception e) {
            LuaConfig.logError("saf", e);
        }
        return LuaValue.NIL;
    }

    public LuaValue save(String name, LuaString bs) {
        if (mDocumentFile == null) {
            select(new LuaFunction() {
                @Override
                public Varargs call(Varargs args) {
                    // 授权失败/用户取消时 mDocumentFile 仍为 null：无条件重调 save 会再弹选择器，
                    //   用户按返回键就出不去（对齐 list() 的防重入守卫）
                    if (mDocumentFile == null) return LuaValue.NONE;
                    save(name, bs);
                    return LuaValue.NONE;
                }
            });
            return LuaValue.FALSE;
        }

        DocumentFile f = mDocumentFile.createFile("", name);
        if (f == null) return LuaValue.FALSE;
        try (OutputStream out = mHost.getContext().getContentResolver().openOutputStream(f.getUri())) {
            if (out != null) {
                return LuaUtil.save(out, bs) ? LuaValue.TRUE : LuaValue.FALSE;
            }
        } catch (Exception e) {
            LuaConfig.logError("saf", e);
        }
        return LuaValue.FALSE;
    }
}
