-- tests/do_align.lua —— ldo/lcorolib/lauxlib 对齐差分（期望值 lua55-debug 实测）
local fails = 0
local function chk(n, got, want)
  if got ~= want then fails = fails + 1; print(("FAIL %s: %s vs %s"):format(n, tostring(got), tostring(want))) end
end
-- resume normal 协程消息（ldo.c lua_resume）
do
  local co = coroutine.create(function()
    return select(2, coroutine.resume(coroutine.running()))
  end)
  local _, e = coroutine.resume(co)
  chk("resume-running-msg", e, "cannot resume non-suspended coroutine")
end
-- __tostring 返回数字（lauxlib.c luaL_tolstring）
chk("tostring-num-meta", tostring(setmetatable({}, {__tostring = function() return 42 end})) + 0, 42)
-- coroutine.close 成功返回 1 值（lcorolib.co_close）
do
  local co = coroutine.create(function() coroutine.yield() end)
  coroutine.resume(co)
  chk("close-nret", select("#", coroutine.close(co)), 1)
end
-- loadfile 错误尾巴（strerror）
do
  local f, e = loadfile("D:/nonexistent_zzz.lua")
  chk("loadfile-msg", e ~= nil and e:find("No such file or directory", 1, true) ~= nil, true)
end
-- insert 参数个数错误名（ltablib 硬编码 'insert'）
do
  local ok, e = pcall(table.insert, {})
  chk("insert-argname", e ~= nil and e:find("arguments to 'insert'", 1, true) ~= nil, true)
end
-- ── 值级 checkXXX 消息形态（lauxlib luaL_checkXXX）──
do
  local _, e = pcall(function() return string.rep("x") end)
  chk("rep-argmsg", e:find("bad argument #2 to 'rep' (number expected, got no value)", 1, true) ~= nil, true)
  local _, e2 = pcall(function() return utf8.char(1.5) end)
  chk("utf8char-intrep", e2:find("bad argument #1 to 'char' (number has no integer representation)", 1, true) ~= nil, true)
  local _, e3 = pcall(function() return string.len() end)
  chk("len-argmsg", e3:find("bad argument #1 to 'len' (string expected, got no value)", 1, true) ~= nil, true)
end

if fails > 0 then error(fails .. " do_align failures") end
return "do_align OK"
