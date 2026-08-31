-- error_align.lua —— 全标准库错误消息对齐门禁（80 项）
--
-- 覆盖六类易错形态（裸 check 系列无参数号/函数名包装；StringFormat 手写
--   to 'format' 无 where；rep 的 sep / find 的 init 漏检；os.difftime 不查参；
--   insert 的 pos 裸检查；ipairs(非表) 在 C 5.5 合法而 Java 急切报错），
--   防止错误消息与 C 的对齐悄悄回退。
-- 期望值取自 lua55-debug 实测输出，双端必须 0 失败。归一化只剥 src:line: 前缀。

local fails = 0
local function chk(tag, got, want)
  if got ~= want then
    fails = fails + 1
    print(("FAIL %s: got=[%s] want=[%s]"):format(tag, tostring(got), tostring(want)))
  end
end
local function E(f, ...)
  local ok, e = pcall(f, ...)
  if ok then e = "<ok>" end
  return tostring(e):match(":%d+: (.*)$") or tostring(e)
end

local S, T, M, U = string, table, math, utf8

chk("rawlen_num", E(rawlen, 42), [==[bad argument #1 to 'rawlen' (table or string expected, got number)]==])
chk("rawlen_str_ok", E(rawlen, "abc"), [==[<ok>]==])
chk("setmeta_protected", E(setmetatable, {}, setmetatable({}, {__metatable="lock"})), [==[<ok>]==])
chk("ipairs_nontable", E(ipairs, 5), [==[<ok>]==])
chk("select_neg", E(select, -5, "a","b"), [==[bad argument #1 to 'select' (index out of range)]==])
chk("select_zero", E(select, 0), [==[bad argument #1 to 'select' (index out of range)]==])
chk("select_nonint", E(select, "x"), [==[bad argument #1 to 'select' (number expected, got string)]==])
chk("assert_false", E(assert, false), [==[assertion failed!]==])
chk("assert_noarg", E(assert), [==[bad argument #1 to 'assert' (value expected)]==])
chk("tonumber_badbase", E(tonumber, "1", 1), [==[bad argument #2 to 'tonumber' (base out of range)]==])
chk("load_badmode", E(load, "return 1", "=c", "z"), [==[<ok>]==])
chk("error_level0", E(function() error("msg", 0) end), [==[msg]==])
chk("rep_neg", E(S.rep, "a", -1), [==[<ok>]==])
chk("sub_floatidx", E(S.sub, "abc", 1.5), [==[bad argument #2 to 'string.sub' (number has no integer representation)]==])
chk("byte_oob", E(S.byte, "abc", 10), [==[<ok>]==])
chk("char_range", E(S.char, -1), [==[bad argument #1 to 'string.char' (value out of range)]==])
chk("char_huge", E(S.char, 2^53), [==[bad argument #1 to 'string.char' (value out of range)]==])
chk("format_pct", E(S.format, "%"), [==[bad argument #2 to 'string.format' (no value)]==])
chk("format_d_str", E(S.format, "%d", "x"), [==[bad argument #2 to 'string.format' (number expected, got string)]==])
chk("format_missing", E(S.format, "%d"), [==[bad argument #2 to 'string.format' (no value)]==])
chk("format_s_num", E(S.format, "%s", 3), [==[<ok>]==])
chk("format_x_str", E(S.format, "%x", "q"), [==[bad argument #2 to 'string.format' (number expected, got string)]==])
chk("format_badverb", E(S.format, "%y", 1), [==[invalid conversion '%y' to 'format']==])
chk("find_pattype", E(S.find, "abc", {}), [==[bad argument #2 to 'string.find' (string expected, got table)]==])
chk("find_init_type", E(S.find, "abc", "a", "x"), [==[bad argument #3 to 'string.find' (number expected, got string)]==])
chk("gsub_repl_bad", E(S.gsub, "abc", "a", {}), [==[<ok>]==])
chk("gsub_badpat", E(S.gsub, "abc", "[", "y"), [==[malformed pattern (missing ']')]==])
chk("match_badinit", E(S.match, "abc", ".", {}), [==[bad argument #3 to 'string.match' (number expected, got table)]==])
chk("len_type", E(S.len, 42), [==[<ok>]==])
chk("reverse_type", E(S.reverse, {}), [==[bad argument #1 to 'string.reverse' (string expected, got table)]==])
chk("upper_nil", E(S.upper, nil), [==[bad argument #1 to 'string.upper' (string expected, got nil)]==])
chk("insert_1ok", E(T.insert, {}, 1), [==[<ok>]==])
chk("insert_0", E(T.insert), [==[bad argument #1 to 'table.insert' (table expected, got no value)]==])
chk("insert_1extra", E(T.insert, {}, 1, 2, 3), [==[wrong number of arguments to 'insert']==])
chk("remove_oob", E(T.remove, {}, 5), [==[bad argument #2 to 'table.remove' (position out of bounds)]==])
chk("concat_hole", E(T.concat, {1, nil, 3}), [==[<ok>]==])
chk("concat_nonstr", E(T.concat, {1, {}}, ","), [==[invalid value (table) at index 2 in table for 'concat']==])
chk("concat_sep_type", E(T.concat, {1,2}, {}), [==[bad argument #2 to 'table.concat' (string expected, got table)]==])
chk("unpack_nonseq", E(T.unpack, {[2]="b"}), [==[<ok>]==])
-- 多行调用正则截断，手工展开（C 不检出比较器返回值错误，双端一致）
do
  local ok = pcall(table.sort, {3,1,2}, function(a,b) return "x" end)
  chk("sort_badcmp_ret", ok, true)
end
chk("sort_cmp_err", E(T.sort, {1,2}, function() error("cmpfail") end), [==[cmpfail]==])
chk("floor_bigfloat", E(M.floor, 2^70), [==[<ok>]==])
chk("ceil_nan", E(M.ceil, 0/0), [==[<ok>]==])
chk("max_mixed", E(M.max, 1, "x"), [==[attempt to compare number with string]==])
chk("tointeger_str", E(M.tointeger, "x"), [==[<ok>]==])
chk("random_bad", E(M.random, 5, 1), [==[bad argument #1 to 'math.random' (interval is empty)]==])
chk("random_type", E(M.random, "x"), [==[bad argument #1 to 'math.random' (number expected, got string)]==])
chk("date_badfmt", E(os.date, "%Q"), [==[bad argument #1 to 'os.date' (invalid conversion specifier '%Q')]==])
chk("io_open_missing", E(io.open, "__nope__.lua"), [==[<ok>]==])
chk("io_read_bad", E((function() return io.read("zz") end)), [==[bad argument #1 to 'read' (invalid format)]==])
do
  local co = coroutine.create(function() end)
  coroutine.resume(co)
  local ok = pcall(function() return select(2, coroutine.resume(co)) end)
  chk("resume_dead", ok, true)
end
do
  local co = coroutine.create(function() coroutine.yield() end)
  coroutine.resume(co)
  local ok = pcall(coroutine.close, co)
  chk("close_normal", ok, true)
end
chk("yield_main", E(function() coroutine.yield() end), [==[attempt to yield from outside a coroutine]==])
chk("codes_off_float", E(U.codes, "abc", 1.5), [==[<ok>]==])
chk("charcode_range", E(U.charcodepoint, 2^31), [==[attempt to call a nil value]==])
chk("offset_badidx", E(U.offset, "abc", 99), [==[<ok>]==])
chk("len_over", E(U.len, "abc", 99), [==[bad argument #2 to 'utf8.len' (initial position out of bounds)]==])

chk("rep_sep_type", E(S.rep, "a", 2, {}), [==[bad argument #3 to 'string.rep' (string expected, got table)]==])
chk("byte_neg", E(S.byte, "abc", -1), [==[<ok>]==])
chk("char_floatfrac", E(S.char, 1.5), [==[bad argument #1 to 'string.char' (number has no integer representation)]==])
chk("format_q_fn", E(S.format, "%q", print), [==[bad argument #2 to 'string.format' (value has no literal form)]==])
chk("format_s_table", E(S.format, "%s", {}), [==[<ok>]==])
chk("format_10d_str", E(S.format, "%10d", "x"), [==[bad argument #2 to 'string.format' (number expected, got string)]==])
chk("gsub_maxneg", E(S.gsub, "aaa", "a", "b", -1), [==[<ok>]==])
chk("gmatch_pattype", E(S.gmatch, "abc", {}), [==[bad argument #2 to 'string.gmatch' (string expected, got table)]==])
chk("find_plain_num", E(S.find, "abc", "b", 1), [==[<ok>]==])
chk("insert_pos_float", E(T.insert, {1,2}, 1.5, 9), [==[bad argument #2 to 'table.insert' (number has no integer representation)]==])
chk("remove_on_empty_ret", E((function() return (T.remove({})) end)), [==[<ok>]==])
chk("move_oob", E(T.move, {1,2,3}, 0, 5, 1), [==[<ok>]==])
chk("concat_i_type", E(T.concat, {1,2}, ",", 1, "x"), [==[bad argument #4 to 'table.concat' (number expected, got string)]==])
chk("random_two_bad2", E(M.random, 1, "x"), [==[bad argument #2 to 'math.random' (number expected, got string)]==])
chk("random_zero", E(M.random, 0), [==[<ok>]==])
chk("floor_int_ok", E(M.floor, 5), [==[<ok>]==])
chk("codepoint_range", E(U.codepoint, "\228\184\173", 1, -1), [==[<ok>]==])
chk("len_badi", E(U.len, "abc", 2, 1), [==[<ok>]==])
chk("offset_nonint", E(U.offset, "abc", "x"), [==[bad argument #2 to 'utf8.offset' (number expected, got string)]==])
chk("char_outofrange", E(U.char, 0x7FFFFFFF), [==[<ok>]==])
chk("time_badtable", E(os.time, {hour=25}), [==[field 'year' missing in date table]==])
chk("difftime_type", E(os.difftime, {}, 1), [==[bad argument #1 to 'os.difftime' (number expected, got table)]==])
do
  local co = coroutine.wrap(function() string.gsub("abc", ".", function() coroutine.yield(1) end) end)
  local ok1, e1 = pcall(co)
  -- 第一次 resume 即抛（yield 跨 C 边界），第二次对错误对象再 resume 无意义；
  -- C 实测：错误消息在第一次就出来
  local ok, e = ok1, e1
  chk("yield_in_gsub", tostring(e):match(":%d+: (.*)$") or tostring(e),
      "attempt to yield across a C-call boundary")
end

if fails > 0 then error(fails .. " error_align failures") end
return "error_align OK"
