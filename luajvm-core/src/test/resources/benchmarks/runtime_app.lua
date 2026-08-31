-- java-only: 真实应用【运行期】负载基准（对照 officialTests 的编译器测试套件形态）
--
-- 为什么需要它：officialTests 是编译器测试套件（calls.lua/errors.lua 循环 load()），
--   既有冷启动口径只覆盖 load 阶段；196 个真实模块 load 完之后的运行期
--   （UI 回调 / 字符串处理 / 元表 OOP / 表字段读写）由本文件给出度量口径。
--
-- 形态取自真实业务 Lua（UI 回调/字符串构建/表字段/闭包），不是构造的算术循环：
--   S1 元表 OOP    ← extensions/class.lua（setmetatable + .super 继承链 + __index）
--   S2 字符串构建   ← models/*（SpannableStringBuilder 式拼接、gsub/format/find）
--   S3 表字段读写   ← components/*（配置表读写、数组段 append、哈希段命名字段）
--   S4 闭包回调     ← pages/*（事件回调、pcall 包裹、vararg 转发）
--
-- 用法：RunLuaFile benchmarks/runtime_app.lua [超时秒]
--   BENCH_ITERS=N（环境变量）覆盖迭代数（默认按 wall-time 自适应到 ~1s/场景）

local clock = os.clock

local ITERS = tonumber(os.getenv and os.getenv("BENCH_ITERS") or nil) or nil

-- ── S1: 元表 OOP（class.lua 形态）──────────────────────────────
local function make_class(super)
  local cls = {}
  cls.super = super
  cls.__index = cls
  if super then setmetatable(cls, { __index = super }) end
  function cls.new(...)
    local o = setmetatable({}, cls)
    if o.ctor then o:ctor(...) end
    return o
  end
  return cls
end

local Base = make_class(nil)
function Base:ctor(n) self.n = n or 0 end
function Base:value() return self.n end
function Base:describe() return "base:" .. self:value() end

local Mid = make_class(Base)
function Mid:value() return self.n * 2 end

local Leaf = make_class(Mid)
function Leaf:describe() return "leaf:" .. self:value() end

local function s1_oop(iters)
  local acc = 0
  for i = 1, iters do
    local o = Leaf.new(i)
    acc = acc + o:value()          -- 3 层 __index 查找
    if i % 64 == 0 then
      local _ = o:describe()       -- 跨层方法 + 字符串拼接
    end
  end
  return acc
end

-- ── S2: 字符串构建（models 形态）───────────────────────────────
local function s2_string(iters)
  local n = 0
  local names = { "alice", "bob", "carol", "dave" }
  for i = 1, iters do
    local who = names[(i % 4) + 1]
    local s = string.format("@%s #%d", who, i)
    if s:find("@", 1, true) then
      local t = s:gsub("#(%d+)", function(d) return "[" .. d .. "]" end)
      n = n + #t
    end
    if i % 32 == 0 then
      local parts = {}
      for j = 1, 8 do parts[#parts + 1] = who .. j end
      n = n + #table.concat(parts, ",")
    end
  end
  return n
end

-- ── S3: 表字段读写（components 形态）──────────────────────────
local function s3_table(iters)
  local cfg = { width = 0, height = 0, title = "", visible = false, count = 0 }
  local list = {}
  for i = 1, iters do
    cfg.width = i % 320                 -- 哈希段命名字段写
    cfg.height = (i * 2) % 480
    cfg.visible = (i % 2) == 0
    cfg.count = cfg.count + cfg.width   -- 读+写同槽
    if i % 16 == 0 then
      list[#list + 1] = cfg.width       -- 数组段 append
      if #list > 256 then list = {} end
    end
  end
  return cfg.count + #list
end

-- ── S4: 闭包回调（pages 形态）─────────────────────────────────
local function s4_callback(iters)
  local total = 0
  local function on_event(kind, a, b)
    if kind == "click" then return (a or 0) + (b or 0) end
    return 0
  end
  local handlers = {}
  for i = 1, 8 do
    handlers[i] = function(...) return on_event("click", ...) end
  end
  for i = 1, iters do
    local h = handlers[(i % 8) + 1]
    local ok, r = pcall(h, i, 1)
    if ok then total = total + r end
  end
  return total
end

-- ── 计时框架 ──────────────────────────────────────────────────
-- 自适应迭代数：先探测使单场景约 0.25s，再正式测（避免固定迭代在快/慢引擎上失配）
local function calibrate(fn)
  if ITERS then return ITERS end
  local n = 1000
  while n < 20000000 do
    local t0 = clock()
    fn(n)
    local dt = clock() - t0
    if dt >= 0.25 then return n end
    n = n * 2
  end
  return n
end

local function bench(name, fn)
  local iters = calibrate(fn)
  fn(iters)                      -- 预热（JIT）
  fn(iters)
  local best = math.huge
  for _ = 1, 5 do                -- best-of-5（见 docs/performance.md『测量纪律』）
    local t0 = clock()
    local sink = fn(iters)
    local dt = clock() - t0
    if dt < best then best = dt end
    _G._sink = sink              -- 防 DCE
  end
  local ns_per_iter = best * 1e9 / iters
  print(string.format("%-14s iters=%-9d best=%.4fs  %8.1f ns/iter",
    name, iters, best, ns_per_iter))
  return best
end

print("=== 真实应用运行期基准（形态取自真实业务 Lua）===")
local t = 0
t = t + bench("S1_oop", s1_oop)
t = t + bench("S2_string", s2_string)
t = t + bench("S3_table", s3_table)
t = t + bench("S4_callback", s4_callback)
print(string.format("TOTAL best-sum = %.4fs", t))
