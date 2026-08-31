-- C：lgc.c : singlestep 的 GCScallfin 判据是 g->tobefnz（待调用集），
-- 不是 g->finobj（带 __gc 的对象登记表）。判错会让分配驱动的 __gc 永不触发，
-- 官方 gc.lua 的 GC1/GC2 驱动（repeat u = {} until finish）因此无限循环。
-- 本用例有界：超过上限即失败，不会挂死。
local n = 0
local finish = false
local u = setmetatable({}, {__gc = function () finish = true end})
repeat
  u = {}
  n = n + 1
  assert(n <= 200000, "20 万次分配后分配驱动的 __gc 仍未触发")
until finish

-- 弱表中的字符串键：C 的 isempty(v) 对 LUA_VEMPTY 与 LUA_VNIL 皆真，
-- 空条目必须 clearkey，否则字符串键每轮被 iscleared 标黑而永不回收。
local w = setmetatable({}, {__mode = "kv"})
local long = string.rep("z", 1 << 16)
w[long] = 1
collectgarbage("collect")
assert(w[long] == 1, "字符串键不应被弱表清除")
w[long] = nil
long = nil
collectgarbage("collect")
assert(next(w) == nil, "置 nil 后条目应为空")
