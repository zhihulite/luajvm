package org.luajvm.android.lib;

import org.luajvm.android.runtime.LuaConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.luajvm.bind.Coercion;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.util.Iterator;

public class json extends LuaFunction {
    public static String encode(LuaValue value) {
        return toJson(value).toString();
    }

    private static Object toJson(LuaValue value) {
        LuaTable table = value.checktable();
        int length = arrayLength(table);
        if (length >= 0) {
            JSONArray arr = new JSONArray();
            for (int i = 1; i <= length; i++) {
                arr.put(checkJsonValue(toJsonValue(table.get(i)), "[" + i + "]"));
            }
            return arr;
        }

        JSONObject obj = new JSONObject();
        Varargs pair = value.next(LuaValue.NIL);
        while (!pair.isnil(1)) {
            // NaN/Infinity 一类非法值静默丢键会让产物无声缺字段，直接上抛
            try {
                obj.put(pair.arg1().toJavaString(), toJsonValue(pair.arg(2)));
            } catch (JSONException e) {
                // LuaError(Exception) 走 LuaString.newStr(e.getMessage())，message 为 null 时二次 NPE
                throw LuaErrors.errorObject(String.valueOf(e), e);
            }
            pair = value.next(pair.arg1());
        }
        return obj;
    }

    /**
     * JSON 没有 Lua table 的统一类型标记，因此仅把键恰好为连续整数 1..n 的表编码为数组。
     * 空表编码为 []；字符串键、稀疏整数键和混合键表都编码为对象。
     */
    private static int arrayLength(LuaTable table) {
        int count = 0;
        int max = 0;
        Varargs pair = table.next(LuaValue.NIL);
        while (!pair.isnil(1)) {
            LuaValue key = pair.arg1();
            if (!key.isinteger()) return -1;
            long integerKey = key.tolong();
            if (integerKey < 1 || integerKey > Integer.MAX_VALUE) return -1;
            count++;
            max = Math.max(max, (int) integerKey);
            pair = table.next(key);
        }
        return count == max ? count : -1;
    }

    private static Object toJsonValue(LuaValue value) {
        if (value.istable()) return toJson(value);
        // 字符串判定必须先于数值：纯数字串 isnumber() 为真（可扫描成数），
        // 落进数值分支会取基类 todouble()=0，把数字 ID 字符串静默编成 0
        if (value.isstring()) return value.toJavaString();
        // 整数必须保 Long：Coercion.toJava(v, Object.class) 会截成 int，
        // 2^40 一类的值直接变 0（org.json 接受 Long，无需降位）
        if (value.isinteger()) return value.tolong();
        if (value.isnumber()) return value.todouble();
        return Coercion.toJava(value, Object.class);
    }

    /**
     * 数组元素的合法性自查：JSONArray.put(Object) 不校验 NaN/Infinity，非法值要等到
     * toString() 才失败并让 encode 整体返回 nil。与对象路径同款、同一处抛出。
     */
    private static Object checkJsonValue(Object v, String where) {
        if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
            throw LuaErrors.errorObject("json.encode: " + where + " 不是合法 JSON 数值（" + d + "）");
        }
        return v;
    }

    public static LuaValue decode(String text) {
        try {
            // 前导 BOM/空白会让 startsWith("[") 把合法 JSON 误判成对象路径
            String t = text;
            if (!t.isEmpty() && t.charAt(0) == '\uFEFF') t = t.substring(1);
            t = t.trim();
            Object parsed = t.startsWith("[")
                    ? new JSONArray(t)
                    : new JSONObject(t);

            return toLuaTable(parsed);
        } catch (Exception e) {
            LuaConfig.logError("json", e);
            // message 可能为 null（OOM 等），裸传会在 LuaError 二次 NPE
            String msg = e.getMessage();
            throw LuaErrors.errorObject(msg != null ? msg : e.toString());
        }
    }

    private static LuaValue toLuaTable(Object obj) {
        if (obj instanceof JSONObject jsonObject) {
            LuaTable luaTable = new LuaTable();
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                luaTable.set(LuaValue.valueOf(key), toLuaTable(jsonObject.opt(key)));
            }
            return luaTable;
        }
        if (obj instanceof JSONArray jsonArray) {
            LuaTable luaTable = new LuaTable();
            int length = jsonArray.length();
            for (int i = 0; i < length; i++) {
                luaTable.set(i + 1, toLuaTable(jsonArray.opt(i)));
            }
            return luaTable;
        }
        // JSON null 落 nil：Android 的 org.json 用 JSONObject.NULL 哨兵而非 Java null，
        //   直接交 Coercion 会包成 userdata ⇒ Lua 里 t.a == nil 为假
        if (obj == null || obj == JSONObject.NULL) return LuaValue.NIL;
        // String/Number/Boolean -> LuaValue
        return Coercion.toLua(obj);
    }

    @Override
    public Varargs call(Varargs args) {
        LuaValue env = args.arg(2);
        LuaTable module = new LuaTable();
        module.set("decode", new decode());
        module.set("encode", new encode());
        env.set("json", module);
        LuaValue pkg = env.get("package");
        if (!pkg.isnil()) pkg.get("loaded").set("json", module);
        return LuaValue.NIL;
    }

    private static class decode extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            return decode(arg.toJavaString());
        }
    }

    private static class encode extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            return LuaValue.valueOf(encode(arg));
        }
    }
}
