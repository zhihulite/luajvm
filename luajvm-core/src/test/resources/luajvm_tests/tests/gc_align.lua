-- tests/gc_align.lua —— lgc/lstate/ldebug 对齐差分（期望值 lua55-debug 实测）
local fails = 0
local function chk(name, cond, detail)
  if not cond then
    fails = fails + 1
    print("FAIL " .. name .. (detail and (": " .. tostring(detail)) or ""))
  end
end
local function errSub(name, f, wantSub)
  local ok, e = pcall(f)
  chk(name, not ok and string.find(tostring(e), wantSub, 1, true) ~= nil, tostring(e))
end

-- ── B1 traceback 尾调用标记 ──
do
  local function g() error("boom") end
  local function f() return g() end  -- 尾调用
  local ok, msg = xpcall(f, debug.traceback)
  chk("traceback-tailcall-marker", msg ~= nil and msg:find("(...tail calls...)", 1, true) ~= nil, tostring(msg))
end
-- ── B2 fullGC 相序：finalizer 期间新建并存活的表不被同周期回收 ──
do
  local KEEP = {}
  local mt = {__gc = function()
    KEEP[1] = setmetatable({}, {__gc = function() KEEP.mark = true end})
  end}
  setmetatable({}, mt)
  mt = nil
  collectgarbage()
  collectgarbage()
  chk("fullgc-finalizer-order", type(KEEP[1]) == "table" and KEEP[1] ~= nil, tostring(KEEP[1]))
  KEEP = nil
end
-- ── B3+B4 屏障：增量步进中段黑表插白键/长串值不丢 ──
do
  local t = {}
  -- 推进到传播相位附近再插入新键（黑表概率路径），多轮压力
  for round = 1, 60 do
    t[("k%d"):format(round)] = ("x"):rep(64 + round)  -- 长串值
    t[setmetatable({}, {})] = true                      -- 表键
    collectgarbage("step", 3)
  end
  collectgarbage()
  collectgarbage()
  local n = 0
  for k, v in pairs(t) do n = n + 1; if v == nil then n = -100 end end
  chk("barrier-key-lstr", n >= 120, "n=" .. n)
end
-- ── B5 sethook mask==0 关 hook ──
do
  debug.sethook(function() end, "")
  local a, b, c = debug.gethook()
  chk("sethook-empty-mask", a == nil and b == nil, tostring(a) .. "/" .. tostring(b) .. "/" .. tostring(c))
  debug.sethook()
end
-- ── B6 协程继承 hook ──
do
  local calls = 0
  debug.sethook(function(ev) calls = calls + 1 end, "c")
  local co = coroutine.create(function() local x = 1 end)
  coroutine.resume(co)
  debug.sethook()
  chk("coroutine-hook-inherit", calls > 0, "calls=" .. calls)
end
-- ── B7 __gc 期间禁 hook（只统计 finalizer 体内的行钩子；主 chunk 自身行不计数）──
do
  local inF = false
  local hookedInF = 0
  debug.sethook(function() if inF then hookedInF = hookedInF + 1 end end, "l")
  local t = setmetatable({}, {__gc = function() inF = true; local a = 1; local b = 2; inF = false end})
  t = nil
  collectgarbage()
  collectgarbage()
  debug.sethook()
  chk("gc-finalizer-no-hook", hookedInF == 0, "hookedInF=" .. hookedInF)
end
-- ── B8 registry 预置槽 ──
chk("registry-slot1", debug.getregistry()[1] == false, tostring(debug.getregistry()[1]))
chk("registry-slot3", type(debug.getregistry()[3]) == "thread", type(debug.getregistry()[3]))

if fails > 0 then error(fails .. " gc_align failures") end
return "gc_align OK"
