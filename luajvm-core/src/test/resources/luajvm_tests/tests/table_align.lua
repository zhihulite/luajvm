-- tests/table_align.lua —— ltable/lobject 对齐差分（期望值全部 lua55-debug 实测）
local fails = 0
local function chk(name, got, want)
  if got ~= want then
    fails = fails + 1
    print(("FAIL %s: got=%s want=%s"):format(name, tostring(got), tostring(want)))
  end
end
local function errSub(name, f, wantSub)
  local ok, e = pcall(f)
  if ok or not string.find(tostring(e), wantSub, 1, true) then
    fails = fails + 1
    print(("FAIL %s: %s"):format(name, tostring(e)))
  end
end

-- ── 浮点 tostring（lobject.c tostringbuffFloat：%.15g → round-trip → %.17g → 补 .0）──
chk("f-1/3", tostring(1 / 3), "0.33333333333333331")
chk("f-2^63", tostring(2 ^ 63), "9.2233720368547758e+18")
chk("f-1e-300", tostring(1e-300), "1e-300")
chk("f-neg0", tostring(-0.0), "-0.0")
chk("f-0.1", tostring(0.1), "0.1")
chk("f-pi", tostring(math.pi), "3.1415926535897931")
chk("f-1e15", tostring(1e15), "1e+15")
chk("f-2^53", tostring(2 ^ 53), "9007199254740992.0")
chk("f--2^53", tostring(-(2 ^ 53)), "-9007199254740992.0")
chk("f-1/7", tostring(1 / 7), "0.14285714285714285")
chk("f-5e-324", tostring(5e-324), "4.94065645841247e-324")
chk("f-1e308", tostring(1e308), "1e+308")
chk("f-1.5", tostring(1.5), "1.5")
chk("f-100", tostring(100.0), "100.0")
chk("f-0", tostring(0.0), "0.0")
-- ── string.format %.17g / %.Ne（printf 精确值语义）──
chk("g17-1e-300", string.format("%.17g", 1e-300), "1e-300")
chk("g17-neg0", string.format("%.17g", -0.0), "-0")
chk("g17-1/3", string.format("%.17g", 1 / 3), "0.33333333333333331")
chk("g17-0.1", string.format("%.17g", 0.1), "0.10000000000000001")
chk("e6-1e-300", string.format("%.6e", 1e-300), "1.000000e-300")
chk("e6-neg", string.format("%.2e", -1.25e-10), "-1.25e-10")
chk("e0-2", string.format("%.0e", 2.0), "2e+00")
chk("g6-1.0", string.format("%g", 1.0), "1")
chk("g-hash", string.format("%#g", 1.0), "1.00000")
-- ── table index 错误带位置 ──
errSub("idx-nil-pos", function() local t = {} t[nil] = 1 end, "table index is nil")
do
  local ok, e = pcall(function() local t = {} t[nil] = 1 end)
  chk("idx-nil-chunkline", ok == false and e:find(":%d+:", 1) ~= nil, true)
end
do
  local ok, e = pcall(function() local t = {} t[0 / 0] = 1 end)
  chk("idx-nan-chunkline", ok == false and e:find(":%d+:", 1) ~= nil, true)
end
-- ── 墓碑复用（删光再插的功能面：正确性 + 无限增长防护）──
do
  local t = {}
  for i = 1, 50 do t["k" .. i] = i end
  for i = 1, 50 do t["k" .. i] = nil end
  for i = 1, 40 do t[i] = i end
  local n, sum = 0, 0
  for k, v in pairs(t) do n = n + 1; sum = sum + v end
  chk("tombstone-reuse-count", n, 40)
  chk("tombstone-reuse-sum", sum, 820)
  for i = 1, 40 do
    if t[i] ~= i then chk("tombstone-read-" .. i, t[i], i); break end
  end
end
-- ── 链保持：墓碑复用不得断碰撞链 ──
do
  local t = {}
  local sentinel = {}
  t[1.5] = "a"; t[2.5] = "b"; t[3.5] = "c"
  t[1.5] = nil  -- 建墓碑
  t[2.5] = "B"  -- 若 2.5 与 1.5 同桶，可能复用墓碑位置
  chk("chain-1.5-gone", t[1.5], nil)
  chk("chain-2.5", t[2.5], "B")
  chk("chain-3.5", t[3.5], "c")
end

if fails > 0 then error(fails .. " table_align failures") end
return "table_align OK"
