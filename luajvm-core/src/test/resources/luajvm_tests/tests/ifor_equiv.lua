-- ifor_equiv.lua —— FlatIFor（数值 for 扁平化）行为等价门禁（java-only）
--
-- 断言具体数值与错误消息，期望值由 C（lua55-debug）实测取得；
-- -Dluajvm.ifor=false 与默认态各跑一次，两侧都必须 0 失败。
-- 覆盖：正/负步长、零迭代、大步长无符号边界、body 内位运算、
--       除零 bail 续跑（'n%%0' / 'divide by zero' 消息逐字）、控制变量 const。

local fails = 0
local function eq(actual, expected, tag)
  if actual ~= expected then
    fails = fails + 1
    print(("FAIL %s: got %s, want %s"):format(tag, tostring(actual), tostring(expected)))
  end
end

-- 正步长收集
do
  local t = {}
  for i = 1, 5 do t[#t + 1] = i end
  eq(#t, 5, "pos-len")
  eq(table.concat(t, ','), '1,2,3,4,5', "pos-seq")
end

-- 负步长
do
  local acc = {}
  for i = 10, 1, -3 do acc[#acc + 1] = i end
  eq(table.concat(acc, ' '), '10 7 4 1', "neg-step")
end

-- 零迭代
do
  local n = 0
  for i = 1, 0 do n = n + 1 end
  eq(n, 0, "zero-iter")
end

-- 大步长无符号边界（mininteger 起点）
do
  local n = 0
  for i = math.mininteger, math.maxinteger, math.maxinteger do n = n + 1 end
  eq(n, 3, "big-step")
end

-- 纯计算体（扁平化主形态）：取模累加；含负操作数（floor 符号调整分支）
do
  local s = 0
  for i = 1, 1000000 do s = s + i % 7 end
  eq(s, 2999998, "mod-accum")
  local n1, n2, q1, q2 = 0, 0, 0, 0
  for i = 1, 100000 do
    n1 = n1 + (-i) % 7
    n2 = n2 + i % -7
    q1 = q1 + (-i) // 7
    q2 = q2 + i // -7
  end
  eq(n1, 300005, "mod-neg-dividend")
  eq(n2, -300005, "mod-neg-divisor")
  eq(q1, -714335715, "idiv-neg-dividend")
  eq(q2, -714335715, "idiv-neg-divisor")
end

-- body 内位运算
do
  local s = 0
  for i = 1, 500000 do if i & 15 == 0 then s = s + 1 end end
  -- 注意：含比较分支的形态当前会被分析器拒绝走装箱，结果必须一致
  eq(s, 31250, "band-skip")
end

-- 浮点 limit（floor 语义，装箱路径）
do
  local r = 0
  for i = 1, 3.0 do r = r + i end
  eq(r, 6, "float-limit")
end

-- 除零：bail 续跑后由装箱路径抛错，消息逐字对齐 C
do
  local ok, err = pcall(function()
    local z = 0
    for i = 5, 10 do local q = i % z end
  end)
  eq(ok, false, "mod-zero-ok")
  eq(err:match("attempt to perform 'n%%0'$") ~= nil, true, "mod-zero-msg")
end
do
  local ok, err = pcall(function()
    for i = 1, 3 do local q = i // 0 end
  end)
  eq(ok, false, "div-zero-ok")
  eq(err:match("divide by zero$") ~= nil, true, "div-zero-msg")
end

-- step 为零报错
do
  local ok, err = pcall(function() for i = 1, 10, 0 do end end)
  eq(ok, false, "step-zero-ok")
  eq(err:match("'for' step is zero$") ~= nil, true, "step-zero-msg")
end

-- 比较分支体：EQI/GTI 跳转、JMP 目标重定位（MMBIN 剥离后下标压缩）、双臂 if-else
do
  local function b1(n)
    local s = 0
    for i = 1, n do
      local a = i % 320
      local b = (i * 2) % 480
      if i & 15 == 0 then s = s + a * b end
      s = s + a + b
    end
    return s
  end
  local function b2(n)
    local s = 0
    for i = 1, n do
      local a = i % 97
      if a > 48 then s = s + a else s = s - a end
    end
    return s
  end
  eq(b1(100000), 258871280, "branch-eqi-fused-tail")
  eq(b2(100000), 2374863, "branch-gti-ifelse")
end

if fails > 0 then error("ifor_equiv: " .. fails .. " failure(s)") end
print("ifor_equiv OK")
