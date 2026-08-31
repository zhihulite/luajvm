package org.luajvm.android.host;

import android.content.Context;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Creator 委托式 Fragment：Lua 侧经 {@code luajava.createProxy} 实现 {@link Creator}
 * 接住全部生命周期回调。
 */
@SuppressWarnings("unused")
public class LuaFragment extends Fragment {

    private Creator mCreator;
    private Bundle mSavedState;

    public LuaFragment() {
    }

    public LuaFragment(Creator creator) {
        mCreator = creator;
    }

    /** Lua 侧回调面。方法签名与 Fragment 同名同参，createProxy 按名匹配。 */
    public interface Creator {
        default void onAttach(@NonNull Context context) {}

        default void onCreate(@Nullable Bundle savedInstanceState) {}

        @Nullable
        default View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            return null;
        }

        default void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {}

        default void onStart() {}

        default void onResume() {}

        default void onPause() {}

        default void onStop() {}

        default void onDestroyView() {}

        default void onDestroy() {}

        default void onSaveInstanceState(@NonNull Bundle outState) {}

        default void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {}

        default boolean onContextItemSelected(@NonNull MenuItem item) {
            return false;
        }

        default void onCreateContextMenu(@NonNull ContextMenu menu, @NonNull View v,
                @Nullable ContextMenu.ContextMenuInfo menuInfo) {}

        default void onLowMemory() {}
    }

    @Override
    public void onAttach(@NonNull android.content.Context context) {
        super.onAttach(context);
        if (mCreator != null) mCreator.onAttach(context);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSavedState = savedInstanceState;
        if (mCreator != null) mCreator.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        if (mCreator == null) {
            return null;
        }
        return mCreator.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mCreator != null) mCreator.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mCreator != null) mCreator.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mCreator != null) mCreator.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mCreator != null) mCreator.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mCreator != null) mCreator.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mCreator != null) mCreator.onDestroyView();
    }

    @Override
    public void onDestroy() {
        if (mCreator != null) mCreator.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mCreator != null) mCreator.onSaveInstanceState(outState);
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mCreator != null) mCreator.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        return (mCreator != null && mCreator.onContextItemSelected(item))
                || super.onContextItemSelected(item);
    }

    @Override
    public void onCreateContextMenu(@NonNull ContextMenu menu, @NonNull View v,
            @Nullable ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if (mCreator != null) mCreator.onCreateContextMenu(menu, v, menuInfo);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mCreator != null) mCreator.onLowMemory();
    }

    /** 重建前的实例状态（Lua 侧读savedInstanceState 用）。 */
    public Bundle getSavedState() {
        return mSavedState;
    }
}
