package org.luajvm.android.widget;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.AdapterView;
import android.widget.BaseExpandableListAdapter;

import org.luajvm.android.api.LuaContext;
import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.runtime.LuaLog;

import org.luajvm.bind.Coercion;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@SuppressWarnings("unused")
public class LuaExpandableListAdapter extends BaseExpandableListAdapter {

    private final LuaContext mContext;

    private final LuaTable mGroupData;
    private final LuaTable mChildData;
    private final LuaTable mGroupLayout;
    private final LuaTable mChildLayout;
    private final LuaLayout mLayoutLoader;

    private final Map<View, Animation> mAnimCache = new HashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private LuaValue mAnimationUtil;
    private LuaValue mChildSelectable;
    private boolean mNotifyOnChange = true;
    private boolean mSuppressAnim;

    public LuaExpandableListAdapter(LuaContext context, LuaTable groupLayout, LuaTable childLayout) throws LuaError {
        this(context, groupLayout, childLayout, null, null);
    }

    public LuaExpandableListAdapter(LuaContext context, LuaTable groupLayout, LuaTable childLayout,
                                    LuaTable groupData, LuaTable childData) throws LuaError {
        mContext = context;
        mGroupLayout = groupLayout;
        mChildLayout = childLayout;
        mGroupData = groupData != null ? groupData : new LuaTable();
        mChildData = childData != null ? childData : new LuaTable();

        var layoutParams = Coercion.toLua(AdapterView.LayoutParams.class);
        mLayoutLoader = new LuaLayout(context.getContext());
        mLayoutLoader.load(mGroupLayout, new LuaTable(), layoutParams);
        mLayoutLoader.load(mChildLayout, new LuaTable(), layoutParams);
    }

    public void setAnimationUtil(LuaValue animation) {
        mAnimCache.clear();
        mAnimationUtil = animation;
    }

    @Override
    public int getGroupCount() {
        return mGroupData.length();
    }

    @Override
    public int getChildrenCount(int groupPosition) {
        var group = mChildData.get(groupPosition + 1);
        return group.istable() ? group.length() : 0;
    }

    @Override
    public Object getGroup(int groupPosition) {
        return Coercion.toJava(mGroupData.get(groupPosition + 1), Object.class);
    }

    @Override
    public Object getChild(int groupPosition, int childPosition) {
        var group = mChildData.get(groupPosition + 1);
        return group.istable() ? Coercion.toJava(group.get(childPosition + 1), Object.class) : null;
    }

    @Override
    public long getGroupId(int groupPosition) {
        return groupPosition + 1;
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
        return childPosition + 1;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    /**
     * 子项可选。返回 false 会让 ExpandableListView 完全收不到子项点击
     * （onChildClick 不触发、按下无反馈），故默认 true。
     *
     * <p>需要按位置禁用时，Lua 侧设 {@code adapter.childSelectable = function(g, c) ... end}，
     * 返回假值即该位置不可选。回调抛错时按可选处理，不让列表整体失去响应。
     */
    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
        if (mChildSelectable == null || !mChildSelectable.isfunction()) return true;
        try {
            LuaValue r = LuaCall.invoke(mChildSelectable, Varargs.of(
                    LuaValue.valueOf(groupPosition + 1),
                    LuaValue.valueOf(childPosition + 1))).arg1();
            // 未返回值（NIL）视为可选：漏写 return 不该让整列表点不动
            return r.isnil() || r.toboolean();
        } catch (Exception e) {
            LuaConfig.logError("LuaExpandableListAdapter", e);
            return true;
        }
    }

    /** Lua 侧可选性回调，nil 时全部可选。 */
    public void setChildSelectable(LuaValue fn) {
        mChildSelectable = fn;
    }

    public LuaTable getGroupData() {
        return mGroupData;
    }

    public LuaTable getChildData() {
        return mChildData;
    }

    public GroupItem add(LuaTable groupItem) {
        return add(groupItem, new LuaTable());
    }

    public GroupItem add(LuaTable groupItem, LuaTable childItem) {
        mGroupData.set(mGroupData.length() + 1, groupItem);
        mChildData.set(mGroupData.length(), childItem);
        if (mNotifyOnChange) notifyDataSetChanged();
        return new GroupItem(childItem);
    }

    public GroupItem insert(int position, LuaTable groupItem, LuaTable childItem) {
        mGroupData.insert(position + 1, groupItem);
        mChildData.insert(position + 1, childItem);
        if (mNotifyOnChange) notifyDataSetChanged();
        return new GroupItem(childItem);
    }

    public void remove(int position) {
        mGroupData.remove(position + 1);
        mChildData.remove(position + 1);
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void clear() {
        mGroupData.clear();
        mChildData.clear();
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    public void setNotifyOnChange(boolean notifyOnChange) {
        mNotifyOnChange = notifyOnChange;
        if (mNotifyOnChange) notifyDataSetChanged();
    }

    @Override
    public void notifyDataSetChanged() {
        super.notifyDataSetChanged();
        if (!mSuppressAnim) {
            mSuppressAnim = true;
            mHandler.postDelayed(() -> mSuppressAnim = false, 500);
        }
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
        return bindItemView(groupPosition, -1, convertView, parent, true);
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
        return bindItemView(groupPosition, childPosition, convertView, parent, false);
    }

    private View bindItemView(int groupPosition, int childPosition, View convertView, ViewGroup parent, boolean isGroup) {
        View view;
        LuaTable holder;
        var layout = isGroup ? mGroupLayout : mChildLayout;
        LuaTable data;

        if (isGroup) {
            data = mGroupData.get(groupPosition + 1).checktable();
        } else {
            var group = mChildData.get(groupPosition + 1);
            data = group.istable() ? group.get(childPosition + 1).checktable() : new LuaTable();
        }

        if (convertView == null) {
            try {
                holder = new LuaTable();
                view = mLayoutLoader.load(layout, holder).touserdata(View.class);
                view.setTag(holder);
            } catch (LuaError e) {
                LuaLog.getInstance().addError("LuaExpandableListAdapter", e);
                return new View(mContext.getContext());
            }
        } else {
            view = convertView;
            holder = (LuaTable) view.getTag();
        }

        // next() 单趟遍历：keys() 会为每行分配一个键数组，且拿到键后还要再查一次表
        //   取值（每属性两次哈希）。next 直接给出键值对，绑定一行只走一遍表。
        LuaValue key = LuaValue.NIL;
        while (true) {
            Varargs pair = data.next(key);
            if (pair.isnil(1)) break;
            key = pair.arg1();
            try {
                LuaValue viewObj = holder.get(key);
                if (viewObj.isuserdata())
                    AdapterHelper.setHelper(viewObj.touserdata(View.class), pair.arg(2));
            } catch (Exception e) {
                LuaConfig.logError("LuaExpandableListAdapter", e);
                LuaLog.getInstance().addError("setHelper", e);
            }
        }

        if (mSuppressAnim) return view;

        if (mAnimationUtil != null && convertView != null) {
            Animation anim = mAnimCache.get(convertView);
            if (anim == null) {
                try {
                    anim = LuaCall.invoke(mAnimationUtil, LuaValue.NONE).arg1().touserdata(Animation.class);
                    mAnimCache.put(convertView, anim);
                } catch (Exception e) {
                    LuaConfig.logError("LuaExpandableListAdapter", e);
                    LuaLog.getInstance().addError("setAnimation error: ", e);
                }
            }
            if (anim != null) {
                view.clearAnimation();
                view.startAnimation(anim);
            }
        }
        return view;
    }

    public class GroupItem {
        private final LuaTable mData;

        public GroupItem(LuaTable data) {
            mData = data;
        }

        public LuaTable getData() {
            return mData;
        }

        public void add(LuaTable item) {
            mData.set(mData.length() + 1, item);
            if (mNotifyOnChange) notifyDataSetChanged();
        }

        public void insert(int position, LuaTable item) {
            mData.insert(position + 1, item);
            if (mNotifyOnChange) notifyDataSetChanged();
        }

        public void remove(int position) {
            mData.remove(position + 1);
            if (mNotifyOnChange) notifyDataSetChanged();
        }

        public void clear() {
            mData.clear();
            if (mNotifyOnChange) notifyDataSetChanged();
        }
    }
}
