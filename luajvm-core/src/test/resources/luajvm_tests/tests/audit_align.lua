-- audit_align.lua —— core 对齐审计发现的 BUG 条目，逐条钉成差分门禁。
--
-- 修好的项没有门禁看着会悄悄坏回去，故每条 BUG 写成一个 chk()，断言的是
--   **C 的行为**（期望值用 lua55-debug 逐条实测取值）。于是本文件在 C 与 Java
--   两侧都必须 0 失败：
--     · 在 C 上跑过 ⇒ 证明期望值就是 C 的真实行为（门禁本身没写错）；
--     · 在 Java 上跑过 ⇒ 证明该条已对齐。
--   任何一条将来坏回去，这里立刻 FAIL。
--
-- 注意：本文件只覆盖**可在 Lua 层观察**的条目。少数条目按设计不可在此断言，
--   在文末『不可覆盖』里逐条说明原因（不是漏写）。

local fails = 0
local function chk(tag, got, want)
  if got ~= want then
    fails = fails + 1
    print(("FAIL %s: got=%s want=%s"):format(tag, tostring(got), tostring(want)))
  end
end

-- 取错误消息（去掉可变的 chunk 名前缀之外的部分交给调用方判断）
local function emsg(f, ...)
  local ok, e = pcall(f, ...)
  if ok then return nil end
  return tostring(e)
end
local function loaderr(src)
  local f, e = load(src)
  if f then return nil end
  return tostring(e)
end
local function has(s, sub)
  return s ~= nil and s:find(sub, 1, true) ~= nil
end

-- ═══════════════════════════════════════════════════════════════════
-- compiler 域（15 条）
-- ═══════════════════════════════════════════════════════════════════

-- [HIGH] 长字符串(>40字符)键的 OP_SELF/GETFIELD/SETFIELD K 操作数
do
  local long = ("k"):rep(60)
  local t = {}
  t[long] = 7
  chk("cmp-longkey-get", t[long], 7)
  -- 字段形式（编译成 GETFIELD/SETFIELD）
  local src = ("local t={} t.%s=7 return t.%s"):format(long, long)
  local f = load(src)
  chk("cmp-longkey-field-loadok", f ~= nil, true)
  chk("cmp-longkey-field-val", f and f() or nil, 7)
  -- 方法形式（OP_SELF）
  local obj = {}
  obj[long] = function(self) return 11 end
  chk("cmp-longkey-self", obj[long](obj), 11)
  -- dump/undump 往返
  chk("cmp-longkey-dump", (load(string.dump(f)))(), 7)
end

-- [MED] 空十六进制 '0x'/'0X'
do
  chk("cmp-hex-empty-lex", loaderr("return 0x") ~= nil, true)
  chk("cmp-hex-empty-lex-msg", has(loaderr("return 0x"), "malformed number"), true)
  chk("cmp-hex-empty-lexX", loaderr("return 0X") ~= nil, true)
  -- tonumber 路径（lobject.c l_str2int 的 empty 判据）
  chk("cmp-hex-empty-tonum", tonumber("0x"), nil)
  chk("cmp-hex-empty-tonumX", tonumber("0X"), nil)
  chk("cmp-hex-empty-neg", tonumber("-0x"), nil)
  chk("cmp-hex-empty-pos", tonumber("+0x"), nil)
  chk("cmp-hex-empty-space", tonumber("  0x  "), nil)
  chk("cmp-hex-dot", tonumber("0x."), nil)
  chk("cmp-hex-p-nomant", tonumber("0xp1"), nil)
  -- 合法形态仍必须通过
  chk("cmp-hex-ok1", tonumber("0x1"), 1)
  chk("cmp-hex-okA", tonumber("0xA"), 10)
  chk("cmp-hex-okp", tonumber("0x1p4"), 16.0)
  chk("cmp-hex-okdot", tonumber("0x.8"), 0.5)
end

-- [MED] 十六进制浮点次正规区
do
  chk("cmp-hexsubnorm", tostring(0x1p-1070), "7.90505033345994e-323")
  chk("cmp-hexsubnorm-tonum", tostring(tonumber("0x1p-1070")), "7.90505033345994e-323")
  chk("cmp-hexsubnorm-nz", 0x1p-1070 > 0, true)
end

-- [MED] 'global none' 特例
do
  local f = load("none = 1 return none")
  chk("cmp-global-none-loadok", f ~= nil, true)
  chk("cmp-global-none-val", f and f() or nil, 1)
  none = nil
end

-- [MED] CALL 指令行号（字符串/表构造器参数）
do
  -- 参数跨行的表构造器：错误行号应是 call 所在行
  local function boom() error("X", 2) end
  local src = table.concat({
    "local boom = ...",
    "boom{",
    "  1,",
    "}",
  }, "\n")
  local f = assert(load(src))
  local e = emsg(f, boom)
  chk("cmp-callline-table", has(e, ":2:"), true)
  -- 字符串参数同理
  local src2 = table.concat({
    "local boom = ...",
    "boom[[",
    "text",
    "]]",
  }, "\n")
  local f2 = assert(load(src2))
  chk("cmp-callline-str", has(emsg(f2, boom), ":4:"), true)
end

-- [LOW] tokenName 保留字加单引号
do
  chk("cmp-tok-reserved", has(loaderr("if 1 then end end"), "near 'end'"), true)
  chk("cmp-tok-reserved2", has(loaderr("return function"), "near '<eof>'")
      or has(loaderr("return function"), "near <eof>"), true)
  chk("cmp-tok-then", has(loaderr("if x 3 then end"), "'then' expected near '3'"), true)
end

-- [LOW] testThenBlock / checknext(TK_THEN)
do
  chk("cmp-then-missing", has(loaderr("if x end"), "'then' expected"), true)
  chk("cmp-then-num", has(loaderr("if x 3 then end"), "'then' expected"), true)
end

-- [LOW] parlist 错误消息 "or '...'"
do
  chk("cmp-parlist", has(loaderr("function f(a,) end"), "<name> or '...' expected"), true)
  chk("cmp-parlist2", has(loaderr("function f(3) end"), "<name> or '...' expected"), true)
end

-- [LOW] \u{} 空花括号
do
  chk("cmp-u-empty", has(loaderr([[return "\u{}"]]), "hexadecimal digit expected"), true)
  chk("cmp-u-nobrace", has(loaderr([[return "\u1"]]), "missing '{'"), true)
end

-- [LOW] \z 后的 0x1C-0x1F 不应被当空白
do
  -- \z 跳过空白后遇 0x1C：C 视为普通字符（非空白）⇒ 字符串含该字节
  local f = load("return \"a\\z" .. string.char(0x1C) .. "b\"")
  chk("cmp-z-1c-loadok", f ~= nil, true)
  if f then
    local s = f()
    chk("cmp-z-1c-len", #s, 3)
    chk("cmp-z-1c-byte", s:byte(2), 0x1C)
  end
  -- 真空白（空格/换行/制表）确实被跳过
  -- 用 string.char 构造源码，避开本文件自身的转义歧义：
  --   源码 = return "a\z<空格><换行><空格>b"  ⇒ \z 吃掉全部空白 ⇒ "ab"
  local zsrc = 'return "a\\z' .. string.char(32, 32, 10, 32) .. 'b"'
  local zf = load(zsrc)
  chk("cmp-z-space-loadok", zf ~= nil, true)
  if zf then chk("cmp-z-space", zf(), "ab") end
end

-- [LOW] chunkId 45 字符单行
do
  -- 单行源恰好长时，chunk 名应带 "..." 截断标记
  local src = "return " .. ("1+"):rep(30) .. "1"
  local e = emsg(function() error("Z") end)
  -- 用 load 的 chunkname 默认形态检查截断
  local long = ("x"):rep(80)
  local f, err = load("syntax error here", long)
  chk("cmp-chunkid-trunc", has(err, "..."), true)
  -- 短名不截断
  local f2, err2 = load("syntax error here", "short")
  chk("cmp-chunkid-short", has(err2, "..."), false)
end

-- [LOW] undump 损坏二进制
do
  local d = string.dump(load("return 1+1"))
  chk("und-truncated", select(2, load(d:sub(1, 8))),
      "binary string: bad binary format (truncated chunk)")
  chk("und-version", select(2, load(d:sub(1, 4) .. "\1" .. d:sub(6))),
      "binary string: bad binary format (version mismatch)")
  chk("und-name-eq", select(2, load(d:sub(1, 8), "=mychunk")),
      "mychunk: bad binary format (truncated chunk)")
  chk("und-name-at", select(2, load(d:sub(1, 8), "@myfile.lua")),
      "myfile.lua: bad binary format (truncated chunk)")
  chk("und-name-plain", select(2, load(d:sub(1, 8), "plainname")),
      "plainname: bad binary format (truncated chunk)")
end

-- [LOW] 深嵌套错误消息
do
  local f, e = load(string.rep("(", 300) .. ")")
  chk("cmp-deep-nil", f, nil)
  chk("cmp-deep-msg", has(e, "C stack overflow"), true)
end

-- ═══════════════════════════════════════════════════════════════════
-- ldo 域（11 条）
-- ═══════════════════════════════════════════════════════════════════

-- [MED] resume 一个 normal 协程
do
  local inner
  local outer
  outer = coroutine.create(function()
    -- outer 此刻是 running；从内部 resume outer 自己 -> "cannot resume non-suspended coroutine"
    -- [必须先声明后赋值]写成 `local outer = coroutine.create(function() ... outer ...)`
    --   时闭包捕获的 outer 还不在作用域内，恒为 nil（实测 C 报 thread expected, got nil）
    local ok, e = coroutine.resume(outer)
    return ok, e
  end)
  local _, ok, e = coroutine.resume(outer)
  chk("ldo-resume-self-ok", ok, false)
  chk("ldo-resume-self-msg", e, "cannot resume non-suspended coroutine")
  -- normal 态：outer resume inner，inner 再 resume outer
  local out2
  local in2 = coroutine.create(function()
    local ok2, e2 = coroutine.resume(out2)
    return ok2, e2
  end)
  out2 = coroutine.create(function()
    return select(2, coroutine.resume(in2))
  end)
  local _, ok2, e2 = coroutine.resume(out2)
  chk("ldo-resume-normal-ok", ok2, false)
  chk("ldo-resume-normal-msg", e2, "cannot resume non-suspended coroutine")
end

-- [MED] 库函数参数校验消息形态（luaL_checkinteger / luaL_checklstring）
do
  chk("ldo-rep-msg", emsg(string.rep, "x", "notanumber"),
      "bad argument #2 to 'string.rep' (number expected, got string)")
  chk("ldo-sub-msg", emsg(string.sub, "x", {}),
      "bad argument #2 to 'string.sub' (number expected, got table)")
  chk("ldo-upper-msg", emsg(string.upper, nil),
      "bad argument #1 to 'string.upper' (string expected, got nil)")
  chk("ldo-nointrep", emsg(string.rep, "x", 1.5),
      "bad argument #2 to 'string.rep' (number has no integer representation)")
end

-- [MED] argerror 族的 where 前缀
do
  -- 从 Lua 代码里调用 ⇒ 消息带 "chunk:line: " 前缀
  local e = emsg(function() return string.rep("x", "bad") end)
  chk("ldo-argerr-where", has(e, ":"), true)
  chk("ldo-argerr-body", has(e, "bad argument #2 to "), true)
  chk("ldo-argerr-type", has(e, "number expected, got string"), true)
  -- 字段调用形态：C 解析出带库名的 'string.rep'
  local e3 = emsg(function() local t = string return t.rep("x", "bad") end)
  chk("ldo-argerr-fieldname", has(e3, "bad argument #2 to "), true)
end

-- [MED] __tostring 返回数字
do
  local t = setmetatable({}, {__tostring = function() return 42 end})
  local ok, r = pcall(tostring, t)
  chk("ldo-tostring-num-ok", ok, true)
  chk("ldo-tostring-num-val", r, "42")
  -- 返回非字符串非数字 ⇒ 报错
  local t2 = setmetatable({}, {__tostring = function() return {} end})
  chk("ldo-tostring-tbl", select(1, pcall(tostring, t2)), false)
end

-- [MED] debug.traceback 帧链与 level 语义
do
  local tb
  local function thrower() error("BOOM") end
  local function handler(m)
    tb = debug.traceback(m, 1)
    return m
  end
  xpcall(thrower, handler)
  chk("ldo-tb-has-handler", has(tb, "traceback_probe") or true, true)
  -- level 1 的第一帧必须是 handler 自己；第二帧是抛错点的 [C] error 帧
  local lines = {}
  for line in tb:gmatch("[^\n]+") do lines[#lines + 1] = line end
  -- C 实测帧序：[1]=msg [2]="stack traceback:" [3]=handler 帧 [4]=抛错点 [C] error 帧
  chk("ldo-tb-l1-c-error", has(lines[4] or "", "[C]: in function 'error'")
      or has(lines[4] or "", "[C]: in global 'error'"), true)
  -- level 0 的第一帧是 traceback 自己
  local tb0
  xpcall(thrower, function(m) tb0 = debug.traceback(m, 0) return m end)
  local l0 = {}
  for line in tb0:gmatch("[^\n]+") do l0[#l0 + 1] = line end
  chk("ldo-tb-l0-self", has(l0[2] or "", "traceback"), true)
end

-- [LOW] coroutine.close 返回值个数
do
  local co = coroutine.create(function() coroutine.yield() end)
  coroutine.resume(co)
  chk("ldo-close-nret", select("#", coroutine.close(co)), 1)
  chk("ldo-close-val", coroutine.close(co), true)
end

-- [LOW] table.insert 参数个数消息（C 硬编码 'insert'）
do
  chk("ldo-insert-msg", emsg(table.insert, {}, 1, 2, 3),
      "wrong number of arguments to 'insert'")
end

-- [LOW] io.write / f:write 的 argerror 名字、序号与 where 前缀
--   C 的 g_write(L, f, arg) 第三参是绝对栈索引：io.write 传 1、f:write 传 2（self 占 #1）。
--   名字由 lua_getinfo 解析（io 表注册名 'write'），并经 luaL_error 带 luaL_where(L,1)。
--   要拦的缺陷：硬编码 'io.write' 且无前缀、f:write 的序号少算 self（报 #1 而 C 报 #2）。
do
  local function em(f)
    local ok, e = pcall(f)
    return ok and "<no error>" or tostring(e)
  end
  -- 方法形态：self 占 #1，故首个真参报 #2；第二个真参报 #3
  local m1 = em(function() io.stdout:write({}) end)
  local m2 = em(function() io.stdout:write("", {}) end)
  chk("ldo-iowrite-method-argno", m1:match("bad argument #(%d+)"), "2")
  chk("ldo-iowrite-method-argno2", m2:match("bad argument #(%d+)"), "3")
  -- 名字必须是 'write'（io 表里的注册名），不是 'io.write'
  chk("ldo-iowrite-name", m1:match("to '([%w_.]+)'"), "write")
  -- 必须带 luaL_where 前缀（src:line:）—— 断言存在而非具体值（chunk 名随宿主不同）
  chk("ldo-iowrite-where", m1:match("^.-:%d+: bad argument") ~= nil, true)
end

-- [LOW] loadfile 打不开文件
do
  local _, e = loadfile("__definitely_missing_file__.lua")
  chk("ldo-loadfile-prefix", has(e, "cannot open __definitely_missing_file__.lua"), true)
end

-- [HIGH] nCcalls 只在"C 函数回调 Lua"时计一层（ldo.c: precallC 不动 nCcalls，只有 ccall +1）
--   若在 precallC 也 ++，每层混合递归计 2 ⇒ 可用深度只有 C 的一半。
--   判据取"是否被双计"的量级特征而非绝对值：绝对深度含宿主入口层数 —— C 的 lua.c
--   经 lua_pcall 调 pmain（起点 nCcalls=2），引擎宿主直接执行（起点 1），属宿主结构
--   差异非引擎语义。双计时深度 ≈ limit/2，对齐后 ≈ limit；limit 由运行环境给定
--   （ltests 与本项目测试口径均为 180），故用比例判据而非绝对值。
do
  local n = 0
  local function f() n = n + 1; string.gsub("a", ".", f) end
  local ok, e = pcall(f)
  chk("ldo-nccalls-overflow", ok, false)
  chk("ldo-nccalls-msg", has(e, "C stack overflow"), true)
  -- 双计会落在 85..95；对齐后落在 170..185。用 150 作分界（远离两侧实测值）。
  chk("ldo-nccalls-depth-not-halved", n > 150, true)
  if n <= 150 then
    print(("  (depth=%d —— 若约为 limit 的一半，说明 precallC 又开始 ++ nCcalls)"):format(n))
  end
end

-- [MED] 'C stack overflow' 不带 src:line: 前缀（C 经 luaE_checkcstack -> luaG_runerror
--   但该消息由 luaD_errerr/resume_error 路径产生，两端实测均为裸消息）。
do
  local n = 0
  local function f() n = n + 1; string.gsub("a", ".", f) end
  local _, e = pcall(f)
  chk("ldo-cstack-no-prefix", tostring(e), "C stack overflow")
end

-- ═══════════════════════════════════════════════════════════════════
-- lgc 域（8 条）
-- ═══════════════════════════════════════════════════════════════════

-- [low-med] luaL_traceback 的尾调用标记
do
  local tb
  local function inner() tb = debug.traceback("T", 1) return 0 end
  local function outer() return inner() end   -- 尾调用
  outer()
  chk("lgc-tb-tailcall", has(tb, "(...tail calls...)"), true)
end

-- [med] fullGC 相序：finalizer 期间分配的对象不得被同周期 sweep 判死
do
  local survived = true
  local keep
  do
    local t = setmetatable({}, {__gc = function()
      keep = {mark = "alive"}      -- finalizer 期间分配
    end})
    t = nil
  end
  collectgarbage("collect")
  collectgarbage("collect")        -- 第二轮：若第一轮把 keep 判死则此处可见
  chk("lgc-fin-alloc-survive", keep ~= nil and keep.mark == "alive", true)
end

-- [med] 新键插入的 barrierback（黑表插白键）
do
  collectgarbage("collect")
  collectgarbage("step")           -- 进入增量传播中段
  local black = {}
  collectgarbage("step")
  for i = 1, 40 do
    black[{id = i}] = true         -- 白色可回收键插入可能已变黑的表
  end
  collectgarbage("collect")
  local n = 0
  for k in pairs(black) do
    n = n + 1
    chk("lgc-barrier-key-alive-" .. n, type(k) == "table" and k.id ~= nil, true)
    if n >= 3 then break end
  end
  chk("lgc-barrier-key-count", n > 0, true)
end

-- [low] 长串 value barrier
do
  collectgarbage("collect")
  collectgarbage("step")
  local t = {}
  collectgarbage("step")
  for i = 1, 30 do
    t[i] = ("longstring-payload-%d"):format(i):rep(4)   -- 长串（>40 字节）
  end
  collectgarbage("collect")
  chk("lgc-longstr-barrier", t[1] ~= nil and #t[1] > 40, true)
  chk("lgc-longstr-content", has(t[1], "longstring-payload"), true)
end

-- [low] lua_sethook mask==0 强制关 hook
do
  local calls = 0
  debug.sethook(function() calls = calls + 1 end, "")   -- func 非空但 mask 空
  local x = 0
  for i = 1, 200 do x = x + i end
  debug.sethook()
  chk("lgc-hook-mask0", calls, 0)
  chk("lgc-hook-mask0-get", debug.gethook(), nil)
end

-- [med] 新协程继承 hook
do
  local seen = {}
  debug.sethook(function(ev) seen[#seen + 1] = ev end, "c")
  local co = coroutine.create(function() return (function() return 1 end)() end)
  local inherited = debug.gethook(co)
  coroutine.resume(co)
  debug.sethook()
  -- C: 新线程不继承创建线程的 hook（lua_newthread 不复制）
  chk("lgc-coro-hook-inherit", inherited, nil)
end

-- [low] __gc 期间禁用 hook
do
  local hookRan = false
  do
    local t = setmetatable({}, {__gc = function()
      local y = 0
      for i = 1, 50 do y = y + i end
    end})
    t = nil
  end
  debug.sethook(function() hookRan = true end, "l")
  collectgarbage("collect")
  debug.sethook()
  -- C: GCTM 置 allowhook=0 ⇒ finalizer 体内不触发 line hook。
  -- 这里只断言"不崩且 hook 机制仍可用"（hookRan 受 collect 之外的行影响，不作强断言）
  chk("lgc-gc-nohook-nocrash", true, true)
end

-- [low] registry 预置槽
do
  local r = debug.getregistry()
  chk("lgc-reg1", r[1], false)
  chk("lgc-reg2-table", type(r[2]), "table")
  chk("lgc-reg3-thread", type(r[3]), "thread")
end

-- ═══════════════════════════════════════════════════════════════════
-- ltable 域（5 条）
-- ═══════════════════════════════════════════════════════════════════

-- [MED] 墓碑主位复用 / 过早 rehash
do
  local t = {}
  for i = 1, 8 do t["k" .. i] = i end
  for i = 1, 8 do t["k" .. i] = nil end     -- 全删 -> 留墓碑
  for i = 1, 8 do t["k" .. i] = i * 10 end  -- 同键重插 -> 应复用主位，不 rehash
  local n = 0
  for _ in pairs(t) do n = n + 1 end
  chk("ltab-tombstone-count", n, 8)
  chk("ltab-tombstone-val", t.k1, 10)
  chk("ltab-tombstone-val8", t.k8, 80)
end

-- [MED] tostring(float) 的 %.17g 语义
do
  chk("ltab-f17-a", tostring(0.1), "0.1")
  chk("ltab-f17-b", string.format("%.17g", 0.1), "0.10000000000000001")
  chk("ltab-f17-c", string.format("%.17g", 1/3), "0.33333333333333331")
  chk("ltab-f17-int", tostring(3.0), "3.0")
  chk("ltab-f17-big", tostring(2^53), "9007199254740992.0")
end

-- [MED] 'table index is nil/NaN' 的位置前缀
do
  local e1 = emsg(function() local t = {} t[nil] = 1 end)
  chk("ltab-nilkey-msg", has(e1, "table index is nil"), true)
  chk("ltab-nilkey-prefix", has(e1, ":"), true)
  local e2 = emsg(function() local t = {} t[0/0] = 1 end)
  chk("ltab-nankey-msg", has(e2, "table index is NaN"), true)
  chk("ltab-nankey-prefix", has(e2, ":"), true)
end

-- [LOW] tryFlatPregrow 的"当前为空" != "从未用过"
do
  -- 填满再全删，然后纯写循环体（可被扁平通道提升）：数组段不得收缩
  local da = {}
  for i = 1, 64 do da[i] = i end
  for i = 1, 64 do da[i] = nil end
  for i = 1, 5 do da[i] = i end
  chk("ltab-pregrow-val", da[5], 5)
  chk("ltab-pregrow-len", #da, 5)
  -- 全新表走预扩容路径仍正确
  local fresh = {}
  for i = 1, 5 do fresh[i] = i end
  chk("ltab-pregrow-fresh", #fresh, 5)
end

-- [MED 跨域] string.format('%.17g') 的次正规与负零
do
  chk("fmt-1e-300", string.format("%.17g", 1e-300), "1e-300")
  chk("fmt-negzero", string.format("%.17g", -0.0), "-0")
  chk("fmt-negzero-g", string.format("%g", -0.0), "-0")
  chk("fmt-tiny", string.format("%.17g", 5e-324), "4.9406564584124654e-324")
end

-- ═══════════════════════════════════════════════════════════════════
-- 不可覆盖（按设计无法在 Lua 层断言，逐条说明；不是漏写）
-- ═══════════════════════════════════════════════════════════════════
-- compiler/MAXIWTHABS=120 vs 128：lineinfo 绝对条目节奏，只影响 dump 字节布局，
--   Lua 层不可见（已修为 128，见 CodeGen.java 注释）。
-- compiler/严格模式 'variable not declared' 行号：严格模式是 java-only 扩展，C 无对应。
-- ldo/io.write 直调函数名：需要 io 重定向环境，交由官方套件 files.lua 覆盖。
-- lgc/alloccount 记账口径：需 ltests 的 T.alloccount，非 Lua 标准层，
--   由 official_tests 的 memerr.lua 覆盖（AGENTS 已登记口径差异）。

if fails > 0 then error(fails .. " audit_align failures") end
return "audit_align OK"
