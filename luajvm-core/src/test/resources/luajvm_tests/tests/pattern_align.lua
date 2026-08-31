-- tests/pattern_align.lua —— lstrlib pattern/format 外对齐差分（lua55-debug 实测期望）
local fails = 0
local function chk(name, got, want)
  if got ~= want then
    fails = fails + 1
    print(("FAIL %s: got=%q want=%q"):format(name, tostring(got), tostring(want)))
  end
end
local function errSub(name, f, wantSub)
  local ok, e = pcall(f)
  if ok or not string.find(tostring(e), wantSub, 1, true) then
    fails = fails + 1
    print(("FAIL %s: got=%q want-sub=%q"):format(name, tostring(ok and e or e), wantSub))
  end
end

-- ── %1 反向引用位置捕获 ──
chk("find-poscap-backref", tostring(select(2, pcall(string.find, "abcdef", "()%1"))), "nil")
do
  local ok, r, n = pcall(string.gsub, "abcdef", "()%1", "X")
  -- C：模式本身匹配失败（位置捕获不可反引），整个 gsub 不替换
  chk("gsub-poscap-backref", ok and (r .. "/" .. n) or "err", "abcdef/0")
end
-- ── 未闭合捕获 ──
errSub("gsub-str-unfin", function() return (string.gsub("xa", "a(", "%1")) end, "unfinished capture")
errSub("gsub-fn-unfin", function() return (string.gsub("xa", "a(", function() return "Y" end)) end, "unfinished capture")
errSub("gmatch-unfin", function() local it = string.gmatch("xa", "a(") return it() end, "unfinished capture")
-- ── 替换串尾部孤立 % ──
errSub("trailing-pct", function() return (string.gsub("a", "a", "x%")) end,
    "invalid use of '%' in replacement string")
-- ── boolean 替换值 ──
errSub("fn-true-repl", function() return (string.gsub("a", "a", function() return true end)) end,
    "invalid replacement value (a boolean)")
errSub("tbl-true-repl", function() return (string.gsub("a", "a", { a = true })) end,
    "invalid replacement value (a boolean)")
chk("fn-false-keep", select(2, pcall(string.gsub, "a", "a", function() return false end)), "a")
-- ── 数字替换 ──
chk("numeric-repl", select(2, pcall(string.gsub, "a", "a", 1)), "1")
chk("numeric-repl-f", select(2, pcall(string.gsub, "a", "a", 2.5)), "2.5")
-- ── gsub 参数顺序：arg4 先报 ──
errSub("gsub-arg4-first", function() return (string.gsub("a", "a", nil, "x")) end,
    "number expected, got string")
-- ── init/max_s 64 位 ──
chk("find-init-huge", tostring(select(2, pcall(string.find, "abc", "b", 2 ^ 31))), "nil")
chk("find-init-huge2", tostring(select(2, pcall(string.find, "abc", "b", 3e9))), "nil")
-- tostring(select(2,...)) 拿到的是第一个返回值 2；C 同样返回 2（负巨值回落到串首匹配）
chk("find-init-neghuge", tostring(select(2, pcall(string.find, "abc", "b", -9e18))), "2")
do
  local it = string.gmatch("abc", "%a", 8589934592)
  local n = 0
  while it() do n = n + 1 end
  chk("gmatch-init-huge", n, 0)
end
chk("gsub-max-huge", select(3, pcall(string.gsub, "aaaa", "a", "b", 2 ^ 33)), 4)
-- gmatch init 类型校验
errSub("gmatch-init-bool", function() return string.gmatch("abc", "%a", true) end,
    "number expected, got boolean")
-- ── byte/char 浮点与消息 ──
errSub("byte-float", function() return string.byte("abc", 1.5) end,
    "has no integer representation")
errSub("char-float", function() return string.char(65.5) end,
    "has no integer representation")
errSub("char-range-msg", function() return string.char(256) end,
    "bad argument #1 to 'char' (value out of range)")
chk("char-int", string.char(65), "A")
chk("byte-ok", string.byte("abc", 2), 98)
-- ── upper/lower "C" locale（仅 ASCII）──
chk("upper-0xFF", string.rep("\255", 1):upper():byte(), 255)
chk("lower-0xC0", ("\192"):lower():byte(), 192)
chk("upper-0xE0", ("\224"):upper():byte(), 224)
chk("upper-0xB5", ("\181"):upper():byte(), 181)
chk("upper-ascii", ("aBc"):upper(), "ABC")
chk("lower-ascii", ("AbC"):lower(), "abc")
-- ── 浮点 subject ──
chk("upper-float", string.upper(65.0), "65.0")
do
  local a, b = string.find(1.5, ".5")
  chk("find-float-subj", a .. " " .. b, "2 3")
end
-- ── dump ──
chk("dump-strip-str", #select(2, pcall(string.dump, load("return 1"), "x")) > 0
    and #select(2, pcall(string.dump, load("return 1"), "x"))
        < #select(2, pcall(string.dump, load("return 1"))), true)
errSub("dump-nonfunc", function() return string.dump(1) end,
    "bad argument #1 to 'dump' (Lua function expected)")
-- ── 算术元方法动词（mtname+2）──
errSub("arith-verb", function() return ("a") - {} end, "attempt to sub a 'string' with a 'table'")
errSub("unm-verb", function() return -("a") end, "attempt to unm a 'string'")
-- ── %f 消息 ──
errSub("fmsg", function() return string.find("abc", "%f") end, "missing '[' after '%f' in pattern")

if fails > 0 then error(fails .. " pattern_align failures") end
return "pattern_align OK"
