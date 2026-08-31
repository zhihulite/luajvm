-- C：gc.lua : weak tables
-- 弱值不应在一次完整收集后继续作为可枚举的表值。
local t = setmetatable({}, {__mode = "v"})
for i = 1, 20 do
  t[i] = {}
end

collectgarbage("collect")
assert(next(t) == nil, "弱值在完整收集后仍保留")
