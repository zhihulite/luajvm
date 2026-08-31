package org.luajvm.android.lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;

/**
 * json 编解码合约，跑在设备上。
 *
 * <p><b>为什么必须是仪器化测试</b>：{@code org.json} 在桌面 JVM 上是 Maven 那份
 * （{@code org.json:json}），在 Android 上是系统自带那份，两者行为不同 ——
 * 最典型的是 {@code opt()} 对 JSON null 返回 {@link JSONObject#NULL} 哨兵而不是
 * Java {@code null}。在 JVM 上测这个类等于在测一个生产环境不存在的实现。
 */
@RunWith(AndroidJUnit4.class)
public class JsonInstrumentedTest {

    // ==================== encode 形态判定 ====================

    @Test
    public void stringKeyTableEncodesAsObject() throws Exception {
        LuaTable table = new LuaTable();
        table.set("dailyId", LuaValue.valueOf(9791943));
        table.set("type", LuaValue.valueOf("daily"));

        String encoded = json.encode(table);
        JSONObject object = new JSONObject(encoded);

        assertFalse(encoded.startsWith("["));
        assertEquals(9791943, object.getInt("dailyId"));
        assertEquals("daily", object.getString("type"));
    }

    @Test
    public void contiguousIntegerTableEncodesAsArray() throws Exception {
        LuaTable table = new LuaTable();
        table.set(1, LuaValue.valueOf("first"));
        table.set(2, LuaValue.valueOf("second"));

        String encoded = json.encode(table);
        JSONArray array = new JSONArray(encoded);

        assertTrue(encoded.startsWith("["));
        assertEquals(2, array.length());
        assertEquals("first", array.getString(0));
        assertEquals("second", array.getString(1));
    }

    @Test
    public void mixedKeyTableEncodesAsObject() throws Exception {
        LuaTable table = new LuaTable();
        table.set(1, LuaValue.valueOf("first"));
        table.set("type", LuaValue.valueOf("daily"));

        String encoded = json.encode(table);
        JSONObject object = new JSONObject(encoded);

        assertFalse(encoded.startsWith("["));
        assertEquals("first", object.getString("1"));
        assertEquals("daily", object.getString("type"));
    }

    @Test
    public void sparseIntegerTableEncodesAsObject() throws Exception {
        LuaTable table = new LuaTable();
        table.set(1, LuaValue.valueOf("first"));
        table.set(3, LuaValue.valueOf("third"));

        String encoded = json.encode(table);
        JSONObject object = new JSONObject(encoded);

        assertFalse(encoded.startsWith("["));
        assertEquals("first", object.getString("1"));
        assertEquals("third", object.getString("3"));
    }

    // ==================== 非法数值：两条路径须一致 ====================

    /**
     * 纯数字字符串必须编码成 JSON 字符串而不是数值：LuaString.isnumber() 对数字串
     * 为真，判定顺序错了会取基类 todouble()=0，把 "2077505556449965534" 这样的
     * ID 字符串静默编成 0（路由参数经 Storage 落盘后 ID 全变 0）。
     */
    @Test
    public void numericStringEncodesAsString() throws Exception {
        LuaTable table = new LuaTable();
        table.set("answerId", LuaValue.valueOf("2077505556449965534"));

        String encoded = json.encode(table);
        JSONObject object = new JSONObject(encoded);

        assertEquals("数字字符串须保持字符串身份",
                "2077505556449965534", object.getString("answerId"));
    }

    /** 对象路径的 NaN：JSONObject.put 拒收，须包成 LuaError 上抛而非静默丢键。 */
    @Test
    public void nanInObjectPathRaisesLuaError() {
        LuaTable table = new LuaTable();
        table.set("ratio", LuaValue.valueOf(Double.NaN));
        try {
            String encoded = json.encode(table);
            fail("NaN 不是合法 JSON 数值，encode 应抛 LuaError 而不是产出 " + encoded);
        } catch (LuaError expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * 数组路径的 NaN：JSONArray.put(Object) 不校验，非法值要等到 toString() 才失败
     * 并让 encode 返回 null。须与对象路径同款、同一处抛出。
     */
    @Test
    public void nanInArrayPathRaisesLuaErrorToo() {
        LuaTable table = new LuaTable();
        table.set(1, LuaValue.valueOf(1.0));
        table.set(2, LuaValue.valueOf(Double.POSITIVE_INFINITY));
        try {
            String encoded = json.encode(table);
            fail("数组里的 Infinity 同样非法，encode 应抛 LuaError 而不是产出 " + encoded);
        } catch (LuaError expected) {
            assertNotNull(expected.getMessage());
        }
    }

    // ==================== decode ====================

    /** BOM 与前导空白不得让 startsWith("[") 把数组误判成对象。 */
    @Test
    public void decodeStripsBomAndLeadingWhitespace() {
        LuaValue v = json.decode("﻿  [1,2,3]");
        assertTrue("带 BOM 的数组应解成表", v.istable());
        assertEquals(3, v.get(3).toint());
    }

    /**
     * JSON null 的落点：Android 的 org.json 用 {@link JSONObject#NULL} 哨兵，
     * 桌面版行为不同 —— 这条只有在设备上才有意义。
     */
    @Test
    public void jsonNullMapsToLuaNil() {
        LuaValue v = json.decode("{\"a\":null,\"b\":1}");
        assertTrue("对象应解成表", v.istable());
        assertEquals("非 null 键应正常", 1, v.get("b").toint());
        assertTrue("JSON null 应落成 Lua nil，而不是 JSONObject.NULL 的 userdata 包装",
                v.get("a").isnil());
    }

    /** 空串/纯空白/非 JSON 输入统一走 LuaError，不得静默返回半个表。 */
    @Test
    public void malformedInputRaisesLuaError() {
        for (String bad : new String[]{"", "   ", "not json", "{unclosed"}) {
            try {
                json.decode(bad);
                fail("非法输入应抛 LuaError: [" + bad + "]");
            } catch (LuaError expected) {
                // 预期
            }
        }
    }
}
