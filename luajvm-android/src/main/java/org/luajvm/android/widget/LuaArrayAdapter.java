package org.luajvm.android.widget;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;

import org.luajvm.android.api.CallLuaFunction;
import org.luajvm.android.api.LuaContext;
import org.luajvm.android.runtime.LuaLog;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;

import java.util.function.Supplier;

@SuppressWarnings("unused")
public class LuaArrayAdapter extends BaseAdapter implements Filterable {
    private final LuaContext mContext;
    private final LuaTable mLayoutResource;
    private final LuaLayout mLayoutLoader;
    private LuaTable mVisibleData;
    private LuaTable mBaseData;
    private Animation mAnimation;
    private LuaFunction mLuaFilter;
    private ArrayFilter mFilter;
    private boolean mNotifyOnChange = true;
    private boolean mUpdating;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public LuaArrayAdapter(LuaContext context, LuaTable layoutResource) throws LuaError {
        this(context, layoutResource, new LuaTable());
    }

    public LuaArrayAdapter(LuaContext context, LuaTable layoutResource, LuaTable data) throws LuaError {
        mContext = context;
        mLayoutResource = layoutResource;
        mVisibleData = data;
        mBaseData = data;
        mLayoutLoader = new LuaLayout(context.getContext());
        mLayoutLoader.load(mLayoutResource, new LuaTable());
    }

    @Override
    public int getCount() {
        return mVisibleData.length();
    }

    @Override
    public Object getItem(int position) {
        return Coercion.toLua(mVisibleData.get(position + 1));
    }

    @Override
    public long getItemId(int position) {
        return position + 1;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return getView(position, convertView, parent);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view;
        if (convertView == null) {
            try {
                var holder = new LuaTable();
                var viewValue = mLayoutLoader.load(mLayoutResource, holder);
                view = viewValue.touserdata(View.class);
                view.setTag(holder);
            } catch (LuaError e) {
                LuaLog.getInstance().addError("LuaArrayAdapter", e);
                return new View(mContext.getContext());
            }
        } else {
            view = convertView;
        }

        AdapterHelper.setHelper(view, getItem(position));
        // 数据更新后的 500ms 内不重启动画：滚动复用行不需要动画，避免每行重绘
        if (mAnimation != null && !mUpdating) view.startAnimation(mAnimation);
        return view;
    }

    @Override
    public void notifyDataSetChanged() {
        super.notifyDataSetChanged();
        if (!mUpdating) {
            mUpdating = true;
            mHandler.postDelayed(() -> mUpdating = false, 500);
        }
    }

    public LuaTable getData() {
        return mVisibleData;
    }

    public void setItem(int index, LuaValue value) {
        guarded(() -> {
            ensureBaseData();
            mBaseData.set(index + 1, value);
        });
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void add(LuaValue item) {
        guarded(() -> {
            ensureBaseData();
            mBaseData.insert(mBaseData.length() + 1, item);
        });
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void addAll(LuaTable items) {
        int length = items.length();
        guarded(() -> {
            ensureBaseData();
            for (int i = 1; i <= length; i++) {
                mBaseData.insert(mBaseData.length() + 1, items.get(i));
            }
        });
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void insert(int position, Object item) {
        guarded(() -> {
            ensureBaseData();
            mBaseData.insert(position + 1, Coercion.toLua(item));
        });
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void remove(int position) {
        guarded(() -> {
            ensureBaseData();
            mBaseData.remove(position + 1);
        });
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void clear() {
        guarded(() -> {
            ensureBaseData();
            mBaseData.clear();
        });
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void setNotifyOnChange(boolean notifyOnChange) {
        mNotifyOnChange = notifyOnChange;
    }

    public Animation getAnimation() {
        return mAnimation;
    }

    public void setAnimation(Animation animation) {
        mAnimation = animation;
    }

    @Override
    public Filter getFilter() {
        if (mFilter == null) mFilter = new ArrayFilter();
        return mFilter;
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.FILTER_WORKER,
            note = "在 Filter 工作线程直接调；同线程内读写 LuaTable 须用 guarded(...) 进执行区")
    public void setFilter(LuaFunction filter) {
        mLuaFilter = filter;
    }

    public void filter(CharSequence constraint) {
        getFilter().filter(constraint);
    }

    /**
     * mBaseData 为 null 时从 mVisibleData 重建
     */
    private void ensureBaseData() {
        if (mBaseData == null) {
            mBaseData = (mVisibleData).copyTable();
        }
    }

    /**
     * 在所属 Globals 的执行区内改表。
     *
     * <p>互斥单位必须是 Globals 的执行区：适配器私有 monitor 只与其他宿主线程互斥，
     * 挡不住 Lua 脚本同时改同一张 {@code LuaTable}。同一状态可重入，
     * 从 Lua 回调进来（已持锁）不会自死锁。
     */
    private void guarded(Runnable action) {
        mContext.getLuaState().runGuarded(action);
    }

    /** guarded 的取值版本：在执行区内读表并带回结果。 */
    private interface ValueSupplier<T> { T get(); }

    private <T> T guardedGet(ValueSupplier<T> action) {
        Object[] box = new Object[1];
        mContext.getLuaState().runGuarded(() -> box[0] = action.get());
        @SuppressWarnings("unchecked") T r = (T) box[0];
        return r;
    }

    private class ArrayFilter extends Filter {
        @Override
        protected FilterResults performFiltering(CharSequence prefix) {
            var results = new FilterResults();
            // performFiltering 跑在 Filter 的工作线程上，须进执行区才能读写 LuaTable
            boolean[] rebuilt = {false};
            guarded(() -> {
                if (mBaseData == null) {
                    mBaseData = (mVisibleData).copyTable();
                    results.values = mVisibleData;
                    results.count = (mVisibleData).length();
                    rebuilt[0] = true;
                }
            });
            if (rebuilt[0]) return results;
            if (TextUtils.isEmpty(prefix)) {
                guarded(() -> {
                    results.values = (mBaseData).copyTable();
                    results.count = (mBaseData).length();
                    mBaseData = null;
                });
                return results;
            }
            if (mLuaFilter != null) {
                var newValues = new LuaTable();
                try {
                    // JavaCall.call -> LuaCall.invoke 自身进执行区；copyTable 读表也须在区内
                    LuaTable snapshot = guardedGet(() -> (mBaseData).copyTable());
                    JavaCall.call(mLuaFilter, snapshot, newValues, prefix);
                } catch (LuaError e) {
                    LuaLog.getInstance().addError("LuaArrayAdapter", e);
                }
                results.values = newValues;
                results.count = (newValues).length();
                return results;
            }

            String prefixLower = prefix.toString().toLowerCase();
            LuaTable sourceValues = guardedGet(() -> (mBaseData).copyTable());

            var filteredValues = new LuaTable();
            int count = (sourceValues).length();
            for (int i = 1; i <= count; i++) {
                var value = sourceValues.get(i);
                // table 项没有可过滤的文本（toString 是 "table: 0x…" 恒不命中）：按命中直通
                if (value.istable()
                        || value.toString().toLowerCase().contains(prefixLower)) {
                    (filteredValues).add(value);
                }
            }
            results.values = filteredValues;
            results.count = (filteredValues).length();
            return results;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            mVisibleData = (LuaTable) results.values;
            // 空结果也走 notifyDataSetChanged：notifyDataSetInvalidated 会让 ListView
            //   整体重置滚动位置与选中态，一次过滤清空不至如此
            notifyDataSetChanged();
        }
    }
}
