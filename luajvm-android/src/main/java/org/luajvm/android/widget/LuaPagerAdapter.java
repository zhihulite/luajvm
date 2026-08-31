package org.luajvm.android.widget;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("unused")
public final class LuaPagerAdapter extends PagerAdapter {
    private static final String DEFAULT_TITLE = "No Title";
    private final List<View> pageViews = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();

    @Override
    public int getCount() {
        return pageViews.size();
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return view == object;
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        View view = pageViews.get(position);
        if (view.getParent() instanceof ViewGroup parent) {
            parent.removeView(view);
        }
        container.addView(view);
        return view;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        container.removeView((View) object);
    }

    @Override
    public CharSequence getPageTitle(int position) {
        if (position >= 0 && position < titles.size()) {
            return Objects.requireNonNullElse(titles.get(position), DEFAULT_TITLE);
        }
        return DEFAULT_TITLE;
    }

    @Override
    public int getItemPosition(@NonNull Object object) {
        int index = pageViews.indexOf((View) object);
        return index == -1 ? POSITION_NONE : index;
    }

    public void add(View view) {
        pageViews.add(view);
        titles.add(DEFAULT_TITLE);
        notifyDataSetChanged();
    }

    public void add(View view, String title) {
        pageViews.add(view);
        titles.add(Objects.requireNonNullElse(title, DEFAULT_TITLE));
        notifyDataSetChanged();
    }

    public void add(int position, View view) {
        if (position >= 0 && position <= pageViews.size()) {
            pageViews.add(position, view);
            titles.add(position, DEFAULT_TITLE);
            notifyDataSetChanged();
        }
    }

    public void add(int position, View view, String title) {
        if (position >= 0 && position <= pageViews.size()) {
            pageViews.add(position, view);
            titles.add(position, Objects.requireNonNullElse(title, DEFAULT_TITLE));
            notifyDataSetChanged();
        }
    }

    public void set(int index, View view) {
        if (index >= 0 && index < pageViews.size()) {
            pageViews.set(index, view);
            notifyDataSetChanged();
        }
    }

    public void set(int index, View view, String title) {
        if (index >= 0 && index < pageViews.size()) {
            pageViews.set(index, view);
            titles.set(index, Objects.requireNonNullElse(title, DEFAULT_TITLE));
            notifyDataSetChanged();
        }
    }

    public void remove(int index) {
        if (index >= 0 && index < pageViews.size()) {
            pageViews.remove(index);
            if (index < titles.size()) {
                titles.remove(index);
            }
            notifyDataSetChanged();
        }
    }

    public void remove(View view) {
        int index = pageViews.indexOf(view);
        if (index != -1) {
            remove(index);
        }
    }

    public void clear() {
        pageViews.clear();
        titles.clear();
        notifyDataSetChanged();
    }
}
