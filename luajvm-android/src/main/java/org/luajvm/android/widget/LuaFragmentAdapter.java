package org.luajvm.android.widget;

import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import androidx.fragment.app.FragmentActivity;

import org.jspecify.annotations.NonNull;
import org.luajvm.android.api.LuaContext;

@SuppressWarnings("unused")
public class LuaFragmentAdapter extends FragmentStateAdapter {

    private final FragmentActivity mContext;
    private Creator mCreator;

    public LuaFragmentAdapter(FragmentActivity context, Creator creator) {
        super(context.getSupportFragmentManager(), context.getLifecycle());
        this.mContext = context;
        this.mCreator = creator;
    }

    @Override
    public @NonNull Fragment createFragment(int position) {
        try {
            return mCreator.createFragment(position);
        } catch (Exception e) {
            if (mContext instanceof LuaContext lc) lc.sendError("FragmentAdapter", e);
            return new Fragment();
        }
    }

    @Override
    public int getItemCount() {
        try {
            return mCreator.getItemCount();
        } catch (Exception e) {
            if (mContext instanceof LuaContext lc) lc.sendError("FragmentAdapter", e);
            return 0;
        }
    }

    public Creator getCreator() {
        return mCreator;
    }

    public void setCreator(Creator creator) {
        this.mCreator = creator;
    }

    public FragmentActivity getContext() {
        return mContext;
    }

    public interface Creator {
        Fragment createFragment(int position);

        int getItemCount();
    }
}
