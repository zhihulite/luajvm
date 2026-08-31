-- flatloop_equiv.lua —— FlatLoop 通道的行为等价门禁（java-only）
--
-- 现有门禁对 FlatLoop 启停零判别力 —— flatShapeTests 在 ON/OFF 两态输出逐字相同
--   （它守的是"预扩容不改变形状"这一保真性质，两路本就该一致），删错或改坏
--   FlatLoop 不会被任何门禁发现。本文件补上那个缺口：覆盖 FlatLoop 宣称支持的
--   全部通道，断言【具体数值】，任何一条通道的语义分叉都会现形。用法两种：
--     1. 常驻：作为 luajvm_tests 的一员，防 FlatLoop 相关改动引入回归；
--     2. 删除 FlatLoop 时：`-Dluajvm.flatloop=false` 与默认态各跑一次，逐字比对。
--
-- 期望值全部由 C（lua55-debug）实测取得，故本文件在 C 与 Java 两侧都必须 0 失败。

local fails = 0
local function chk(tag, got, want)
  if got ~= want then
    fails = fails + 1
    print(("FAIL %s: got=%s want=%s"):format(tag, tostring(got), tostring(want)))
  end
end
local function emsg(f)
  local ok, e = pcall(f)
  if ok then return "ok" end
  return (tostring(e):gsub(".*:%d+: ", ""))
end

-- 1) 数值 for 整数循环（基础通道）
do
  local s = 0
  for i = 1, 10000 do s = s + i * 2 - 1 end
  chk("flat-int-for", s, 100000000)
end

-- 2) 浮点循环（analyzeFloat / runFloat；默认关闭，仍须语义一致）
do
  local s = 0.0
  for i = 1, 1000 do s = s + i / 3 end
  chk("flat-float", ("%.6f"):format(s), "166833.333333")
end

-- 3) 数组段读（表寄存器不变量 + metatable==null guard）
do
  local t = {}
  for i = 1, 256 do t[i] = i * i end
  local s = 0
  for i = 1, 256 do s = s + t[i] end
  chk("flat-arr-get", s, 5625216)
end

-- 4) 数组段写 + 全新空表预扩容（fillTableReg 路径；形状由 T.querytab 把关）
do
  local t = {}
  for i = 1, 64 do t[i] = i end
  chk("flat-fill-len", #t, 64)
  if T then
    local a, h = T.querytab(t)
    chk("flat-fill-shape", a .. "/" .. h, "64/0")
  end
end

-- 5) 读改写槽复用融合（SYN_ARR_GET_CACHED + SYN_ARR_SET_CACHED，fusedU）
do
  local t = {}
  for i = 1, 128 do t[i] = i end
  for i = 1, 128 do t[i] = t[i] + 1 end
  chk("flat-rmw-first", t[1], 2)
  chk("flat-rmw-last", t[128], 129)
end

-- 6) 计数器键追加（fillCounterReg：local n=0 ... n=n+1; t[n]=v）
do
  local t, n = {}, 0
  for i = 1, 100 do n = n + 1; t[n] = i * 3 end
  chk("flat-counter-val", t[100], 300)
  if T then
    local a, h = T.querytab(t)
    chk("flat-counter-shape", a .. "/" .. h, "128/0")
  end
end

-- 7) 常量串键字段读写提升（SYN_FIELD_GET / SYN_FIELD_SET，Node 身份提到环前）
do
  local o = { x = 0, y = 1 }
  for i = 1, 1000 do o.x = o.x + o.y end
  chk("flat-field", o.x, 1000)
end

-- 8) 嵌套表读（SYN_NESTED_IGET）
do
  local m = { { 1, 2 }, { 3, 4 } }
  local s = 0
  for i = 1, 1000 do s = s + m[1][1] + m[2][2] end
  chk("flat-nested", s, 5000)
end

-- 9) 叶子调用内联（SYN_LEAF_CALL）
do
  local function leaf(a, b) return a * 2 + b end
  local s = 0
  for i = 1, 1000 do s = s + leaf(i, 1) end
  chk("flat-leaf-call", s, 1002000)
end

-- 10) 整数自递归扁平化（runRec 通道）
do
  local function fib(n)
    if n < 2 then return n end
    return fib(n - 1) + fib(n - 2)
  end
  chk("flat-rec-fib", fib(20), 6765)
end

-- 11) 整数 while 循环通道
do
  local i, s = 1, 0
  while i <= 1000 do s = s + i; i = i + 1 end
  chk("flat-while", s, 500500)
end

-- 12) generic-for 的 TFORCALL 内联（INextFn，须 guard metatable==null）
do
  local t = { 10, 20, 30 }
  local s = 0
  for _, v in ipairs(t) do s = s + v end
  chk("flat-ipairs", s, 60)
end

-- 13) 长度源寄存器（lenRegs：#t 作循环不变量）
do
  local t = {}
  for i = 1, 50 do t[i] = i end
  local s = 0
  for i = 1, #t do s = s + #t end
  chk("flat-len-src", s, 2500)
end

-- 14) 分支体（branchy 通道：JMP/EQI/LTI 等）
do
  local s = 0
  for i = 1, 1000 do
    if i % 3 == 0 then s = s + i else s = s - 1 end
  end
  chk("flat-branch", s, 166166)
end

-- 15) 内建函数内联（flatIntOp：math.max/min 等）
do
  local s = 0
  for i = 1, 1000 do s = s + math.max(i, 500) end
  chk("flat-builtin-max", s, 625250)
end

-- 16) 错误边界：bailout 路径必须产出与装箱路径同样的错误
chk("flat-err-nil-index",
    emsg(function() local t = nil; for i = 1, 10 do local _ = t[i] end end),
    "attempt to index a nil value (local 't')")
chk("flat-err-div-zero",
    emsg(function() for i = 1, 10 do local _ = i // 0 end end),
    "attempt to divide by zero")
-- 浮点除零不报错（inf），须与装箱一致
chk("flat-err-float-div",
    emsg(function() local t = {}; for i = 1, 10 do t[i] = i / 0 end; return t[1] end),
    "ok")

-- 17) ipairs 扁平化（IpairsMark 通道）：密集数组求和
do
  local t = {}
  for i = 1, 200 do t[i] = i end
  local s = 0
  for _, v in ipairs(t) do s = s + v end
  chk("flat-ipairs-dense", s, 20100)
end

-- 18) body 读索引 i（needsKey 路径：sum i*i*i for 1..50 = (50*51/2)^2）
do
  local t = {}
  for i = 1, 50 do t[i] = i * i end
  local s = 0
  for i, v in ipairs(t) do s = s + i * v end
  chk("flat-ipairs-keyuse", s, 1625625)
end

-- 19) 中途 bail：非整数值触发交回装箱，续跑无重复交付
do
  local t = { 1, 2, "x", 4 }
  local n, s = 0, 0
  for _, v in ipairs(t) do
    n = n + 1
    if type(v) == "number" then s = s + v end
  end
  chk("flat-ipairs-mixed-count", n, 4)
  chk("flat-ipairs-mixed-sum", s, 7)
end

-- 20) 稀疏表空洞即停（{[1]=1,[3]=3} 只交付键 1）
do
  local t = { [1] = 1, [3] = 3 }
  local acc = {}
  for i, v in ipairs(t) do acc[#acc + 1] = i .. "=" .. v end
  chk("flat-ipairs-hole", table.concat(acc, ","), "1=1")
end

-- 21) 非整数值表：逐元素 bail 回装箱，行为与纯装箱一致
do
  local t = { "a", "b" }
  local n = 0
  for _ in ipairs(t) do n = n + 1 end
  chk("flat-ipairs-strs", n, 2)
end

-- 22) body 除零经 bail 续跑由装箱路径抛 Lua 错误（消息逐字对齐 C），
--     不得以裸 ArithmeticException 穿透 pcall
do
  local t1 = { [1] = 5 }
  local ok, err = pcall(function()
    local s = 0
    for _, v in pairs(t1) do s = v % 0 end
  end)
  chk("tfor-mod-zero-ok", tostring(ok), "false")
  chk("tfor-mod-zero-msg", err:match("attempt to perform 'n%%0'$") ~= nil, true)
end
do
  local t2 = { [1] = 7, [2] = 0 }
  local ok, err = pcall(function()
    local s = 0
    for _, v in pairs(t2) do s = s + 100 % v end
  end)
  chk("tfor-mod-reg-zero-ok", tostring(ok), "false")
  chk("tfor-mod-reg-zero-msg", err:match("attempt to perform 'n%%0'$") ~= nil, true)
end
do
  local t3 = { [1] = 5 }
  local ok, err = pcall(function()
    local s = 0
    for _, v in pairs(t3) do s = v // 0 end
  end)
  chk("tfor-div-zero-ok", tostring(ok), "false")
  chk("tfor-div-zero-msg", err:match("divide by zero$") ~= nil, true)
end

if fails > 0 then error(fails .. " flatloop_equiv failures") end
return "flatloop_equiv OK"