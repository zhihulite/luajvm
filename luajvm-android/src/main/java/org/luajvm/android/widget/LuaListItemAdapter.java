package org.luajvm.android.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.luajvm.android.api.LuaContext;
import com.google.android.material.listitem.ListItemLayout;
import com.google.android.material.listitem.ListItemViewHolder;
import com.google.android.material.textview.MaterialTextView;

import org.luajvm.core.LuaTable;

import java.util.Objects;

@SuppressWarnings("unused")
public class LuaListItemAdapter extends RecyclerView.Adapter<LuaListItemAdapter.LuaListItemHolder> {

    private final LuaContext mContext;
    private final Creator mAdapterCreator;

    public LuaListItemAdapter(LuaContext context, Creator adapterCreator) {
        this.mContext = context;
        this.mAdapterCreator = adapterCreator;
    }

    public LuaListItemAdapter(Creator adapterCreator) {
        this(null, adapterCreator);
    }

    @Override
    public int getItemCount() {
        try {
            return mAdapterCreator.getItemCount();
        } catch (Exception e) {
            if (mContext != null) mContext.sendError("getItemCount", e);
            return 0;
        }
    }

    @Override
    public int getItemViewType(int position) {
        try {
            return (int) mAdapterCreator.getItemViewType(position);
        } catch (Exception e) {
            if (mContext != null) mContext.sendError("getItemViewType", e);
            return -1;
        }
    }

    @Override
    public void onBindViewHolder(@NonNull LuaListItemHolder holder, int position) {
        try {
            mAdapterCreator.onBindViewHolder(holder, position);
        } catch (Exception e) {
            if (mContext != null) mContext.sendError("onBindViewHolder", e);
        }
    }

    @NonNull
    @Override
    public LuaListItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        try {
            LuaListItemHolder holder = mAdapterCreator.onCreateViewHolder(parent, viewType);
            return Objects.requireNonNull(holder);
        } catch (Exception e) {
            if (mContext != null) mContext.sendError("onCreateViewHolder", e);
            Context context = mContext != null ? mContext.getContext() : parent.getContext();
            ListItemLayout errorLayout = new ListItemLayout(context);
            MaterialTextView errorView = new MaterialTextView(context);
            errorView.setText("Adapter Error: " + e.getMessage());
            errorView.setPadding(16, 16, 16, 16);
            errorView.setTextColor(0xFFFF0000);
            errorView.setDuplicateParentStateEnabled(true);

            errorLayout.addView(errorView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            return new LuaListItemHolder(errorLayout);
        }
    }

    @Override
    public void onViewRecycled(@NonNull LuaListItemHolder holder) {
        try {
            mAdapterCreator.onViewRecycled(holder);
        } catch (Exception e) {
            if (mContext != null) mContext.sendError("onViewRecycled", e);
        }
    }

    public interface Creator {
        int getItemCount();

        long getItemViewType(int position);

        void onBindViewHolder(LuaListItemHolder holder, int position);

        LuaListItemHolder onCreateViewHolder(ViewGroup parent, int viewType);

        void onViewRecycled(LuaListItemHolder holder);
    }

    public static class LuaListItemHolder extends ListItemViewHolder {

        public LuaTable views = null;

        public LuaListItemHolder(@NonNull View itemView) {
            super(itemView);
        }

        public LuaTable getViews() {
            return views;
        }

        public void setViews(LuaTable views) {
            this.views = views;
        }
    }
}
