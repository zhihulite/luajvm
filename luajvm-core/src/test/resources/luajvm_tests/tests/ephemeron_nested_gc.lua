-- C：gc.lua : ephemerons（嵌套弱键表）
-- 回归点：convergeephemerons 每轮必须只摘链表头（C: g->ephemeron = NULL），
-- 若清掉链上每个 gclist，本轮只会处理到第一个表，跨层链在中途断裂。
local mt = {__mode = 'k'}
local a = setmetatable({}, mt)

local K = {}
a[K] = {}
for i = 1, 10 do
  a[K][i] = {}
  a[a[K][i]] = setmetatable({}, mt)
end

local x = nil
local k = 1
for _ = 1, 100 do
  local n = {}
  local nk = k % 10 + 1
  a[a[K][nk]][n] = {x, k = k}
  x = n
  k = nk
end

collectgarbage("collect")
collectgarbage("collect")

local n = x
local i = 0
while n do
  local mid = a[K][k]
  assert(mid ~= nil, "a[K][" .. k .. "] 被误清，i=" .. i)
  local inner = a[mid]
  assert(inner ~= nil, "内层弱表被误清，i=" .. i)
  local t = inner[n]
  assert(t ~= nil, "嵌套 ephemeron 链在 i=" .. i .. " 处断裂")
  n = t[1]
  k = t.k
  i = i + 1
end
assert(i == 100, "链长应为 100，实得 " .. i)
