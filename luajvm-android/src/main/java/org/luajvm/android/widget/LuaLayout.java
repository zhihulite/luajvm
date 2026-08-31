package org.luajvm.android.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import org.luajvm.android.runtime.LuaConfig;
import org.luajvm.android.util.LuaBitmap;
import org.luajvm.android.widget.LuaAdapter;
import org.luajvm.android.widget.LuaPagerAdapter;
import org.luajvm.android.api.LuaContext;
import org.luajvm.android.widget.LuaBitmapDrawable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior;
import com.google.android.material.behavior.HideViewOnScrollBehavior;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.search.SearchBar;
import com.google.android.material.sidesheet.SideSheetBehavior;
import com.google.android.material.transformation.FabTransformationScrimBehavior;
import com.google.android.material.transformation.FabTransformationSheetBehavior;

import org.luajvm.bind.Coercion;
import org.luajvm.bind.JavaCall;
import org.luajvm.bind.JavaObject;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;
import org.luajvm.vm.LuaCall;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.Objects;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 运行时 Lua 布局加载器，将 Lua 表解析为 Android View 层次。
 */
public class LuaLayout {

    // ==================== 内部数据结构 ====================

    private static final Map<String, Integer> XML_CONSTANT_MAP;
    private static final Map<String, Integer> SCALE_TYPE_MAP;

    // ==================== 常量映射（不可变） ====================
    private static final Map<String, Integer> RULE_MAP;
    private static final Map<String, Integer> UNIT_MAP;
    private interface BehaviorFactory { CoordinatorLayout.Behavior<?> create(); }

    private static final Map<String, BehaviorFactory> BEHAVIOR_MAP;
    /**
     * 全局 View ID 映射（跨实例共享）
     */
    private static final Map<String, Integer> ID_MAP = new ConcurrentHashMap<>();
    private static final String[] PADDING_KEYS = {"paddingLeft", "paddingTop", "paddingRight", "paddingBottom"};
    private static final String[] MARGIN_KEYS = {"layout_marginLeft", "layout_marginTop", "layout_marginRight", "layout_marginBottom"};
    private static final LuaValue WRAP_CONTENT = Coercion.toLua(ViewGroup.LayoutParams.WRAP_CONTENT);
    private static final int ID_START = 0x7f000000;
    /**
     * 预计算 ScaleType 数组，避免每次调用 values() 分配新数组
     */
    private static final ImageView.ScaleType[] SCALE_TYPES = ImageView.ScaleType.values();
    /**
     * 预编译管道分隔正则，避免每次 str.split() 重新编译
     */
    private static final Pattern PIPE_PATTERN = Pattern.compile("\\|");

    static {
        XML_CONSTANT_MAP = initValueMap();
        // 必须对齐 ImageView.ScaleType.values() 的枚举序（旧表按字母序编号，
        // fitCenter 实际拿到 FIT_XY 等——全表错位）
        SCALE_TYPE_MAP = Map.ofEntries(
                entry("matrix", 0), entry("fitXY", 1), entry("fitStart", 2), entry("fitCenter", 3),
                entry("fitEnd", 4), entry("center", 5), entry("centerCrop", 6), entry("centerInside", 7)
        );
        RULE_MAP = Map.ofEntries(
                entry("layout_above", 2), entry("layout_alignBaseline", 4), entry("layout_alignBottom", 8),
                entry("layout_alignEnd", 19), entry("layout_alignLeft", 5), entry("layout_alignParentBottom", 12),
                entry("layout_alignParentEnd", 21), entry("layout_alignParentLeft", 9), entry("layout_alignParentRight", 11),
                entry("layout_alignParentStart", 20), entry("layout_alignParentTop", 10), entry("layout_alignRight", 7),
                entry("layout_alignStart", 18), entry("layout_alignTop", 6),
                // layout_alignWithParentIfMissing 不在规则表：它是 LayoutParams 布尔属性，
                // 走 handleLayoutParam 的专用分支设字段，而非 addRule（旧表误映射 rule 0=LEFT_OF）
                entry("layout_below", 3), entry("layout_centerHorizontal", 14), entry("layout_centerInParent", 13),
                entry("layout_centerVertical", 15), entry("layout_toEndOf", 17), entry("layout_toLeftOf", 0),
                entry("layout_toRightOf", 1), entry("layout_toStartOf", 16)
        );
        UNIT_MAP = Map.of(
                "px", TypedValue.COMPLEX_UNIT_PX, "dp", TypedValue.COMPLEX_UNIT_DIP,
                "sp", TypedValue.COMPLEX_UNIT_SP, "pt", TypedValue.COMPLEX_UNIT_PT,
                "in", TypedValue.COMPLEX_UNIT_IN, "mm", TypedValue.COMPLEX_UNIT_MM
        );
        // 每次 resolve 都新造实例：Behavior 是有状态的（per-child），静态共享一份
        // 会让双页面互相踩状态、且单例钉住首个 child 的引用
        BEHAVIOR_MAP = new HashMap<>();
        BEHAVIOR_MAP.put("@string/appbar_scrolling_view_behavior", () -> new AppBarLayout.ScrollingViewBehavior());
        BEHAVIOR_MAP.put("@string/bottom_sheet_behavior", () -> new BottomSheetBehavior<View>());
        BEHAVIOR_MAP.put("@string/side_sheet_behavior", () -> new SideSheetBehavior<View>());
        BEHAVIOR_MAP.put("@string/hide_bottom_view_on_scroll_behavior", () -> new HideBottomViewOnScrollBehavior<View>());
        BEHAVIOR_MAP.put("@string/hide_view_on_scroll_behavior", () -> new HideViewOnScrollBehavior<View>());
        BEHAVIOR_MAP.put("@string/searchbar_scrolling_view_behavior", () -> new SearchBar.ScrollingViewBehavior());
        BEHAVIOR_MAP.put("@string/fab_transformation_scrim_behavior", () -> new FabTransformationScrimBehavior());
        BEHAVIOR_MAP.put("@string/fab_transformation_sheet_behavior", () -> new FabTransformationSheetBehavior());
    }

    private final Context mContext;
    private final DisplayMetrics mDisplayMetrics;
    private final Map<String, LuaValue> mViewMap = new HashMap<>();
    private final LuaValue mContextValue;

    // ==================== 实例字段 ====================
    private final LuaContext mLuaContext;
    /**
     * 资源引用解析缓存（含负缓存）。static 共享：键 {@code fieldName|kind|ref}
     * 与 {@code Context}/主题无关，资源 id 进程内稳定；值 {@code ResourceRef}
     * 只持 id，无泄漏风险。getIdentifier 是全表扫描，命中缓存可整段跳过。
     */
    private static final Map<String, ResourceRef> RESOURCE_CACHE =
            Collections.synchronizedMap(new HashMap<>());
    /**
     * 4 参构造器缓存（含负缓存）。跨 {@code LuaLayout} 实例共享：键是 {@code Class}，
     * 值只由该 Class 决定，与 {@code Context}/主题无关，故不必每次 {@code loadlayout}
     * 重新反射探测。
     *
     * <p><b>必须是 {@code WeakHashMap}</b>：Lua 可经 {@code import} 从自定义
     * {@code ClassLoader} 绑类，强键会把该 loader 钉在进程里
     * （`classLoaderRetentionTests` 守的正是这条）。值 {@code Constructor} 持有其
     * 声明类，故键弱可达时整条目一并回收。
     */
    private static final Map<Class<?>, Constructor<?>> CTOR_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * 解析值缓存（LRU）。static 共享：loadlayout 每次调用新建 LuaLayout，
     * 实例级缓存在列表滚动等高频路径上形同虚设。缓存值含 dp/sp/百分比
     * 换算结果，随 density 与屏幕尺寸变化，故键拼入 metrics 指纹。
     */
    private static final Map<String, Object> PARSE_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > 128;
                }
            });
    /**
     * 主题属性值缓存
     */
    private final Map<String, Object> mThemeCache = new HashMap<>();
    // 与 static ID_MAP 配套的全局发号器（计数器必须全局唯一：ID_MAP 是 static 的，
    // 两个 LuaLayout 各自从 ID_START 起号会互相覆盖同名映射）
    private static final AtomicInteger NEXT_VIEW_ID =
            new AtomicInteger(ID_START);

    public LuaLayout(Context context) {
        mContext = context;
        mDisplayMetrics = context.getResources().getDisplayMetrics();
        mContextValue = Coercion.toLua(context);
        mLuaContext = mContextValue.touserdata(LuaContext.class);
    }

    private static Map.Entry<String, Integer> entry(String key, int value) {
        return Map.entry(key, value);
    }

    private static Map<String, Integer> initValueMap() {
        var map = new HashMap<String, Integer>(128);
        map.put("auto", 0);
        map.put("low", 1);
        map.put("high", 2);
        map.put("yes", 1);
        map.put("no", 2);
        map.put("none", 0);
        map.put("software", 1);
        map.put("hardware", 2);
        map.put("ltr", 0);
        map.put("rtl", 1);
        map.put("inherit", 2);
        map.put("locale", 3);
        map.put("insideOverlay", 0x0);
        map.put("insideInset", 0x01000000);
        map.put("outsideOverlay", 0x02000000);
        map.put("outsideInset", 0x03000000);
        map.put("visible", 0);
        map.put("invisible", 4);
        map.put("gone", 8);
        map.put("wrap_content", -2);
        map.put("fill_parent", -1);
        map.put("match_parent", -1);
        map.put("wrap", -2);
        map.put("fill", -1);
        map.put("match", -1);
        map.put("web", 0x01);
        map.put("email", 0x02);
        map.put("phon", 0x04);
        map.put("map", 0x08);
        map.put("all", 0x0f);
        map.put("vertical", 1);
        map.put("horizontal", 0);
        map.put("axis_clip", 8);
        map.put("axis_pull_after", 4);
        map.put("axis_pull_before", 2);
        map.put("axis_specified", 1);
        map.put("axis_x_shift", 0);
        map.put("axis_y_shift", 4);
        map.put("bottom", 80);
        map.put("center", 17);
        map.put("center_horizontal", 1);
        map.put("center_vertical", 16);
        map.put("clip_horizontal", 8);
        map.put("clip_vertical", 128);
        map.put("display_clip_horizontal", 16777216);
        map.put("display_clip_vertical", 268435456);
        map.put("fill_horizontal", 7);
        map.put("fill_vertical", 112);
        map.put("horizontal_gravity_mask", 7);
        map.put("left", 3);
        map.put("no_gravity", 0);
        map.put("relative_horizontal_gravity_mask", 8388615);
        map.put("relative_layout_direction", 8388608);
        map.put("right", 5);
        map.put("start", 8388611);
        map.put("top", 48);
        map.put("vertical_gravity_mask", 112);
        map.put("end", 8388613);
        map.put("gravity", 1);
        map.put("textStart", 2);
        map.put("textEnd", 3);
        map.put("textCenter", 4);
        map.put("viewStart", 5);
        map.put("viewEnd", 6);
        map.put("text", 0x00000001);
        map.put("textCapCharacters", 0x00001001);
        map.put("textCapWords", 0x00002001);
        map.put("textCapSentences", 0x00004001);
        map.put("textAutoCorrect", 0x00008001);
        map.put("textAutoComplete", 0x00010001);
        map.put("textMultiLine", 0x00020001);
        map.put("textImeMultiLine", 0x00040001);
        map.put("textNoSuggestions", 0x00080001);
        map.put("textUri", 0x00000011);
        map.put("textEmailAddress", 0x00000021);
        map.put("textEmailSubject", 0x00000031);
        map.put("textShortMessage", 0x00000041);
        map.put("textLongMessage", 0x00000051);
        map.put("textPersonName", 0x00000061);
        map.put("textPostalAddress", 0x00000071);
        map.put("textPassword", 0x00000081);
        map.put("textVisiblePassword", 0x00000091);
        map.put("textWebEditText", 0x000000a1);
        map.put("textFilter", 0x000000b1);
        map.put("textPhonetic", 0x000000c1);
        map.put("textWebEmailAddress", 0x000000d1);
        map.put("textWebPassword", 0x000000e1);
        map.put("number", 0x00000002);
        map.put("numberSigned", 0x00001002);
        map.put("numberDecimal", 0x00002002);
        map.put("numberPassword", 0x00000012);
        map.put("phone", 0x00000003);
        map.put("datetime", 0x00000004);
        map.put("date", 0x00000014);
        map.put("time", 0x00000024);
        map.put("normal", 0x00000000);
        map.put("actionUnspecified", 0x00000000);
        map.put("actionNone", 0x00000001);
        map.put("actionGo", 0x00000002);
        map.put("actionSearch", 0x00000003);
        map.put("actionSend", 0x00000004);
        map.put("actionNext", 0x00000005);
        map.put("actionDone", 0x00000006);
        map.put("actionPrevious", 0x00000007);
        map.put("flagNoFullscreen", 0x2000000);
        map.put("flagNavigatePrevious", 0x4000000);
        map.put("flagNavigateNext", 0x8000000);
        map.put("flagNoExtractUi", 0x10000000);
        map.put("flagNoAccessoryAction", 0x20000000);
        map.put("flagNoEnterAction", 0x40000000);
        map.put("flagForceAscii", -0x80000000);
        map.put("noScroll", 0);
        map.put("scroll", 1);
        map.put("exitUntilCollapsed", 2);
        map.put("enterAlways", 4);
        map.put("enterAlwaysCollapsed", 8);
        map.put("snap", 16);
        map.put("snapMargins", 32);
        map.put("pin", 1);
        map.put("parallax", 2);
        return Map.copyOf(map);
    }

    public static int parseColor(String colorStr) {
        if (colorStr == null || colorStr.isEmpty() || colorStr.charAt(0) != '#') return 0;
        long color = parseHexLong(colorStr, 1, colorStr.length());
        if (colorStr.length() <= 7) color |= 0xFF000000L;
        return (int) color;
    }

    private static int tryParseInt(String s, int defaultValue) {
        if (s.isEmpty()) return defaultValue;
        int start = 0;
        boolean negative = false;
        if (s.charAt(0) == '-') {
            negative = true;
            start = 1;
            if (s.length() == 1) return defaultValue;
        }

        int result = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return defaultValue;
            result = result * 10 + (c - '0');
        }
        return negative ? -result : result;
    }

    private static long tryParseLong(String s, long defaultValue) {
        if (s.isEmpty()) return defaultValue;
        int start = 0;
        boolean negative = false;
        if (s.charAt(0) == '-') {
            negative = true;
            start = 1;
            if (s.length() == 1) return defaultValue;
        }

        long result = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return defaultValue;
            result = result * 10 + (c - '0');
        }
        return negative ? -result : result;
    }

    private static float tryParseFloat(String s, int start, int end, float defaultValue) {
        if (start >= end) return defaultValue;
        boolean negative = false;
        if (s.charAt(start) == '-') {
            negative = true;
            start++;
            if (start >= end) return defaultValue;
        }

        long integerPart = 0;
        int i = start;
        boolean hasDigits = false;
        while (i < end) {
            char c = s.charAt(i);
            if (c == '.' || c == 'e' || c == 'E') break;
            if (c < '0' || c > '9') return defaultValue;
            integerPart = integerPart * 10 + (c - '0');
            hasDigits = true;
            i++;
        }
        if (!hasDigits) return defaultValue;
        float fraction = 0;
        if (i < end && s.charAt(i) == '.') {
            i++;
            float divisor = 10;
            while (i < end) {
                char c = s.charAt(i);
                if (c == 'e' || c == 'E') break;
                if (c < '0' || c > '9') return defaultValue;
                fraction += (c - '0') / divisor;
                divisor *= 10;
                i++;
            }
        }

        float result = integerPart + fraction;

        // 科学计数法

        if (i < end && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            return defaultValue; // 简化：回退到 Double.parseDouble
        }
        return negative ? -result : result;
    }

    private static double tryParseDouble(String s, double defaultValue) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseHexLong(String s, int start, int end) {
        long result = 0;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            int digit;
            if (c >= '0' && c <= '9') digit = c - '0';
            else if (c >= 'a' && c <= 'f') digit = c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') digit = c - 'A' + 10;
            else break;
            result = result * 16 + digit;
        }
        return result;
    }

    public Map<String, Integer> getIdMap() {
        return ID_MAP;
    }

    public Map<String, LuaValue> getViewMap() {
        return mViewMap;
    }

    @Nullable
    public LuaValue getView(String id) {
        return mViewMap.get(id);
    }

    public int type() {
        return LuaValue.TUSERDATA;
    }

    // ==================== 资源解析 ====================

    public String typeName() {
        return "LuaLayout";
    }

    public LuaValue get(LuaValue key) {
        return get(key.toJavaString());
    }

    public LuaValue get(String key) {
        return mViewMap.get(key);
    }

    // ==================== 值解析（热路径  -  重点优化） ====================

    private int obtainViewId(String idString) {
        Integer id = ID_MAP.get(idString);
        if (id == null) {
            id = NEXT_VIEW_ID.getAndIncrement();
            ID_MAP.put(idString, id);
        }
        return id;
    }

    private ResourceRef resolveResource(LuaValue value, String fieldName) {
        var kind = "styleAttr".equals(fieldName) || (value.isstring() && value.toJavaString().charAt(0) == '?')
                ? ResourceRef.Kind.ATTR : ResourceRef.Kind.STYLE;
        if (value.isnil()) return new ResourceRef(0, kind);
        if (value.isnumber()) return new ResourceRef(value.toint(), kind);
        if (!value.isstring()) {
            mLuaContext.sendMsg("loadlayout: " + fieldName + " requires resource id or string, got " + value.typeName());
            return new ResourceRef(0, kind);
        }

        var ref = value.toJavaString().trim();
        if (ref.isEmpty() || "nil".equals(ref)) return new ResourceRef(0, kind);

        // 尝试直接解析为数字

        int parsedInt = tryParseInt(ref, Integer.MIN_VALUE);
        if (parsedInt != Integer.MIN_VALUE) return new ResourceRef(parsedInt, kind);
        var cacheKey = fieldName + "|" + kind.name() + "|" + ref;
        var cached = RESOURCE_CACHE.get(cacheKey);
        if (cached != null) return cached;
        int resolved = resolveResourceId(ref, kind);
        if (resolved == 0)
            mLuaContext.sendMsg("loadlayout: cannot resolve " + fieldName + " resource '" + ref + "'");
        var result = new ResourceRef(resolved, kind);
        RESOURCE_CACHE.put(cacheKey, result);
        return result;
    }

    @SuppressLint("DiscouragedApi")
    private int resolveResourceId(String ref, ResourceRef.Kind kind) {
        var normalized = ref;
        if (normalized.startsWith("?attr/")) normalized = normalized.substring(6);
        else if (normalized.startsWith("?android:attr/"))
            normalized = "android:" + normalized.substring(14);
        else if (normalized.charAt(0) == '?') normalized = normalized.substring(1);
        else if (normalized.charAt(0) == '@') normalized = normalized.substring(1);
        int slashIndex = normalized.indexOf('/');
        String typeName, entryName;
        if (slashIndex >= 0) {
            typeName = normalized.substring(0, slashIndex);
            entryName = normalized.substring(slashIndex + 1);
        } else {
            typeName = kind == ResourceRef.Kind.ATTR ? "attr" : "style";
            entryName = normalized;
        }

        boolean isAndroid = typeName.startsWith("android:");
        var cleanType = typeName.replace("android:", "");
        var cleanName = entryName.replace("android:", "");
        return mContext.getResources().getIdentifier(cleanName, cleanType, isAndroid ? "android" : mContext.getPackageName());
    }

    /**
     * 值位置的资源引用（"@dimen/space_8" 等），按资源类型换算成对应的值：
     * dimen -> px（int）、color -> ARGB int、string -> 文本。
     * 不认识的类型（如 @style/）返回 null，交由调用方按字符串处理。
     */
    @Nullable
    private Object resolveResourceReference(String ref) {
        var cached = mThemeCache.get(ref);
        if (cached != null) return cached;
        var body = ref.substring(1);
        int slash = body.indexOf('/');
        if (slash <= 0 || slash == body.length() - 1) return null;
        var type = body.substring(0, slash);
        var name = body.substring(slash + 1);
        boolean isAndroid = type.startsWith("android:");
        if (isAndroid) type = type.substring(8);
        int resId = mContext.getResources().getIdentifier(name, type,
                isAndroid ? "android" : mContext.getPackageName());
        if (resId == 0) {
            mLuaContext.sendMsg("loadlayout: unknown resource '" + ref + "'");
            return null;
        }
        Object result = switch (type) {
            case "dimen" -> (int) mContext.getResources().getDimension(resId);
            case "color" -> mContext.getResources().getColor(resId, mContext.getTheme());
            case "string" -> mContext.getResources().getString(resId);
            default -> null;
        };
        if (result != null) mThemeCache.put(ref, result);
        return result;
    }

    @Nullable
    private Object resolveThemeAttribute(String ref) {
        if (ref.charAt(0) != '?') return null;

        // 检查主题属性缓存

        var cached = mThemeCache.get(ref);
        if (cached != null) return cached;
        var attrName = ref.substring(1);
        if (attrName.startsWith("attr/")) attrName = attrName.substring(5);
        else if (attrName.startsWith("android:attr/")) attrName = attrName.substring(13);
        if (attrName.isEmpty()) return null;
        boolean isAndroid = ref.contains("android:");
        @SuppressLint("DiscouragedApi") int attrId = mContext.getResources().getIdentifier(attrName, "attr", isAndroid ? "android" : mContext.getPackageName());
        if (attrId == 0) return null;
        var outValue = new TypedValue();
        if (!mContext.getTheme().resolveAttribute(attrId, outValue, true)) return null;
        Object result = switch (outValue.type) {
            case TypedValue.TYPE_DIMENSION -> outValue.getDimension(mDisplayMetrics);
            case TypedValue.TYPE_FLOAT -> outValue.getFloat();
            case TypedValue.TYPE_STRING ->
                    outValue.string != null ? parseValue(outValue.string.toString()) : null;
            default ->
                    (outValue.type >= TypedValue.TYPE_FIRST_INT && outValue.type <= TypedValue.TYPE_LAST_INT)
                            ? outValue.data : (outValue.resourceId != 0 ? outValue.resourceId : outValue.data);
        };
        if (result != null) mThemeCache.put(ref, result);
        return result;
    }

    @Nullable
    private Object parseValue(String str) {
        if (str == null || str.isEmpty()) return 0;
        if ("nil".equals(str)) return 0;

        char first = str.charAt(0);
        // "?attr/..."、"@dimen/..." 等引用解析结果随 Theme 变化（日夜切换、换主题），
        //   不进进程级 PARSE_CACHE（键只含 metrics 指纹）；实例级 mThemeCache 供
        //   同一次 loadlayout 内复用
        if (first == '?' || first == '@') {
            Object result = parseValueCore(str);
            return result == null ? str : result;
        }

        // 快速路径：查缓存（键拼 metrics 指纹，见 PARSE_CACHE 说明）
        var cacheKey = str + '@' + mDisplayMetrics.densityDpi
                + 'x' + mDisplayMetrics.widthPixels + 'x' + mDisplayMetrics.heightPixels;
        var cached = PARSE_CACHE.get(cacheKey);
        if (cached != null) return cached;
        Object result = parseValueCore(str);

        // 仅缓存数值/常量结果，不缓存任意字符串（太泛，缓存命中率低）

        if (result != null && !(result instanceof String)) {
            PARSE_CACHE.put(cacheKey, result);
        }
        return result;
    }

    // ==================== 无异常数字解析（避免 NumberFormatException 堆栈开销） ====================

    @Nullable
    private Object parseValueCore(String str) {

        // 1. 管道分隔的位掩码（如 "bold|italic"）

        if (str.indexOf('|') >= 0) {
            return parseBitmask(str);
        }

        // 2. 常量映射（最高命中率）

        var constant = XML_CONSTANT_MAP.get(str);
        if (constant != null) return constant;
        int len = str.length();

        // 3. 颜色

        if (len > 0 && str.charAt(0) == '#') return parseColor(str);

        // 4. 百分比（如 "50%"、"50%w"、"50%h"）
        //    连续两个 % 的写法（"50%%"）无需特判：百分数部分含 '%' 时 tryParseFloat
        //    返回 NaN 落空，随后分支的轴字符也不是 w/h，同样落空。

        if (len > 1 && str.charAt(len - 1) == '%') {
            float pct = tryParseFloat(str, 0, len - 1, Float.NaN);
            if (!Float.isNaN(pct)) return (int) (pct * mLuaContext.getWidth() / 100);
        }
        if (len >= 3 && str.charAt(len - 2) == '%') {
            float v = tryParseFloat(str, 0, len - 2, Float.NaN);
            if (!Float.isNaN(v)) {
                char axis = str.charAt(len - 1);
                if (axis == 'w') return (int) (v * mLuaContext.getWidth() / 100);
                if (axis == 'h') return (int) (v * mLuaContext.getHeight() / 100);
            }
        }

        // 5. 单位后缀（如 "16dp"、"12sp"）

        if (len >= 3) {
            var unitStr = str.substring(len - 2);
            var unitType = UNIT_MAP.get(unitStr);
            if (unitType != null) {
                float v = tryParseFloat(str, 0, len - 2, Float.NaN);
                if (!Float.isNaN(v))
                    return (int) TypedValue.applyDimension(unitType, v, mDisplayMetrics);
            }
        }

        // 6. 主题属性（如 "?attr/colorPrimary"）

        var trimmed = str.trim();
        if (!trimmed.isEmpty() && trimmed.charAt(0) == '?') {
            var themeValue = resolveThemeAttribute(trimmed);
            if (themeValue != null) return themeValue;
        }

        // 6b. 资源引用（如 "@dimen/space_8"、"@color/xxx"、"@android:color/xxx"）

        if (!trimmed.isEmpty() && trimmed.charAt(0) == '@') {
            var resValue = resolveResourceReference(trimmed);
            if (resValue != null) return resValue;
        }

        // 7. 纯数字（无异常解析，避免 NumberFormatException 开销）

        long longVal = tryParseLong(str, Long.MIN_VALUE);
        if (longVal != Long.MIN_VALUE) return longVal;
        double doubleVal = tryParseDouble(str, Double.NaN);
        if (!Double.isNaN(doubleVal)) return doubleVal;

        // 8. 无法解析，返回原始字符串

        return str;
    }

    /**
     * 解析管道分隔的位掩码值
     */
    private int parseBitmask(String str) {
        int result = 0;
        var parts = PIPE_PATTERN.split(str);
        for (var part : parts) {
            var val = XML_CONSTANT_MAP.get(part);
            if (val != null) result |= val;
        }
        return result;
    }

    /**
     * 将 LuaValue 转换为像素值，用于 margin/padding 等成对属性
     */
    private int toPixelValue(LuaValue value) {
        if (value.isnumber()) return value.toint();
        Object parsed = parseValue(value.toJavaString());
        if (parsed instanceof Number num) return num.intValue();
        return 0;
    }

    private StyleConfig parseViewStyle(LuaValue layout) {
        var theme = resolveResource(layout.get("theme"), "theme");
        var styleAttr = resolveResource(layout.get("styleAttr"), "styleAttr");
        var styleRes = resolveResource(layout.get("styleRes"), "styleRes");
        var legacy = resolveResource(layout.get("style"), "style");
        return new StyleConfig(
                theme.id(),
                styleAttr.valid() ? styleAttr.id() : (legacy.kind() == ResourceRef.Kind.ATTR ? legacy.id() : 0),
                styleRes.valid() ? styleRes.id() : 0,
                legacy.kind() == ResourceRef.Kind.STYLE ? legacy.id() : 0
        );
    }

    private LuaValue createViewWithStyle(LuaValue viewClass, LuaValue layout) {
        var style = parseViewStyle(layout);
        if (!style.hasAny()) return JavaCall.construct(viewClass, mContextValue);
        var themedContext = createThemedContext(style);
        var themedLuaContext = Coercion.toLua(themedContext);
        var nil = LuaValue.NIL;

        // 按样式键实际设了哪几个逐个试构造器：正确的构造器取决于本次 layout 设的是
        //   theme / styleAttr / styleRes / style 中的哪些，不能只按 View 类缓存"上次
        //   成功的元数"——同一个类换一种样式键组合就会拿到错的那个（丢默认 style 或
        //   丢 styleRes），且这类错配不抛异常，缓存永远不会被驱逐

        var attempts = new ArrayList<String>();
        var failures = new ArrayList<String>();
        if (style.attr() != 0 && style.res() != 0 && viewClass.isuserdata(Class.class)) {
            attempts.add("(Context, AttributeSet?, defStyleAttr, defStyleRes)");
            try {
                var result = instantiateView(viewClass, themedContext, null, style.attr(), style.res());
                // null = 类没有 4 参构造器，继续尝试后续构造器
                if (result != null) return result;
                failures.add("(Context, AttributeSet?, defStyleAttr, defStyleRes) -> no such constructor");
            } catch (Exception e) {
                failures.add("(Context, AttributeSet?, defStyleAttr, defStyleRes) -> " + e.getMessage());
            }
        }
        if (style.attr() != 0) {
            attempts.add("(Context, AttributeSet?, defStyleAttr)");
            try {
                return JavaCall.construct(viewClass, themedLuaContext, nil, Coercion.toLua(style.attr()));
            } catch (Exception e) {
                failures.add("(Context, AttributeSet?, defStyleAttr) -> " + e.getMessage());
            }
        }
        if (style.res() != 0) {
            attempts.add("(Context, AttributeSet?, styleRes)");
            try {
                return JavaCall.construct(viewClass, themedLuaContext, nil, Coercion.toLua(style.res()));
            } catch (Exception e) {
                failures.add("(Context, AttributeSet?, styleRes) -> " + e.getMessage());
            }
        }
        if (style.legacy() != 0) {
            attempts.add("legacy (ContextThemeWrapper, AttributeSet?, style)");
            try {
                return JavaCall.construct(viewClass, themedLuaContext, nil, Coercion.toLua(style.legacy()));
            } catch (Exception e) {
                failures.add("legacy -> " + e.getMessage());
            }
        }
        attempts.add("(Context)");
        try {
            return JavaCall.construct(viewClass, themedLuaContext);
        } catch (Exception e) {
            failures.add("(Context) -> " + e.getMessage());
        }

        var viewId = layout.get("id").isstring() ? "[" + layout.get("id").toJavaString() + "] " : "";
        throw LuaErrors.errorObject(
                "loadlayout create View failed " + viewId + viewClass + "\n" +
                        "theme=" + layout.get("theme") + ", style=" + layout.get("style") +
                        ", styleAttr=" + layout.get("styleAttr") + ", styleRes=" + layout.get("styleRes") + "\n" +
                        "Tried: " + String.join(", ", attempts) + "\n" +
                        "Failures: " + String.join("; ", failures)
        );
    }

    // ==================== 样式 & View 创建 ====================

    private Context createThemedContext(StyleConfig style) {
        if (style.theme() != 0) return new ContextThemeWrapper(mContext, style.theme());
        if (style.legacy() != 0 && style.attr() == 0)
            return new ContextThemeWrapper(mContext, style.legacy());
        if (style.res() != 0 && style.attr() == 0 && style.legacy() == 0)
            return new ContextThemeWrapper(mContext, style.res());
        return mContext;
    }

    private LuaValue instantiateView(LuaValue viewClass, Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) throws Exception {
        var clazz = (Class<?>) viewClass.touserdata(Class.class);
        Constructor<?> ctor = CTOR_CACHE.get(clazz);
        // 负缓存须用 containsKey 区分：get()==null 既可能是未探测，也可能是已判明无 4 参构造器
        if (ctor == null && !CTOR_CACHE.containsKey(clazz)) {
            try {
                ctor = clazz.getConstructor(Context.class, AttributeSet.class, Integer.TYPE, Integer.TYPE);
            } catch (NoSuchMethodException e) {
                ctor = null;
            }
            CTOR_CACHE.put(clazz, ctor);
        }
        if (ctor == null) return null;
        return Coercion.toLua(ctor.newInstance(context, attrs, defStyleAttr, defStyleRes));
    }

    public LuaValue load(LuaValue layout) {
        return load(layout, new LuaTable(), Coercion.toLua(ViewGroup.LayoutParams.class));
    }

    public LuaValue load(LuaValue layout, LuaTable env) {
        return load(layout, env, Coercion.toLua(ViewGroup.LayoutParams.class));
    }

    // ==================== 布局加载（主入口） ====================

    public LuaValue load(LuaValue layout, LuaTable env, LuaValue params) {
        var viewClass = layout.get(1);
        if (viewClass.isnil()) {
            var idVal = layout.get("id");
            var idHint = idVal.isstring() ? " (id=\"" + idVal.toJavaString() + "\")" : "";
            throw LuaErrors.errorObject("loadlayout error: First value Must be a Class" + idHint + "\nLayout: " + layout.checktable().toJavaString());
        }

        JavaObject view = (JavaObject) createViewWithStyle(viewClass, layout);
        params = JavaCall.construct(params, WRAP_CONTENT, WRAP_CONTENT);
        boolean isAdapterView = viewClass.isuserdata() && AdapterView.class.isAssignableFrom((Class<?>) viewClass.touserdata(Class.class));

        // 预取 id 字符串（多处使用）

        var idVal = layout.get("id");
        String idStr = idVal.isstring() ? idVal.toJavaString() : null;
        LuaValue key = LuaValue.NIL;
        Varargs next;
        while (!(next = layout.next(key)).isnil(1)) {
            key = next.arg1();
            var value = next.arg(2);
            try {
                if (key.isinteger()) {
                    handleChildElement(key.toint(), value, view, env, viewClass, isAdapterView);
                } else if (key.isstring()) {
                    handleAttribute(key.toJavaString(), value, view, env, params, idStr);
                }
            } catch (Exception e) {
                mLuaContext.sendError("loadlayout " + (idStr != null ? "[" + idStr + "]" : "") + ": " + key + "=" + value, e);
            }
        }
        applyMargins(layout, params, idStr);
        Object target = view.touserdata();
        Object layoutParams = params.touserdata();
        if (target instanceof View androidView && layoutParams instanceof ViewGroup.LayoutParams androidParams) {
            androidView.setLayoutParams(androidParams);
        } else {
            view.set("LayoutParams", params);
        }
        applyPadding(layout, view, idStr);
        return view;
    }

    private void handleChildElement(int index, LuaValue value, JavaObject view, LuaTable env, LuaValue viewClass, boolean isAdapterView) {
        if (index <= 1) return;
        // require 取自全局（C 的 luaopen_package 同样把它注册为全局），不经 Globals.package_
        if (value.isstring()) value = LuaCall.invoke(mLuaContext.getLuaState().get("require"), value).arg1();
        if (isAdapterView) {
            JavaCall.setField(view, "adapter", new LuaAdapter(mLuaContext, value.checktable()));
        } else if (value.isuserdata(View.class)) {
            // 子元素是已实例化的 view（如宿主把 contentView 直接内嵌到布局表），直接加入容器
            Object parent = view.touserdata();
            View androidChild = value.touserdata(View.class);
            if (parent instanceof ViewGroup viewGroup) {
                viewGroup.addView(androidChild);
            } else {
                JavaCall.callLua(view.getJavaMethod("addView"), value);
            }
        } else {
            var child = load(value, env, viewClass.get("LayoutParams"));
            Object parent = view.touserdata();
            Object childView = child.touserdata();
            if (parent instanceof ViewGroup viewGroup && childView instanceof View androidChild) {
                viewGroup.addView(androidChild);
            } else {
                JavaCall.callLua(view.getJavaMethod("addView"), child);
            }
        }
    }

    private void handleAttribute(String key, LuaValue value, JavaObject view, LuaTable env, LuaValue params, String idStr) {
        switch (key) {
            case "style", "styleAttr", "styleRes", "theme", "padding" -> {
            }
            case "id" -> {
                String id = value.toJavaString();
                int viewId = obtainViewId(id);
                view.touserdata(View.class).setId(viewId);
                mViewMap.put(id, view);
                env.set(value, view);
            }
            case "text" -> view.set("text", value.tostring());
            case "hint" -> view.set("hint", value.tostring());
            case "textSize" -> setTextSize(view, value);
            case "textStyle" -> setTextStyle(view, value);
            case "scaleType" -> setScaleType(view, value);
            case "ellipsize" -> setEllipsize(view, value);
            case "items" -> setItems(view, value);
            case "minWidth" ->
                    view.set("MinimumWidth", Coercion.toLua(parseValue(value.toJavaString())));
            case "minHeight" ->
                    view.set("MinimumHeight", Coercion.toLua(parseValue(value.toJavaString())));
            case "pages" -> setPages(view, value.checktable(), env);
            case "pagesWithTitle" -> setPagesWithTitle(view, value.checktable(), env);
            case "src" -> handleSrc(view, value);
            case "background" -> handleBackground(view, value);
            default -> handleDefault(key, value, view, env, params);
        }
    }

    // ==================== 子元素处理 ====================

    private void setTextSize(JavaObject view, LuaValue value) {
        if (view.touserdata() instanceof TextView textView) {
            if (value.isnumber()) {
                textView.setTextSize((float) value.todouble());
                return;
            }
            var str = value.toJavaString();
            Object parsed = parseValue(str);
            if (parsed instanceof Number number) {
                if (isPlainNumber(str)) {
                    // 无单位的纯数字串与 Lua 数字同语义（sp）
                    textView.setTextSize(number.floatValue());
                } else {
                    // 带单位/百分比/?attr：parseValue 已换算成 px
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, number.floatValue());
                }
                return;
            }
        }
        if (value.isnumber()) {
            JavaCall.invokeMember(view.getJavaMethod("setTextSize"), value.tonumber());
        } else {
            var str = value.toJavaString();
            var parsed = parseValue(str);
            if (parsed instanceof Number num) {
                if (isPlainNumber(str)) {
                    JavaCall.callLua(view.getJavaMethod("setTextSize"), num.floatValue());
                } else {
                    JavaCall.callLua(view.getJavaMethod("setTextSize"), TypedValue.COMPLEX_UNIT_PX, num.floatValue());
                }
            }
        }
    }

    // java-only: 字符串是否是纯数字。parseValue 只对这种形态返回未换算的原始值，
    //   带单位后缀、百分比和 ?attr 三种都已在 parseValueCore 里换算成 px（判据必须与
    //   parseValueCore 的"纯数字"分支同款，否则已是 px 的值会被再当 sp 放大一次）
    private static boolean isPlainNumber(String s) {
        return tryParseLong(s, Long.MIN_VALUE) != Long.MIN_VALUE
                || !Double.isNaN(tryParseDouble(s, Double.NaN));
    }

    // ==================== 属性分发 ====================

    private void setTextStyle(JavaObject view, LuaValue value) {
        int style = switch (value.toJavaString()) {
            case "bold" -> Typeface.BOLD;
            case "italic" -> Typeface.ITALIC;
            case "bold|italic", "italic|bold" -> Typeface.BOLD_ITALIC;
            default -> Typeface.NORMAL;
        };
        JavaCall.callLua(view.getJavaMethod("setTypeface"), Typeface.defaultFromStyle(style));
    }

    // ==================== 属性设置方法 ====================

    private void setScaleType(JavaObject view, LuaValue value) {
        var index = SCALE_TYPE_MAP.get(value.toJavaString());
        if (index != null)
            JavaCall.callLua(view.getJavaMethod("setScaleType"), SCALE_TYPES[index]);
    }

    private void setEllipsize(JavaObject view, LuaValue value) {
        try {
            JavaCall.callLua(view.getJavaMethod("setEllipsize"), TextUtils.TruncateAt.valueOf(value.toJavaString().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            mLuaContext.sendMsg("loadlayout: unsupported ellipsize value: " + value.toJavaString());
        }
    }

    private void setItems(JavaObject view, LuaValue value) {
        var adapter = view.get("adapter");
        if (!adapter.isnil()) {
            JavaCall.invokeMember(adapter.get("addAll"), value);
        } else {
            var items = (String[]) Coercion.arrayCoerce(value, String.class);
            JavaCall.callLua(view.getJavaMethod("setAdapter"),
                    new ArrayAdapter<>(mContext, android.R.layout.simple_list_item_1, items));
        }
    }

    private void setPages(JavaObject view, LuaTable viewsTable, LuaTable env) {
        var views = processLuaPages(viewsTable, env);
        var pagerAdapter = new LuaPagerAdapter();
        for (View v : views) pagerAdapter.add(v);
        JavaCall.callLua(view.getJavaMethod("setAdapter"), pagerAdapter);
    }

    private void setPagesWithTitle(JavaObject view, LuaTable table, LuaTable env) {
        var views = processLuaPages(table.get(1).checktable(), env);
        var titleTable = table.get(2).checktable();
        var pagerAdapter = new LuaPagerAdapter();
        for (int i = 0; i < views.size(); i++) {
            pagerAdapter.add(views.get(i), titleTable.get(i + 1).toJavaString());
        }

        JavaCall.callLua(view.getJavaMethod("setAdapter"), pagerAdapter);
    }

    private List<View> processLuaPages(LuaTable viewsTable, LuaTable env) {
        var views = new ArrayList<View>();
        for (int i = 1; i <= viewsTable.length(); i++) {
            var page = viewsTable.get(i);
            View view;
            if (page.isuserdata()) {
                view = page.touserdata(View.class);
            } else if (page.istable()) {
                view = load(page.checktable(), env).touserdata(View.class);
            } else if (page.isstring()) {
                view = load(LuaCall.invoke(mLuaContext.getLuaState().get("require"), page).arg1(), env).touserdata(View.class);
            } else {
                throw LuaErrors.errorObject("Unsupported type for Lua pages: " + page.typeName());
            }
            views.add(view);
        }
        return views;
    }

    private void handleSrc(JavaObject view, LuaValue value) {
        try {
            if (value.isuserdata(Drawable.class)) {
                JavaCall.setField(view, "ImageDrawable", value.touserdata(Drawable.class));
            } else if (value.isuserdata()) {

                // 其他 userdata 类型（如 Bitmap）也直接设置

                JavaCall.setField(view, "ImageBitmap", value.touserdata());
            } else {
                String src = value.toJavaString();
                Object target = view.touserdata();
                if (!(target instanceof View androidView)) return;
                if (target instanceof ImageView imageView
                        && Looper.myLooper() == Looper.getMainLooper()) {
                    // 请求绑定目标 View：View 复用时 Glide 以目标为准顶掉旧请求，
                    //   不会把旧图打进复用的 View。into(ImageView) 断言主线程，
                    //   Lua 工作线程上构造 layout 时走下面的 CustomTarget 路径
                    Glide.with(imageView).load(LuaBitmap.toGlideModel(src)).into(imageView);
                    return;
                }
                Glide.with(androidView).load(LuaBitmap.toGlideModel(src))
                        .into(new CustomTarget<Drawable>() {
                    @Override
                    public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                        JavaCall.setField(view, "ImageDrawable", resource);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                    }
                });
            }
        } catch (Exception e) {
            LuaConfig.logError("LuaLayout", e);
        }
    }

    private void handleBackground(JavaObject view, LuaValue value) {
        if (value.isuserdata()) {
            JavaCall.setField(view, "background", value.touserdata(Drawable.class));
        } else if (value.isnumber()) {
            JavaCall.setField(view, "backgroundColor", value.toint());
        } else if (value.isstring()) {
            var s = value.toJavaString();
            if (s.charAt(0) == '#') {
                JavaCall.setField(view, "backgroundColor", parseColor(s));
            } else {
                JavaCall.setField(view, "background", new LuaBitmapDrawable(mLuaContext, s));
            }
        }
    }

    @Nullable
    private CoordinatorLayout.Behavior<?> createBehaviorFromString(String behaviorString) {
        if (behaviorString == null || behaviorString.isEmpty()) return null;
        var factory = BEHAVIOR_MAP.get(behaviorString);
        return factory != null ? factory.create() : null;
    }

    private void handleDefault(String key, LuaValue value, JavaObject view, LuaTable env, LuaValue params) {
        if (key.length() >= 2 && key.charAt(0) == 'o' && key.charAt(1) == 'n') {
            if (value.isstring()) {
                var finalVal = value;
                value = new LuaFunction() {
                    @Override
                    public Varargs call(Varargs args) {
                        return LuaCall.invoke(env.get(finalVal), args);
                    }
                };
            }
            view.set(key, value);
            return;
        }
        // 必须与 handleLayoutParam 里的 substring(7)（"layout_" 恰 7 字符）同口径；
        // startsWith("layout") 会把 layoutAnimation 之类送进去截出 "nimation"
        if (key.startsWith("layout_")) {
            handleLayoutParam(key, value, params);
            return;
        }
        if (setCommonViewProperty(key, value, view)) return;
        if (value.type() == LuaValue.TSTRING)
            value = Coercion.toLua(parseValue(value.toJavaString()));
        view.set(key, value);
    }

    /**
     * java-only: Lua 布局仍按运行时表逐项解释；这里只为 Android View 的固定 setter
     * 提供等价直调，未知键和不匹配类型继续回落通用 JavaObject.set。
     */
    private boolean setCommonViewProperty(String key, LuaValue value, JavaObject view) {
        Object target = view.touserdata();
        if (!(target instanceof View androidView)) return false;
        switch (key) {
            case "clickable" -> {
                if (!value.isboolean()) return false;
                androidView.setClickable(value.toboolean());
            }
            case "focusable" -> {
                if (!value.isboolean()) return false;
                androidView.setFocusable(value.toboolean());
            }
            case "textColor" -> {
                if (!(androidView instanceof TextView textView) || !value.isnumber()) return false;
                textView.setTextColor(value.toint());
            }
            case "typeface" -> {
                if (!(androidView instanceof TextView textView) || !value.isuserdata(Typeface.class)) return false;
                textView.setTypeface(value.touserdata(Typeface.class));
            }
            case "orientation" -> {
                if (!(androidView instanceof LinearLayout linearLayout)) return false;
                Integer parsed = parseViewInteger(value);
                if (parsed == null) return false;
                linearLayout.setOrientation(parsed);
            }
            case "gravity" -> {
                if (!(androidView instanceof LinearLayout linearLayout)) return false;
                Integer parsed = parseViewInteger(value);
                if (parsed == null) return false;
                linearLayout.setGravity(parsed);
            }
            case "clipToPadding" -> {
                if (!(androidView instanceof ViewGroup viewGroup) || !value.isboolean()) return false;
                viewGroup.setClipToPadding(value.toboolean());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private Integer parseViewInteger(LuaValue value) {
        if (value.isnumber()) return value.toint();
        if (!value.isstring()) return null;
        Object parsed = parseValue(value.toJavaString());
        return parsed instanceof Number number ? number.intValue() : null;
    }

    // ==================== Behavior ====================

    private void handleLayoutParam(String key, LuaValue value, LuaValue params) {
        Integer rule = RULE_MAP.get(key);
        if (rule != null) {
            if ((value.isboolean() && value.toboolean()) || "true".equals(value.toJavaString())) {
                JavaCall.callLua(params.get("addRule"), rule);
            } else {
                var targetId = ID_MAP.get(value.toJavaString());
                if (targetId != null) JavaCall.callLua(params.get("addRule"), rule, targetId);
                else
                    mLuaContext.sendMsg("loadlayout: " + key + " references undefined id '" + value.toJavaString() + "'");
            }
            return;
        }
        switch (key) {
            case "layout_alignWithParentIfMissing" -> {
                // 属性 alignWithParentIfMissing 对应 RL.LayoutParams 的字段是 alignWithParent；非 RL 参数跳过
                if (params.touserdata() instanceof RelativeLayout.LayoutParams rlParams)
                    rlParams.alignWithParent = (value.isboolean() && value.toboolean())
                            || "true".equals(value.toJavaString());
            }
            case "layout_behavior" -> {
                var behavior = createBehaviorFromString(value.toJavaString());
                JavaCall.callLua(params.get("setBehavior"), Objects.requireNonNullElse(behavior, value));
            }
            case "layout_anchor" -> {
                var anchorId = ID_MAP.get(value.toJavaString());
                if (anchorId != null) JavaCall.callLua(params.get("setAnchorId"), anchorId);
                else
                    mLuaContext.sendMsg("loadlayout: layout_anchor references undefined id '" + value.toJavaString() + "'");
            }
            case "layout_collapseParallaxMultiplier" ->
                    JavaCall.callLua(params.get("setParallaxMultiplier"), coerceNumeric(value));
            case "layout_marginEnd" ->
                    JavaCall.callLua(params.get("setMarginEnd"), coerceNumeric(value));
            case "layout_marginStart" ->
                    JavaCall.callLua(params.get("setMarginStart"), coerceNumeric(value));
            case "layout_collapseMode" ->
                    JavaCall.callLua(params.get("setCollapseMode"), coerceNumeric(value));
            case "layout_scrollFlags" ->
                    JavaCall.callLua(params.get("setScrollFlags"), coerceNumeric(value));
            default -> params.set(key.substring(7), coerceNumeric(value));
        }
    }

    // ==================== 默认属性处理 ====================

    /**
     * 将 LuaValue 转换为数值 LuaValue，字符串经 parseValue 解析
     */
    private LuaValue coerceNumeric(LuaValue value) {
        return value.isnumber() ? value : Coercion.toLua(parseValue(value.toJavaString()));
    }

    private void applyMargins(LuaValue layout, LuaValue params, String idStr) {
        try {
            boolean hasMargin = false;
            int left = 0, top = 0, right = 0, bottom = 0;
            for (int i = 0; i < MARGIN_KEYS.length; i++) {
                var margin = layout.get(MARGIN_KEYS[i]);
                if (margin.isnil()) margin = layout.get("layout_margin");
                if (!margin.isnil()) {
                    hasMargin = true;
                    int px = toPixelValue(margin);
                    switch (i) {
                        case 0 -> left = px;
                        case 1 -> top = px;
                        case 2 -> right = px;
                        case 3 -> bottom = px;
                    }
                }
            }
            if (hasMargin) {
                JavaCall.callLua(params.get("setMargins"), left, top, right, bottom);
            }
        } catch (Exception e) {
            mLuaContext.sendError("loadlayout margin error " + (idStr != null ? "[" + idStr + "]" : ""), e);
        }
    }

    private void applyPadding(LuaValue layout, JavaObject view, String idStr) {
        try {
            boolean hasPadding = false;
            int left = 0, top = 0, right = 0, bottom = 0;
            for (int i = 0; i < PADDING_KEYS.length; i++) {
                var padding = layout.get(PADDING_KEYS[i]);
                if (padding.isnil()) padding = layout.get("padding");
                if (!padding.isnil()) {
                    hasPadding = true;
                    int px = toPixelValue(padding);
                    switch (i) {
                        case 0 -> left = px;
                        case 1 -> top = px;
                        case 2 -> right = px;
                        case 3 -> bottom = px;
                    }
                }
            }
            if (hasPadding) {
                JavaCall.callLua(view.getJavaMethod("setPadding"), left, top, right, bottom);
            }
        } catch (Exception e) {
            mLuaContext.sendError("loadlayout padding error " + (idStr != null ? "[" + idStr + "]" : ""), e);
        }
    }

    // ==================== Margin / Padding（成对处理） ====================

    private record ResourceRef(int id, Kind kind) {
        boolean valid() {
            return id != 0;
        }

        enum Kind {ATTR, STYLE}
    }

    private record StyleConfig(int theme, int attr, int res, int legacy) {
        boolean hasAny() {
            return (theme | attr | res | legacy) != 0;
        }
    }
}
