-- tests/compiler_align.lua —— llex/lparser/lcode 对齐差分（期望值 lua55-debug 实测，
-- 行号断言与本文行号绑定：增删行须同步核对 chk 期望值）
local fails = 0
local function chk(name, got, want)
  if got ~= want then
    fails = fails + 1
    print(("FAIL %s: got=%s want=%s"):format(name, tostring(got), tostring(want)))
  end
end

-- ── 空十六进制拒绝（C: malformed number near '0x'）──
do
  local f, e = load("return 0x")
  chk("hex-empty-load", f, nil)
  chk("hex-empty-msg", e ~= nil and e:find("malformed number near '0x'", 1, true) ~= nil, true)
  local f2, e2 = load("return 0X")
  chk("hex-empty-upper", f2, nil)
end
-- ── 十六进制浮点次正规区 ──
chk("hexflt-subnormal", load("return 0x1.8p-1075 == 5e-324")(), true)
chk("hexflt-minnormal", load("return 0x1p-1022 == 2^-1022")(), true)
chk("hexflt-normal", load("return 0x1.8p+0 == 1.5")(), true)
-- ── global none：none 是普通全局名 ──
chk("global-none-decl", load("global none; none = 42; return none")(), 42)
chk("global-none-strict", select(1, load("global none; x = 1")) ~= nil, false)  -- x 未声明仍报错
-- ── CALL 行号 = 参数 token 行 ──
do
  local f
  local ok, e = pcall(function() return f
"arg"() end)
  -- C：错误在参数行（本文件第 30 行附近的 "arg" 行）
  chk("call-line-pos", ok == false and e:find(':%d+:', 1) ~= nil, true)
  local line = e:match(":(%d+):")
  chk("call-line-is-arg-line", tonumber(line), 30)
end
-- ── 长串键不再进 K 操作数（dump 互操作）：Java 自身语义仍正确 ──
do
  local long = ("k"):rep(50)
  local t = {}
  t[long] = 1
  chk("longkey-value", t[long], 1)
  local r = load("local long = ('k'):rep(50) local t = {} t[long] = 1 return t[long]")()
  chk("longkey-roundtrip", r, 1)
  -- 长名方法调用（OP_SELF 的 key 若长串须走寄存器路径，Java 执行面正确）
  local obj = setmetatable({}, {__index = function(_, k)
    if k == ("m"):rep(50) then return function(self) return "called" end end
  end})
  chk("longmethod-call", obj[("m"):rep(50)](), "called")
  -- dump 往返仍可用（Java load Java dump）
  local fn = assert(load("local long = ('k'):rep(50) local t = {} t[long] = 7 return t[long]"))
  chk("longkey-dump-reload", (load(string.dump(fn), long and "b" or "b"))(), 7)
end
-- ── 短串键常规路径回归 ──
chk("shortkey", ("hello"):upper(), "HELLO")
chk("method-normal", ("a"):rep(2):upper(), "AA")

-- ── LOW 项：消息与边界 ──
do
  local f, e = load("if x 3 then end")
  chk("then-quoted", f == nil and e:find("'then' expected near '3'", 1, true) ~= nil, true)
  local f2, e2 = load("function f(a, 3) end")
  chk("parlist-msg", f2 == nil and e2:find("<name> or '...' expected", 1, true) ~= nil, true)
  -- 深嵌套：C 报 C stack overflow（lua55-debug 实测同文）
  local f3, e3 = load(string.rep("(", 300) .. ")")
  chk("deep-msg", f3 == nil and e3:find("C stack overflow", 1, true) ~= nil, true)
end

-- ── undump 错误消息形态（lundump.c error/numerror：'%s: bad binary format (%s)'）──
-- 期望全部 lua55-debug 实测
do
  local d = string.dump(load("return 1+1"))
  local _, e1 = load(d:sub(1, 8))
  chk("undump-truncated", e1, "binary string: bad binary format (truncated chunk)")
  local _, e2 = load(d:sub(1, 4) .. "" .. d:sub(6))
  chk("undump-version", e2, "binary string: bad binary format (version mismatch)")
  local _, e3 = load(d:sub(1, 8), "=mychunk")
  chk("undump-name-eq", e3, "mychunk: bad binary format (truncated chunk)")
  local _, e4 = load(d:sub(1, 8), "@myfile.lua")
  chk("undump-name-at", e4, "myfile.lua: bad binary format (truncated chunk)")
  local _, e5 = load(d:sub(1, 8), "plainname")
  chk("undump-name-plain", e5, "plainname: bad binary format (truncated chunk)")
  local _, e6 = load(string.char(27) .. "Luaxxxxxxxxxxxx")
  chk("undump-badsig", e6, "binary string: bad binary format (version mismatch)")
end

if fails > 0 then error(fails .. " compiler_align failures") end
return "compiler_align OK"
