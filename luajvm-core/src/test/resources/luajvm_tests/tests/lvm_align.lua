-- tests/lvm_align.lua —— lvm.c 对齐差分用例（期望值来自 lua55-debug 实测）
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

-- ── 字符串位运算：vanilla 5.5.1 无强转（报错），元方法可达 ──
errSub("band-string-no-coerce", function() return ("3") & 1 end,
    "attempt to perform bitwise operation on a string value")
do
  local mt = getmetatable("")
  mt.__band = function(a, b) return 99 end
  chk("band-metatable-reachable", ("3") & 1 == 99, ("3") & 1)
  mt.__band = nil
end
-- 数字 & 字符串元方法（bwcoercion 形态）：装上元方法后 "0xF0" | "0xAA" 走元方法可计算
do
  local mt = getmetatable("")
  local function toint(s) return math.tointeger(tonumber(s)) end
  mt.__band = function(a, b) return toint(a) & toint(b) end
  chk("band-string-metamath", ("0xF0") & ("0x3C") == 0x30, ("0xF0") & ("0x3C"))
  mt.__band = nil
end

-- ── rawequal 数值交叉（luaV_equalobj(NULL,..) 语义）──
chk("rawequal-int-float-eq", rawequal(1, 1.0))
chk("rawequal-float-int-eq", rawequal(1.0, 1))
chk("rawequal-int-float-ne", not rawequal(1, 1.5))
chk("rawequal-table-raw", not rawequal({}, {}))
chk("rawequal-string", rawequal("a", "a"))

-- ── forprep：浮点分支按 limit/step/init 顺序报错；step-zero 带位置 ──
errSub("for-order-limit-first", function()
  for i = 1, {}, {} do end
end, "bad 'for' limit (number expected, got table)")
errSub("for-step-zero-pos", function()
  for i = 1, 10, 0 do end
end, " 'for' step is zero")
errSub("for-step-zero-float-pos", function()
  for i = 1.0, 10.0, 0.0 do end
end, "'for' step is zero")

-- ── OP_TBC / OP_TFORPREP：non-closable 错误带位置与变量名 ──
errSub("tbc-position-and-name", function()
  local x <close> = {}
end, "variable 'x' got a non-closable value")
do
  local ok, e = pcall(function()
    local x <close> = {}
  end)
  chk("tbc-has-chunkline", ok == false and e:find(":%d+:", 1) ~= nil, tostring(e))
  local ok2, e2 = pcall(function()
    for i = 1, 10, 0 do end
  end)
  chk("stepzero-has-chunkline", ok2 == false and e2:find(":%d+:", 1) ~= nil, tostring(e2))
end
-- C 实测（lua55-debug）：间接迭代器（__call/函数调用）返回的非可关闭闭合值不报错
-- （TFORPREP 只对直接上栈的第 4 值查 close 元方法）；与 LuaVM OP_TFORPREP 行为一致。
do
  local t = setmetatable({}, {__call = function() return 1, nil, nil, {} end})
  local ok, e = pcall(function() for i in t do break end end)
  chk("tforprep-indirect-nocheck", ok, tostring(e))
end
-- 数字不可关闭（OP_TBC 主路径）：报错带位置与变量名
errSub("tbc-nonclosable-number", function()
  local x <close> = 5
end, "variable 'x' got a non-closable value")
do
  local ok, e = pcall(function() local x <close> = 5 end)
  chk("tbc-has-chunkline2", ok == false and e:find(":%d+:", 1) ~= nil, tostring(e))
end

if fails > 0 then error(fails .. " lvm_align failures") end
return "lvm_align OK"
