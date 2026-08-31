package org.luajvm.android.widget;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

@SuppressWarnings("unused")
public class LuaPager2Adapter extends RecyclerView.Adapter<LuaPager2Adapter.ViewHolder> {
    private static final String DEFAULT_TITLE = "No Title";
    private final List<View> pageViews = new CopyOnWriteArrayList<>();
    private final List<String> titles = new CopyOnWriteArrayList<>();

    // add 的合法区间 [0,size]（可插到尾部），remove 的合法区间 [0,size-1]：
    //   越界裸抛 IndexOutOfBoundsException 对 Lua 侧无从排查，转 LuaError
    private void checkAddPosition(int position) {
        if (position < 0 || position > pageViews.size()) {
            throw LuaErrors.errorObject("position " + position + " out of range [0, " + pageViews.size() + "]");
        }
    }

    private void checkRemovePosition(int position) {
        if (position < 0 || position >= pageViews.size()) {
            throw LuaErrors.errorObject("position " + position + " out of range [0, " + (pageViews.size() - 1) + "]");
        }
    }

    public void add(View view, String title) {
        pageViews.add(view);
        titles.add(Objects.requireNonNullElse(title, DEFAULT_TITLE));
        notifyItemInserted(pageViews.size() - 1);
    }

    public void add(int position, View view, String title) {
        checkAddPosition(position);
        pageViews.add(position, view);
        titles.add(position, Objects.requireNonNullElse(title, DEFAULT_TITLE));
        notifyItemInserted(position);
    }

    public void add(View view) {
        pageViews.add(view);
        titles.add(DEFAULT_TITLE);
        notifyItemInserted(pageViews.size() - 1);
    }

    public void add(int position, View view) {
        checkAddPosition(position);
        pageViews.add(position, view);
        titles.add(position, DEFAULT_TITLE);
        notifyItemInserted(position);
    }

    public void remove(int position) {
        checkRemovePosition(position);
        pageViews.remove(position);
        titles.remove(position);
        notifyItemRemoved(position);
    }

    public boolean remove(View view) {
        int index = pageViews.indexOf(view);
        if (index != -1) {
            pageViews.remove(index);
            titles.remove(index);
            notifyItemRemoved(index);
            return true;
        }
        return false;
    }

    public View getItem(int position) {
        if (position >= 0 && position < pageViews.size()) {
            return pageViews.get(position);
        }
        return null;
    }

    public String getTitle(int position) {
        if (position >= 0 && position < titles.size()) {
            return titles.get(position);
        }
        return DEFAULT_TITLE;
    }

    public List<View> getData() {
        return pageViews;
    }

    public List<String> getTitles() {
        return titles;
    }

    public void clear() {
        int size = pageViews.size();
        pageViews.clear();
        titles.clear();
        notifyItemRangeRemoved(0, size);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout container = new FrameLayout(parent.getContext());
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        container.setLayoutParams(params);
        return new ViewHolder(container);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        try {
            View view = pageViews.get(position);
            FrameLayout container = (FrameLayout) holder.container;

            // 避免重复添加同一个 View
            if (container.getChildCount() == 1 && container.getChildAt(0) == view) {
                return;
            }

            container.removeAllViews();
            if (view.getParent() != null) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            container.addView(view);
        } catch (Exception e) {
            FrameLayout container = (FrameLayout) holder.container;
            container.removeAllViews();
            TextView errorView = new TextView(container.getContext());
            errorView.setText(String.format("Adapter Error: %s", e.getMessage()));
            errorView.setTextColor(0xFFFF0000);
            container.addView(errorView);
        }
    }

    @Override
    public int getItemCount() {
        return pageViews.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final View container;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            this.container = itemView;
        }
    }
}
