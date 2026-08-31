package org.luajvm.android.widget;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.AdapterView;
import android.widget.BaseAdapter;

import org.luajvm.android.api.LuaContext;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.runtime.LuaLog;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused")
public class LuaMultiAdapter extends BaseAdapter {
    private final LuaContext mContext;
    private final LuaValue mLayouts;
    private final LuaTable mData;
    private final LuaLayout mLayoutLoader;
    private final LuaValue mInsertFunc;
    private final LuaValue mRemoveFunc;
    private final LuaValue mLayoutParams;
    private final Map<View, Animation> mAnimCache = new HashMap<>();
    private final Set<View> mStyledViews = new HashSet<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private LuaValue mTheme;
    private LuaValue mAnimationUtil;
    private boolean mNotifyOnChange = true;
    private boolean mUpdating;

    public LuaMultiAdapter(LuaContext context, LuaValue layouts) throws LuaError {
        this(context, null, layouts);
    }

    public LuaMultiAdapter(LuaContext context, LuaTable data, LuaValue layouts) throws LuaError {
        mContext = context;
        mLayouts = layouts;
        Globals globals = context.getLuaState();
        mData = data != null ? data : new LuaTable();
        mLayoutLoader = new LuaLayout(context.getContext());
        var table = globals.get("table");
        mInsertFunc = table.get("insert");
        mRemoveFunc = table.get("remove");
        mLayoutParams = Coercion.toLua(AdapterView.LayoutParams.class);
        int layoutCount = mLayouts.length();
        for (int i = 1; i <= layoutCount; i++) {
            mLayoutLoader.load(mLayouts.get(i), new LuaTable(), mLayoutParams);
        }
    }

    @Override
    public int getViewTypeCount() {
        return mLayouts.length();
    }

    @Override
    public int getItemViewType(int position) {
        try {
            int type = mData.get(position + 1).get("__type").toint();
            return Math.max(type - 1, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int getCount() {
        return mData.length();
    }

    @Override
    public Object getItem(int position) {
        return mData.get(position + 1);
    }

    @Override
    public long getItemId(int position) {
        return position + 1;
    }

    public LuaTable getData() {
        return mData;
    }

    public void setAnimationUtil(LuaValue animation) {
        mAnimCache.clear();
        mAnimationUtil = animation;
    }

    public void add(LuaValue item) {
        JavaCall.call(mInsertFunc, mData, item);
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void addAll(LuaValue items) {
        int length = items.length();
        for (int i = 1; i <= length; i++) JavaCall.call(mInsertFunc, mData, items.get(i));
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void insert(int position, LuaValue item) {
        JavaCall.call(mInsertFunc, mData, position + 1, item);
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void remove(int position) {
        JavaCall.call(mRemoveFunc, mData, position + 1);
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void clear() {
        // Lua 脚本可能在 task/thread 的 IO 线程经 table.insert 并发写同一张表，
        // 裸 clear 与之竞争；进执行区（同包 LuaAdapter/LuaArrayAdapter 的写路径同款）。
        mContext.getLuaState().runGuarded(mData::clear);
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void setNotifyOnChange(boolean notifyOnChange) {
        mNotifyOnChange = notifyOnChange;
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    @Override
    public void notifyDataSetChanged() {
        super.notifyDataSetChanged();
        // 数据全量换新，行视图的动画/样式缓存随之失效：不清的话集合会强引用
        //   所有创建过的行 View（行 View 又持 Activity 上下文），列表重建时成片泄漏
        mAnimCache.clear();
        mStyledViews.clear();
        if (!mUpdating) {
            mUpdating = true;
            mHandler.postDelayed(() -> mUpdating = false, 500);
        }
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return getView(position, convertView, parent);
    }

    public void setStyle(LuaValue theme) {
        mStyledViews.clear();
        mTheme = theme;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view;
        LuaTable holder;
        var itemData = mData.get(position + 1);
        if (!itemData.istable()) {
            LuaLog.getInstance().addError("LuaMultiAdapter.getView " + position,
                    new Exception(position + " is not a table"));
            // 不能返回未打 tag 的裸 View：它会进回收池，而坏行与 __type=1 的正常行同属
            //   view type 0，下一个正常行拿到它后 getTag() 为 null，每个键 NPE 被 per-key
            //   catch 吞掉、那一行永久空白。改用空表走正常创建/复用，只是没有数据可绑
            itemData = new LuaTable();
        }
        int type = Math.max(itemData.get("__type").toint(), 1);
        var layout = mLayouts.get(type);
        if (convertView == null) {
            try {
                holder = new LuaTable();
                view = mLayoutLoader.load(layout, holder, mLayoutParams).touserdata(View.class);
                view.setTag(holder);
            } catch (LuaError e) {
                return new View(mContext.getContext());
            }
        } else {
            view = convertView;
            holder = (LuaTable) view.getTag();
        }

        var data = itemData.checktable();
        boolean isNewView = mStyledViews.add(view);
        // next() 单趟遍历：keys() 每行分配一个键数组，且拿到键后还要再查表取值
        //   （每属性两次哈希）。next 直接给出键值对，绑定一行只走一遍表。
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs pair = data.next(key);
            if (pair.isnil(1)) break;
            key = pair.arg1();
            try {
                var targetObj = holder.get(key);
                if (targetObj.isuserdata()) {
                    var targetView = targetObj.touserdata(View.class);
                    if (mTheme != null && isNewView)
                        AdapterHelper.setHelper(targetView, mTheme.get(key));
                    AdapterHelper.setHelper(targetView, pair.arg(2));
                }
            } catch (Exception e) {
                LuaConfig.logError("LuaMultiAdapter", e);
            }
        }
        if (mUpdating) return view;
        if (mAnimationUtil != null && convertView != null) {
            Animation anim = mAnimCache.get(convertView);
            if (anim == null) {
                try {
                    anim = LuaCall.invoke(mAnimationUtil.get(type), LuaValue.NONE).arg1().touserdata(Animation.class);
                    mAnimCache.put(convertView, anim);
                } catch (Exception e) {
                    mContext.sendError("setAnimation", e);
                }
            }
            if (anim != null) {
                view.clearAnimation();
                view.startAnimation(anim);
            }
        }
        return view;
    }
}
