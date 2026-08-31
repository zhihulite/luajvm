-- luajvm_tests/04_collection.lua
-- Java 集合互操作：数组/List/Map 索引、#、泛型 for、astable 浅/深转换
local ArrayList = luajava.bindClass("java.util.ArrayList")
local list = luajava.newInstance("java.util.ArrayList")
list.add(10); list.add(20); list.add(30)
-- astable 转换 List → Lua 式访问
local t = luajava.astable(list)
assert(#t == 3, "list #")
assert(t[1] == 10 and t[3] == 30, "list index")
-- pairs（PairsFn 支持 __pairs 元方法；Lua 5.5 泛型 for 对非函数值直接当迭代器调用是 C 行为）
local n = 0
for i, v in pairs(t) do n = n + 1 end
assert(n == 3, "pairs count")
-- 数组（newArray）索引/写入/#（Java 数组从 0 开始）
local arr = luajava.newArray(luajava.bindClass("java.lang.Integer").TYPE, 3)
arr[0] = 7
arr[1] = 8
assert(arr[0] == 7 and arr[1] == 8, "array write/read")
assert(#arr == 3, "array #")
-- Map 索引
local HashMap = luajava.bindClass("java.util.HashMap")
local map = luajava.newInstance("java.util.HashMap")
map.put("k", "v")
local mt = luajava.astable(map)
assert(mt["k"] == "v", "map index")
print("04_collection OK")
