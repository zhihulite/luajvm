package org.luajvm.android.widget;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.luajvm.android.api.LuaContext;
import com.google.android.material.textview.MaterialTextView;

import org.luajvm.core.LuaTable;

import java.util.Objects;

@SuppressWarnings("unused")
public class LuaCustRecyclerAdapter extends RecyclerView.Adapter<LuaCustRecyclerAdapter.LuaCustRecyclerHolder> {

    private LuaContext mContext;
    private Creator mAdapterCreator;

    public LuaCustRecyclerAdapter(LuaContext context, Creator adapterCreator) {
        this.mContext = context;
        this.mAdapterCreator = adapterCreator;
    }

    public LuaCustRecyclerAdapter(Creator adapterCreator) {
        this(null, adapterCreator);
    }

    @Override
    public int getItemCount() {
        try {
            return mAdapterCreator.getItemCount();
        } catch (Exception e) {
            if (mContext != null) {
                mContext.sendError("RecyclerAdapter: getItemCount", e);
            }
            return 0;
        }
    }

    @Override
    public int getItemViewType(int position) {
        try {
            return (int) mAdapterCreator.getItemViewType(position);
        } catch (Exception e) {
            if (mContext != null) {
                mContext.sendError("RecyclerAdapter: getItemViewType", e);
            }
            return -1;
        }
    }

    @Override
    public void onBindViewHolder(@NonNull LuaCustRecyclerHolder holder, int position) {
        try {
            mAdapterCreator.onBindViewHolder(holder, position);
        } catch (Exception e) {
            if (mContext != null) {
                mContext.sendError("RecyclerAdapter: onBindViewHolder", e);
            }
        }
    }

    @NonNull
    @Override
    public LuaCustRecyclerHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int viewType) {
        try {
            LuaCustRecyclerHolder holder = mAdapterCreator.onCreateViewHolder(viewGroup, viewType);
            return Objects.requireNonNull(holder, "onCreateViewHolder returned null");
        } catch (Exception e) {
            if (mContext != null) {
                mContext.sendError("RecyclerAdapter: onCreateViewHolder", e);
            }
            MaterialTextView errorView = new MaterialTextView(mContext != null ? mContext.getContext() : viewGroup.getContext());
            errorView.setText("Adapter Error: " + e.getMessage());
            errorView.setPadding(16, 16, 16, 16);
            errorView.setTextColor(0xFFFF0000);
            return new LuaCustRecyclerHolder(errorView);
        }
    }

    @Override
    public void onViewRecycled(@NonNull LuaCustRecyclerHolder holder) {
        try {
            mAdapterCreator.onViewRecycled(holder);
        } catch (Exception e) {
            if (mContext != null) {
                mContext.sendError("RecyclerAdapter: onViewRecycled", e);
            }
        }
    }

    // Getter 方法
    public LuaContext getContext() {
        return mContext;
    }

    public void setContext(LuaContext context) {
        this.mContext = context;
    }

    public Creator getAdapterCreator() {
        return mAdapterCreator;
    }

    public void setAdapterCreator(Creator adapterCreator) {
        this.mAdapterCreator = adapterCreator;
    }

    // Creator 接口
    public interface Creator {
        int getItemCount();

        long getItemViewType(int position);

        void onBindViewHolder(LuaCustRecyclerHolder viewHolder, int position);

        LuaCustRecyclerHolder onCreateViewHolder(ViewGroup viewGroup, int viewType);

        void onViewRecycled(LuaCustRecyclerHolder viewHolder);
    }

    public static class LuaCustRecyclerHolder extends RecyclerView.ViewHolder {
        public LuaTable views = null;

        public LuaCustRecyclerHolder(@NonNull View itemView) {
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
