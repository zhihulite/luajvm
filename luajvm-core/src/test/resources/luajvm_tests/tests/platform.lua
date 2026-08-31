-- luajvm_tests/01_platform.lua
-- luajava 平台：全局 luajava 表、require("luajava")、Java 互操作基础
-- java-only: JVM 测试由 LuajvmTestRunner 用 Platform.standardGlobals() 装配（含 luajava）

-- 全局 luajava 表存在且是表
assert(type(luajava) == "table", "luajava global missing")
-- require("luajava") 返回同一模块
local m = require("luajava")
assert(m ~= nil, "require luajava failed")
-- 基本类型别名（JVM 环境未注册全局别名，探测不硬断言）
local booleanType = luajava.bindClass("java.lang.Boolean").TYPE
assert(booleanType ~= nil, "boolean.TYPE missing")
local byteType = luajava.bindClass("java.lang.Byte").TYPE
assert(byteType ~= nil, "byte.TYPE missing")
print("01_platform OK")
