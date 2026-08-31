-- tests/traceback_align.lua —— lauxlib.c luaL_traceback / ldblib.c db_traceback 对齐差分
-- 期望值全部在 lua55-debug 上实测：本文件可直接用 C Lua 运行，必须 0 失败。
-- 断言只用与宿主无关的量：起始若干帧的文本，以及由 debug.getinfo 现测层数推出的截断结构
-- （C 的独立解释器底部多一个 "[C]: in ?" 帧，绝对帧数不可跨宿主比较）。
local fails = 0
local function chk(n, got, want)
  if got ~= want then
    fails = fails + 1
    print(("FAIL %s: %s vs %s"):format(n, tostring(got), tostring(want)))
  end
end

-- 拆行（保留空行：msg 前缀的换行语义要用到）
local function split(s)
  local out, pos = {}, 1
  while true do
    local nl = s:find("\n", pos, true)
    if not nl then out[#out + 1] = s:sub(pos); return out end
    out[#out + 1] = s:sub(pos, nl - 1)
    pos = nl + 1
  end
end

-- 解析 traceback：返回 skip 行之前的帧、之后的帧、skip 数、尾调用标记数
local function parse(tb)
  local lead, trail, skipped, tails = {}, {}, nil, 0
  local cur, seen = lead, false
  for _, line in ipairs(split(tb)) do
    if line == "stack traceback:" then
      seen = true
    elseif seen then
      local body = line:match("^\t(.*)$")
      if body then
        local n = body:match("^%.%.%.\t%(skipping (%d+) levels%)$")
        if n then
          skipped = tonumber(n); cur = trail
        elseif body == "(...tail calls...)" then
          tails = tails + 1
        else
          cur[#cur + 1] = body
        end
      end
    end
  end
  return lead, trail, skipped, tails
end

-- ── A. message handler 期间的帧链（handler 帧 + 抛错点 C 帧都在链上）──
do
  local function inner() error("boom") end
  local function outer() inner() end
  local tb = select(2, xpcall(outer, function(m) return debug.traceback(m, 1) end))
  local lead = parse(tb)
  chk("A1-msg-prefix", tb:match("^[^\n]*boom\n") ~= nil, true)
  chk("A2-handler-frame", (lead[1] or ""):match(": in function <") ~= nil, true)
  chk("A3-error-c-frame", lead[2], "[C]: in global 'error'")
  chk("A4-thrower-frame", (lead[3] or ""):match(": in upvalue 'inner'$") ~= nil, true)
  chk("A5-caller-frame", (lead[4] or ""):match(": in function <") ~= nil, true)
  chk("A6-xpcall-frame", lead[5], "[C]: in global 'xpcall'")
end

-- level=0 ⇒ 第一帧是 debug.traceback 自己的 C 帧
do
  local function inner() error("boom0") end
  local tb = select(2, xpcall(inner, function(m) return debug.traceback(m, 0) end))
  local lead = parse(tb)
  chk("B1-level0-self", lead[1], "[C]: in field 'traceback'")
  chk("B2-level0-handler", (lead[2] or ""):match(": in function <") ~= nil, true)
  chk("B3-level0-error", lead[3], "[C]: in global 'error'")
end

-- level=2 ⇒ 跳过 traceback 自身与 handler
do
  local function inner() error("boom2") end
  local tb = select(2, xpcall(inner, function(m) return debug.traceback(m, 2) end))
  local lead = parse(tb)
  chk("C1-level2", lead[1], "[C]: in global 'error'")
end

-- handler 就是 debug.traceback 本身（默认 level=1 ⇒ 跳过它自己）
do
  local function inner() error("boomh") end
  local tb = select(2, xpcall(inner, debug.traceback))
  local lead = parse(tb)
  chk("D1-tb-as-handler", lead[1], "[C]: in global 'error'")
end

-- ── E. 无错误路径的默认 level=1 ──
do
  local function plainCaller() return debug.traceback("plain") end
  local lead = parse(plainCaller())
  chk("E1-default-level", (lead[1] or ""):match(": in %a+ 'plainCaller'$") ~= nil, true)
  local function plainCaller0() return debug.traceback("plain0", 0) end
  local lead0 = parse(plainCaller0())
  chk("E2-level0", lead0[1], "[C]: in field 'traceback'")
end

-- ── F. 显式线程参数：L == L1 时默认 level 仍是 1（ldblib.c db_traceback）──
do
  local function selfThread() return debug.traceback(coroutine.running(), "st") end
  local lead = parse(selfThread())
  chk("F1-running-thread-default", (lead[1] or ""):match(": in %a+ 'selfThread'$") ~= nil, true)
end

-- ── G. 协程 target：挂起时默认 level=0，死亡（出错）时保留出错帧链 ──
do
  local co = coroutine.create(function() coroutine.yield() end)
  coroutine.resume(co)
  local lead = parse(debug.traceback(co, "susp"))
  chk("G1-susp-default-level0", lead[1], "[C]: in field 'yield'")
  local lead1 = parse(debug.traceback(co, "susp", 1))
  chk("G2-susp-level1", (lead1[1] or ""):match(": in function <") ~= nil, true)

  local dead = coroutine.create(function() error("co-err") end)
  coroutine.resume(dead)
  local leadd = parse(debug.traceback(dead, "dead"))
  chk("G3-dead-error-frame", leadd[1], "[C]: in global 'error'")
  chk("G4-dead-body-frame", (leadd[2] or ""):match(": in function <") ~= nil, true)
end

-- ── H. level 越界：负数与过大都只输出表头 ──
do
  local function neg() return debug.traceback("neg", -1) end
  local lead = parse(neg())
  chk("H1-negative-level", #lead, 0)
  local function big() return debug.traceback("big", 1000) end
  chk("H2-huge-level", #parse(big()), 0)
end

-- ── I. msg 语义（ldblib.c db_traceback + lauxlib.c luaL_traceback）──
do
  local function numMsg() return debug.traceback(42) end
  local ls = split(numMsg())
  chk("I1-number-msg", ls[1], "42")
  chk("I2-number-msg-head", ls[2], "stack traceback:")

  local function emptyMsg() return debug.traceback("") end
  local le = split(emptyMsg())
  chk("I3-empty-msg-blank-line", le[1], "")
  chk("I4-empty-msg-head", le[2], "stack traceback:")

  local function nilMsg() return debug.traceback(nil) end
  chk("I5-nil-msg-head", split(nilMsg())[1], "stack traceback:")

  local t = {}
  local function tblMsg() return debug.traceback(t) end
  chk("I6-table-msg-untouched", tblMsg(), t)
end

-- ── J. pushfuncname 顺序：无 code 名时先试 package.loaded 里的名字 ──
function TB_AlignGlobalFn() error("gerr") end
do
  local tb = select(2, xpcall(TB_AlignGlobalFn, function(m) return debug.traceback(m, 2) end))
  local lead = parse(tb)
  chk("J1-global-name", lead[1], "[C]: in global 'error'")
  chk("J2-global-name-fn", (lead[2] or ""):match(": in function 'TB_AlignGlobalFn'$") ~= nil, true)
end
do
  local M = {}
  function M.inner() error("merr") end
  package.loaded["tb_align_mod"] = M
  local tb = select(2, xpcall(M.inner, function(m) return debug.traceback(m, 2) end))
  local lead = parse(tb)
  chk("J3-module-field-name",
      (lead[2] or ""):match(": in function 'tb_align_mod%.inner'$") ~= nil, true)
  package.loaded["tb_align_mod"] = nil
end
do
  local anon = function() error("aerr") end
  local tb = select(2, xpcall(anon, function(m) return debug.traceback(m, 2) end))
  local lead = parse(tb)
  chk("J4-anon-falls-back", (lead[2] or ""):match(": in function <") ~= nil, true)
end

-- ── K. hook 帧命名（ldebug.c funcnamefromcall ⇒ namewhat="hook", name="?"）──
do
  local captured
  local function target() local x = 1; return x end
  debug.sethook(function()
    debug.sethook()
    captured = debug.traceback("in-hook", 1)
  end, "", 3)
  target()
  debug.sethook()
  local lead = parse(captured or "")
  chk("K1-hook-frame", (lead[1] or ""):match(": in hook '%?'$") ~= nil, true)
end

-- ── L. 尾调用标记 ──
do
  local function t3() error("tc") end
  local function t2() return t3() end
  local tb = select(2, xpcall(t2, function(m) return debug.traceback(m, 2) end))
  local _, _, _, tails = parse(tb)
  chk("L1-tailcall-marker", tails >= 1, true)
end

-- ── M. 截断算法（lauxlib.c luaL_traceback 的 limit2show/lastlevel/n 公式，含被丢弃的那一帧）──
local function lastlevel()
  local li, le = 1, 1
  while debug.getinfo(le, "") do li = le; le = le * 2 end
  while li < le do
    local m = (li + le) // 2
    if debug.getinfo(m, "") then li = m + 1 else le = m end
  end
  return le - 1
end

local function depth(n, level)
  if n > 0 then
    -- 先赋值再 return：保证是非尾调用（尾调用会折叠帧，撑不出层数）
    local a, b, c, d = depth(n - 1, level)
    return a, b, c, d
  end
  local last = lastlevel() - 1  -- lastlevel 自身比 traceback 多一帧
  local lead, trail, skipped = parse(debug.traceback("t", level))
  return last, #lead, skipped, #trail
end

for _, spec in ipairs{{5, 1}, {12, 1}, {18, 1}, {19, 1}, {20, 1}, {24, 1}, {12, 0}, {20, 0}} do
  local n, level = spec[1], spec[2]
  local last, lead, skipped, trail = depth(n, level)
  local tag = ("M-n%d-l%d"):format(n, level)
  if last - level > 21 then
    chk(tag .. "-lead", lead, 10)
    chk(tag .. "-trail", trail, 11)
    chk(tag .. "-skip", skipped, last - level - 21)
  else
    chk(tag .. "-full", lead, last - level + 1)
    chk(tag .. "-noskip", skipped, nil)
  end
end

if fails > 0 then error(fails .. " traceback_align failures") end
return "traceback_align OK"

