package org.luajvm.android.lib;

import org.luajvm.android.api.CallLuaFunction;
import org.luajvm.core.LuaValue;

import java.util.Map;

/**
 * 异步 HTTP。
 * Http.get(url, cb)
 */
public class Http {

    // ==================== 请求头 ====================

    public static Map<String, String> getDefaultHeaders() {
        return HttpCore.getDefaultHeaders();
    }

    public static void setDefaultHeaders(Map<String, String> headers) {
        HttpCore.setDefaultHeaders(headers);
    }

    // ==================== GET ====================

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void get(String url, LuaValue callback) {
        HttpCore.get(url).async(callback);
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void get(String url, Map<String, String> headers, LuaValue callback) {
        HttpCore.get(url, headers).async(callback);
    }

    // ==================== HEAD ====================

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void head(String url, LuaValue callback) {
        HttpCore.head(url).async(callback);
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void head(String url, Map<String, String> headers, LuaValue callback) {
        HttpCore.head(url, headers).async(callback);
    }

    // ==================== POST ====================

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void post(String url, LuaValue body, LuaValue callback) {
        HttpCore.post(url, HttpCore.luaBody(body)).async(callback);
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void post(String url, LuaValue body, Map<String, String> headers, LuaValue callback) {
        HttpCore.post(url, HttpCore.luaBody(body), headers).async(callback);
    }

    // ==================== PUT ====================

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void put(String url, LuaValue body, LuaValue callback) {
        HttpCore.put(url, HttpCore.luaBody(body)).async(callback);
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void put(String url, LuaValue body, Map<String, String> headers, LuaValue callback) {
        HttpCore.put(url, HttpCore.luaBody(body), headers).async(callback);
    }

    // ==================== DELETE ====================

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void delete(String url, LuaValue callback) {
        HttpCore.delete(url).async(callback);
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void delete(String url, Map<String, String> headers, LuaValue callback) {
        HttpCore.delete(url, headers).async(callback);
    }

    // ==================== 上传 ====================

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void upload(String url, Map<String, String> data, Map<String, String> files, LuaValue callback) {
        HttpCore.upload(url, data, files).async(callback);
    }

    @CallLuaFunction(value = CallLuaFunction.Thread.MAIN,
            note = "回调经 LuaScheduler.runOnIo 完成后 post 主线程（HttpCore.HttpTask.async）")
    public static void upload(String url, Map<String, String> data, Map<String, String> files, Map<String, String> headers, LuaValue callback) {
        HttpCore.upload(url, data, files, headers).async(callback);
    }
}
