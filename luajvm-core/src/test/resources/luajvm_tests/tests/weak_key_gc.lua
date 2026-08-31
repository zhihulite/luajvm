-- C：gc.lua : weak tables
-- 与官方测试相同：可回收表键、整数键和字符串键必须同时正确处理。
-- 先推进完整增量周期，保留官方测试在弱表前已有的颜色状态。
repeat
until collectgarbage("step", 1)

local lim = 15
-- 与官方弱表段相邻的清表序列会反复完成收集周期。
local clearing = {}
for i = 1, lim do
  clearing[{}] = i
end
local keep = {}
for k, v in pairs(clearing) do
  keep[k] = v
end
for k in pairs(keep) do
  clearing[k] = nil
  collectgarbage()
end
keep = nil
collectgarbage()

weak_key_gc_regression = setmetatable({}, {__mode = "k"})
local t = weak_key_gc_regression
for i = 1, lim do
  t[{}] = i
end
for i = 1, lim do
  t[i] = i
end
for i = 1, lim do
  local s = string.rep("@", i)
  t[s] = s .. "#"
end

collectgarbage()
local n = 0
for k, v in pairs(t) do
  assert(k == v or k .. "#" == v, "弱键在完整收集后仍保留")
  n = n + 1
end
assert(n == 2 * lim, "弱键表的剩余条目数量错误")
weak_key_gc_regression = nil
