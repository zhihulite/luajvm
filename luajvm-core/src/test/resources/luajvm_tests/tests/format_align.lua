-- tests/format_align.lua —— string.format 对齐 Lua 5.5.1 (lua55-debug 基线) 的差分用例
-- 期望值全部来自 lua55-debug 实测。
-- 覆盖：%q 严格类型路由、次正规 %q/%a、%.0d、%u 修饰符、精度路径符号位、inf/nan 宽度、
-- %#g/#%x 零值、half-even 舍入、%s 精度 0、%a 修饰符（ltests 基线报错）、错误优先序、
-- 错误消息 #N、%c 掩码、conv 缺失尾字符、多精度点。
local fails = 0
local function eq(fmt, val, want)
  local ok, got = pcall(string.format, fmt, val)
  if ok ~= false then
    if got ~= want then
      fails = fails + 1
      print(("FAIL fmt=%s got=%q want=%q"):format(fmt, got, want))
    end
  else
    fails = fails + 1
    print(("FAIL fmt=%s raised %q want=%q"):format(fmt, tostring(got), want))
  end
end
local function errContains(fmt, args, wantSub)
  local ok, got = pcall(string.format, fmt, table.unpack(args))
  if ok then
    fails = fails + 1
    print(("FAIL-NOERR fmt=%s got=%q want-sub=%q"):format(fmt, got, wantSub))
  elseif not string.find(got, wantSub, 1, true) then
    fails = fails + 1
    print(("FAIL-MSG fmt=%s got=%q want-sub=%q"):format(fmt, got, wantSub))
  end
end

-- ── %q 严格类型路由（C 按严格 lua_type 分派，字符串不做数字强转）──
eq("%q", "5", '"5"')
eq("%q", "5.5", '"5.5"')
eq("%q", 5, "5")
eq("%q", 5.5, "0x1.6p+2")
-- ── %q/%a 次正规与最小正规边界（quotefloat 语义）──
eq("%q", 2 ^ -1022, "0x1p-1022")
eq("%q", 5e-324, "0x1p-1074")
eq("%a", 5e-324, "0x1p-1074")
eq("%a", 2 ^ -1022, "0x1p-1022")
eq("%a", 1.5, "0x1.8p+0")
eq("%q", 1.5, "0x1.8p+0")
-- ── %.0d / %5.d（precision 0：值为 0 输出空，非 0 出数字；宽度仍生效）──
eq("%.0d", 0, "")
eq("%.0d", 5, "5")
eq("%5.d", 5, "    5")
eq("%5.0x", 0, "     ")
eq("%5.0u", 0, "     ")
-- ── %u 修饰符（SB 路径，不得整体丢弃）──
eq("%10u", 5, "         5")
eq("%-8.5u", 5, "00005   ")
eq("%u", 5, "5")
-- ── 整数精度路径的 '+'/' ' 符号位（符号在零填充之后）──
eq("%+.5d", 5, "+00005")
eq("% .5d", 5, " 00005")
eq("%.5d", -5, "-00005")
-- ── inf/nan 的宽度与符号（C sprintf 缩写形态）──
eq("%10f", 1 / 0, "       inf")
eq("%+e", 1 / 0, "+inf")
eq("% f", -(1 / 0), "-inf")
eq("%3g", 0 / 0, "nan")
eq("%E", 1 / 0, "INF")
-- ── 舍入模式：精确半值按 half-even（C printf 语义）──
eq("%.1f", 0.25, "0.2")
eq("%.1f", 0.35, "0.3") -- 0.35 的最近表示 > 0.35，两侧一致进位
eq("%.0f", 2.5, "2")
eq("%.0f", 3.5, "4")
eq("%.2e", 1.125, "1.12e+00")
-- ── %#g 保留尾零（Java Formatter 直接抛异常）──
eq("%#g", 1.0, "1.00000")
eq("%g", 1.0, "1")
-- ── %#x 零值不加 0x 前缀（C printf）──
eq("%#x", 0, "0")
eq("%#.5x", 0, "00000")
eq("%#x", 255, "0xff")
-- ── %s 精度 0：宽度生效、内嵌零仍报错 ──
eq("%5.0s", "abc", "     ")
errContains("%5.0s", { "a\0b" }, "string contains zeros")
errContains("%.0s", { "a\0b" }, "string contains zeros")
eq("%.2s", "abcdef", "ab")
eq("%5.2s", "abcdef", "   ab")
-- ── %a 修饰符：lua55-debug 基线（ltests #undef lua_number2strx）报错 ──
errContains("%10a", { 1.5 }, "modifiers for format %a/%A not implemented")
errContains("%#a", { 1.5 }, "modifiers for format %a/%A not implemented")
-- ── %c 掩码到 unsigned char（sprintf 语义）──
do
  local s = string.format(("x"):rep(50) .. "%c", 256)
  if s:byte(51) ~= 0 then
    fails = fails + 1
    print(("FAIL %%c 256 mask: byte=%d want=0"):format(s:byte(51)))
  end
end
-- ── 错误优先序（C：no value 最先；数值转换先类型检查后 spec 校验；s 先查内嵌零）──
errContains("%t", {}, "no value")
errContains("%", {}, "no value")
errContains("%+x", { "nope" }, "number expected")
errContains("%05s", { "a\0b" }, "string contains zeros")
-- ── %q 修饰符专用消息（先于 spec 校验可达）──
errContains("%-8q", { 1 }, "specifier '%q' cannot have modifiers")
errContains("%5q", { 1 }, "specifier '%q' cannot have modifiers")
-- ── 错误消息含 #N ──
errContains("%5s", { "a\0b" }, "#2")
errContains("%q", { {} }, "#2")
-- ── conv 缺失的尾字符（C form 含 conv 字符、末尾无 '?'）──
errContains("%12345678901234567890", { 1 }, "invalid conversion '%12345678901234567890'")
if not ("%12345678901234567890"):find("?", 18, true) == nil then end
-- ── 多精度点规格 ──
errContains("%.5.5d", { 1 }, "invalid conversion specification")

if fails > 0 then error(fails .. " format_align failures") end
return "format_align OK"
