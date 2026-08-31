-- luajvm_tests/05_api.lua
-- luajava API：new/newInstance/instanceof/clear/newArray/loadLib；
-- createProxy/override：JVM 无 LuaJavaContext SPI 时应报错（记录预期）
local sb = luajava.newInstance("java.lang.StringBuilder")
assert(luajava.instanceof(sb, luajava.bindClass("java.lang.StringBuilder")), "instanceof")
-- clear 释放引用，返回 nil 不抛错
assert(luajava.clear(sb) == nil, "clear")
-- newArray
local byteArr = luajava.newArray(luajava.bindClass("java.lang.Byte").TYPE, 8)
assert(#byteArr == 8, "newArray size")
-- createProxy/override：JVM 无 LuaJavaContext SPI 时应报错（记录预期，不断言具体文本）
local okProxy, errProxy = pcall(function()
  luajava.createProxy({luajava.bindClass("java.lang.Runnable")}, function() end)
end)
print("createProxy (JVM, no SPI): ok=" .. tostring(okProxy) .. " err=" .. tostring(errProxy))
print("05_api OK")
