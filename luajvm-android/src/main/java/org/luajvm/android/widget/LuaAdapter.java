package org.luajvm.android.widget;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;

import org.luajvm.android.api.CallLuaFunction;
import org.luajvm.android.api.LuaContext;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.runtime.LuaLog;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unused")
public class LuaAdapter extends BaseAdapter implements Filterable {
    private final LuaTable mBaseData;
    private final LuaContext mContext;
    private final LuaLayout mLayoutLoader;
    private final Map<View, Animation> mAnimCache = new HashMap<>();
    private final HashMap<View, Boolean> mStyleCache = new HashMap<>();
    private LuaTable mLayout;
    private LuaTable mData;
    private LuaTable mTheme;
    private CharSequence mPrefix;
    private LuaFunction mAnimationUtil;
    private boolean mNotifyOnChange = true;
    private boolean mUpdating;
    private ArrayFilter mFilter;
    private LuaFunction mLuaFilter;

    public LuaAdapter(LuaContext context, LuaTable layout) throws LuaError {
        this(context, null, layout);
    }

    public LuaAdapter(LuaContext context, LuaTable data, LuaTable layout) throws LuaError {
        mContext = context;
        if (layout == null) throw LuaErrors.errorObject("LuaAdapter: layout 表不能为 nil");
        if (data == null) data = new LuaTable();
        // 顺序纠错：第二参是纯序列（#=键数）且第一参带字符串键（布局形态）时，
        // 判为调用方把 (data, layout) 写反，交换回来。
        //   第一参只用"带字符串键"判，不用 #!=键数——稀疏 data（{[1]=a,[3]=b}）也满足后者，
        //   会把合法调用误交换掉
        if (layout.length() == entryCount(layout) && hasStringKey(data)) {
            mLayout = data;
            data = layout;
            layout = mLayout;
        }
        mLayout = layout;
        mData = data;
        mBaseData = mData;
        mLayoutLoader = new LuaLayout(mContext.getContext());
        mLayoutLoader.load(mLayout, new LuaTable());
    }

    @SuppressLint("HandlerLeak")
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == 0) {
                notifyDataSetChanged();
            } else {
                try {
                    LuaTable newValues = new LuaTable();
                    JavaCall.call(mLuaFilter, mBaseData, newValues, mPrefix);
                    mData = newValues;
                    notifyDataSetChanged();
                } catch (LuaError e) {
                    mContext.sendError("performFiltering", e);
                }
            }
        }
    };

    // java-only: 全部键数（数组段+哈希段），等价 luajpp 的 LuaTable.size()。
    //   next 全量遍历 O(n)：仅构造期调用（滚动路径不经过）；进高频路径前须缓存结果
    private static int entryCount(LuaTable t) {
        int n = 0;
        LuaValue k = LuaValue.NIL;
        while (!(k = t.next(k).arg1()).isnil()) n++;
        return n;
    }

    // java-only: 是否带字符串键——布局表的属性键（id/text/layout_width…）即此形态
    private static boolean hasStringKey(LuaTable t) {
        LuaValue k = LuaValue.NIL;
        while (!(k = t.next(k).arg1()).isnil()) {
            if (k.isstring()) return true;
        }
        return false;
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "getView 期间取动画，随布局在主线程")
    public void setAnimation(LuaFunction animation) {
        setAnimationUtil(animation);
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "同 setAnimation")
    public void setAnimationUtil(LuaFunction animation) {
        mAnimCache.clear();
        mAnimationUtil = animation;
    }

    @Override
    public int getCount() {
        return mData.length();
    }

    @Override
    public Object getItem(int position) {
        return Coercion.toLua(mData.get(position + 1));
    }

    @Override
    public long getItemId(int position) {
        return position + 1;
    }

    public LuaTable getData() {
        return mData;
    }

    public void setItem(int index, LuaValue value) {
        // 进所属 Globals 的执行区改表 - 适配器私有 monitor 挡不住 Lua 侧并发写同一张表。
        mContext.getLuaState().runGuarded(() -> mBaseData.set(index + 1, value));
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void add(LuaTable item) {
        mContext.getLuaState().runGuarded(
                () -> mBaseData.insert(mBaseData.length() + 1, item));
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void addAll(LuaTable items) {
        int len = items.length();
        mContext.getLuaState().runGuarded(() -> {
            for (int i = 1; i <= len; i++) {
                mBaseData.insert(mBaseData.length() + 1, items.get(i));
            }
        });
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void insert(int position, LuaTable item) {
        mContext.getLuaState().runGuarded(() -> mBaseData.insert(position + 1, item));
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void remove(int position) {
        mContext.getLuaState().runGuarded(() -> mBaseData.remove(position + 1));
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void clear() {
        mContext.getLuaState().runGuarded(() -> mBaseData.clear());
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void setNotifyOnChange(boolean notifyOnChange) {
        mNotifyOnChange = notifyOnChange;
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    @Override
    public void notifyDataSetChanged() {
        super.notifyDataSetChanged();
        // 数据全量换新，行视图的动画/样式缓存随之失效：不清的话 Map 会强引用
        //   所有创建过的行 View（行 View 又持 Activity 上下文），列表重建时成片泄漏
        mAnimCache.clear();
        mStyleCache.clear();
        if (!mUpdating) {
            mUpdating = true;
            mHandler.postDelayed(() -> mUpdating = false, 500);
        }
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return getView(position, convertView, parent);
    }

    public void setStyle(LuaTable theme) {
        mStyleCache.clear();
        mTheme = theme;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view;
        LuaTable holder;
        if (convertView == null) {
            try {
                holder = new LuaTable();
                var viewValue = mLayoutLoader.load(mLayout, holder);
                view = viewValue.touserdata(View.class);
                view.setTag(holder);
            } catch (LuaError e) {
                LuaLog.getInstance().addError("LuaAdapter", e);
                return new View(mContext.getContext());
            }
        } else {
            view = convertView;
            holder = (LuaTable) view.getTag();
        }

        var itemData = mData.get(position + 1);
        if (!itemData.istable()) {
            LuaLog.getInstance().addError("setHelper error: " + position,
                    new Exception(position + " is not a table"));
            return view;
        }

        boolean isNewView = mStyleCache.get(view) == null;
        if (isNewView) mStyleCache.put(view, true);
        // next() 单趟遍历：keys() 每行分配一个键数组，且拿到键后还要再查表取值
        //   （每属性两次哈希）。next 直接给出键值对，绑定一行只走一遍表。
        LuaTable itemTable = (LuaTable) itemData;
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs pair = itemTable.next(key);
            if (pair.isnil(1)) break;
            key = pair.arg1();
            try {
                var obj = holder.get(key);
                if (obj.isuserdata()) {
                    var targetView = obj.touserdata(View.class);
                    if (mTheme != null && isNewView) AdapterHelper.setHelper(targetView, mTheme.get(key));
                    AdapterHelper.setHelper(targetView, pair.arg(2));
                }
            } catch (Exception e) {
                LuaConfig.logError("LuaAdapter", e);
                LuaLog.getInstance().addError("setHelper error: " + position, e);
            }
        }
        if (mUpdating) return view;
        if (mAnimationUtil != null && convertView != null) {
            var anim = mAnimCache.get(convertView);
            if (anim == null) {
                try {
                    anim = LuaCall.invoke(mAnimationUtil, LuaValue.NONE).arg1().touserdata(Animation.class);
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


    @Override
    public Filter getFilter() {
        if (mFilter == null) mFilter = new ArrayFilter();
        return mFilter;
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "performFiltering 在 worker 线程，但本类用 mHandler(mainLooper) 弹回主线程再调")
    public void setFilter(LuaFunction filter) {
        mLuaFilter = filter;
    }

    public void filter(CharSequence constraint) {
        getFilter().filter(constraint);
    }

    private class ArrayFilter extends Filter {
        @Override
        protected FilterResults performFiltering(CharSequence prefix) {
            mPrefix = prefix;
            if (mData == null) return new FilterResults();
            if (mLuaFilter != null) {
                // Lua 过滤器只能安全地在主线程跑（见 mHandler）：把过滤请求弹回主线程，
                // 此处返回当前快照。绝不能返回 null——框架 Filter 不判空，直接 NPE 崩。
                mHandler.sendEmptyMessage(1);
                var async = new FilterResults();
                async.values = mData;
                async.count = mData.length();
                return async;
            }

            var results = new FilterResults();
            results.values = mData;
            results.count = mData.length();
            return results;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            mData = (LuaTable) results.values;
            if (results.count > 0) notifyDataSetChanged();
            else notifyDataSetInvalidated();
        }
    }




}
