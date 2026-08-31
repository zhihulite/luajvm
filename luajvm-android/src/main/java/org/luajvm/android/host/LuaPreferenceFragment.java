package org.luajvm.android.host;

import android.annotation.SuppressLint;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.luajvm.android.runtime.LuaLog;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;

/**
 * Lua 偏好设置 Fragment
 */
@SuppressLint("ValidFragment")
@SuppressWarnings("unused")
public class LuaPreferenceFragment extends PreferenceFragmentCompat implements
        Preference.OnPreferenceChangeListener,
        Preference.OnPreferenceClickListener {
    private LuaTable mPreferences;
    private Preference.OnPreferenceChangeListener mOnPreferenceChangeListener;
    private Preference.OnPreferenceClickListener mOnPreferenceClickListener;

    public LuaPreferenceFragment(LuaTable preferences) {
        mPreferences = preferences;
    }

    /**
     * Fragment 恢复（配置变更/进程重建）经反射走无参构造——缺了它恢复必崩。
     * 重建后 mPreferences 为 null（Lua 侧持有的表不可序列化），onCreatePreferences 只建空屏。
     */
    public LuaPreferenceFragment() {
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // 不调 setPreferencesFromResource：PreferenceScreen 由下面按 mPreferences 这张
        //   Lua 表现建；传 resId=0 会让 androidx.preference 走 getXml(0) 抛
        //   NotFoundException，Fragment 起不来。要从 XML 起头请 override 本方法传真实 resId。
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        if (mPreferences != null) {
            try {
                initPreferences(screen, mPreferences);
            } catch (Exception e) {
                LuaLog.getInstance().addError("LuaPreferenceFragment", e);
            }
        }
        setPreferenceScreen(screen);
    }

    /**
     * 重建后 Lua 重新供表时把屏幕建起来：只存字段的话 onCreatePreferences 已经跑完，
     * 屏幕会一直空着。已 attach 就地重建，未 attach 则等 onCreatePreferences 用新表建。
     */
    public void setPreferences(LuaTable preferences) {
        mPreferences = preferences;
        if (preferences == null || getContext() == null) return;
        try {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
            initPreferences(screen, preferences);
            setPreferenceScreen(screen);
        } catch (Exception e) {
            // 尚未走到 onCreatePreferences（PreferenceManager 还没建）时就地失败，
            //   表已存进字段，等 onCreatePreferences 用它建屏幕
            LuaLog.getInstance().addError("LuaPreferenceFragment", e);
        }
    }

    private void initPreferences(PreferenceScreen screen, LuaTable preferences) {
        int count = preferences.length();
        for (int i = 1; i <= count; i++) {
            LuaTable prefConfig = preferences.get(i).checktable();
            try {
                LuaValue prefClass = prefConfig.get(1);
                if (prefClass.isnil()) {
                    throw new IllegalArgumentException("First value must be a Preference class");
                }

                Preference preference = (Preference) JavaCall.call(prefClass, requireContext());
                preference.setOnPreferenceChangeListener(this);
                preference.setOnPreferenceClickListener(this);
                for (LuaValue key : (prefConfig).keys()) {
                    if (key.isstring()) {
                        setPreferenceProperty(preference, key.toJavaString(), prefConfig.get(key.toJavaString()));
                    }
                }
                screen.addPreference(preference);
            } catch (Exception e) {
                LuaLog.getInstance().addError("LuaPreferenceFragment", e);
            }
        }
    }

    private void setPreferenceProperty(Preference preference, String key, Object value) {
        JavaCall.set(Coercion.toLua(preference), key, value);
    }

    public void setOnPreferenceChangeListener(Preference.OnPreferenceChangeListener listener) {
        mOnPreferenceChangeListener = listener;
    }

    public void setOnPreferenceClickListener(Preference.OnPreferenceClickListener listener) {
        mOnPreferenceClickListener = listener;
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
        return mOnPreferenceChangeListener == null || mOnPreferenceChangeListener.onPreferenceChange(preference, newValue);
    }

    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
        return mOnPreferenceClickListener != null && mOnPreferenceClickListener.onPreferenceClick(preference);
    }
}
