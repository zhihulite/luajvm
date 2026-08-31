-- 仪器化测试用的最小 Lua 页面：在 LuaActivity 里把各条平台链路各走一遍。
--
-- 约定：每完成一项就往 RESULT 里记 true/错误串，测试侧读 RESULT 逐项断言。
--   页面自己不做断言 —— 断言留在 Java 侧，失败信息才能进 JUnit 报告。
-- 任何一步抛错都不得中断后续项：逐项 pcall，否则第一个缺陷会掩盖其余全部。

RESULT = {}

local function probe(name, fn)
  local ok, err = pcall(fn)
  RESULT[name] = ok and true or tostring(err)
end

-- import 是逐个类名（不支持通配）：布局表的第一个元素必须是 Class，
--   少导一个就会得到 "First value Must be a Class"。
probe("import", function()
  import "android.widget.LinearLayout"
  import "android.widget.TextView"
  import "android.widget.Button"
  import "android.widget.ListView"
  import "android.content.IntentFilter"
  import "org.luajvm.android.widget.LuaAdapter"
  assert(LinearLayout ~= nil, "LinearLayout 未导入")
  assert(TextView ~= nil, "TextView 未导入")
  assert(Button ~= nil, "Button 未导入")
  assert(ListView ~= nil, "ListView 未导入")
  assert(IntentFilter ~= nil, "IntentFilter 未导入")
  assert(LuaAdapter ~= nil, "LuaAdapter 未导入")
end)

-- ── 1. loadlayout 建 View 树 ──────────────────────────────────────────────
-- textSize 的四种写法都要能落到真实 DisplayMetrics 上（"16" 视作 sp、"16px"、
--   "16dp"、"5%" 按屏宽百分比）；只验证不抛且能取回 View，具体像素值在 Java 侧断言。
local LAYOUT = {
    LinearLayout,
    orientation = "vertical",
    layout_width = "fill",
    layout_height = "fill",
    {
      TextView,
      id = "title",
      text = "apk probe",
      textSize = "16sp",
    },
    {
      TextView,
      id = "sized_px",
      text = "px",
      textSize = "16px",
    },
    {
      TextView,
      id = "sized_dp",
      text = "dp",
      textSize = "16dp",
    },
    {
      TextView,
      id = "sized_pct",
      text = "pct",
      textSize = "5%",
    },
    {
      Button,
      id = "btn",
      text = "tap",
    },
    {
      ListView,
      id = "list",
      layout_width = "fill",
      layout_height = "fill",
    },
  }

-- 只建一棵树：交给 setContentView 统一建并附窗。
--   若先 loadlayout 再 setContentView(同表)，会建两棵，id 全局指向先建的那棵（未附窗）。
probe("layout", function()
  this.setContentView(LAYOUT)
  assert(title ~= nil, "id=title 未注入全局")
  assert(btn ~= nil, "id=btn 未注入全局")
  assert(list ~= nil, "id=list 未注入全局")
end)

-- 把 id 注入的 View 同时挂到一张普通表上：Java 侧若能从 VIEWS 读到、却读不到同名全局，
--   说明 id 注入进的是脚本的 _ENV 而不是 Globals 本体（读法问题，非注入失败）。
VIEWS = {
  title = title,
  sized_px = sized_px,
  sized_dp = sized_dp,
  sized_pct = sized_pct,
  btn = btn,
  list = list,
}

-- ── 2. adapter 绑数据 ──────────────────────────────────────────────────────
-- 稀疏与坏行都要能过：getView 必须给每个返回的 View 打 tag，
--   坏行也不能让后续行拿到脏 tag（A5 那批修的就是 tag 污染）。
probe("adapter", function()
  local data = {
    { text = "row 1" },
    { text = "row 2" },
    { text = "row 3" },
  }
  local adapter = LuaAdapter(this, data, {
    LinearLayout,
    layout_width = "fill",
    { TextView, id = "text", layout_width = "fill" },
  })
  list.Adapter = adapter
  assert(adapter.getCount() == 3, "adapter count 应为 3，实为 " .. tostring(adapter.getCount()))
end)

-- ── 3. luajava.override（dexmaker 在真机上生成 dex） ──────────────────────
-- 同一个类连续 override 两次、第二次多给一个方法：新增的必须生效
--   （ProxyBuilder 的进程级缓存键不含方法集，曾在此静默失效）。
probe("override_click", function()
  local fired = false
  btn.onClick = function()
    fired = true
  end
  btn.performClick()
  assert(fired, "onClick 未触发")
end)

probe("override_twice", function()
  -- 用有公开无参构造器的具体类：接口没有构造器，override 接口是另一条路径
  import "java.lang.Thread"
  local first = luajava.override(Thread, { run = function() end })
  assert(first ~= nil, "首次 override 返回 nil")
  local hits = 0
  local second = luajava.override(Thread, {
    run = function() hits = hits + 1 end,
    toString = function() return "probe" end,
  })
  second.run()
  assert(hits == 1, "第二次 override 的 run 未生效")
  assert(tostring(second.toString()) == "probe",
      "第二次 override 新增的 toString 未生效（ProxyBuilder 缓存键不含方法集时会静默失效）")
end)

-- ── 4. receiver 注册/注销 ─────────────────────────────────────────────────
-- 注册三个再全部注销：注销必须逐个 remove 而非"快照后 clear"
--   （A8 那批修的是 clear 会漏注销并发注册进来的 receiver）。
-- 经 getLuaDelegate() 而不是 this：LuaHost 没有 receiver 的 default 转发，
--   this.registerReceiver(filter) 会在 bind 层解析到 ContextWrapper 的 2 参版本而参数对不上；
--   BaseDelegate 的 1 参 registerReceiver / 零参 unregisterReceiver 只能经 delegate 拿到。
probe("receiver", function()
  local delegate = this.getLuaDelegate()
  assert(delegate ~= nil, "getLuaDelegate 返回 nil")
  for i = 1, 3 do
    delegate.registerReceiver(IntentFilter("org.luajvm.probe.ACTION_" .. i))
  end
  delegate.unregisterReceiver()
end)

-- ── 5. SharedData 与 GlobalData ───────────────────────────────────────────
-- SharedData 落盘（不可序列化的值须返回 false 而不是抛）；GlobalData 只在进程内。
probe("shared_data", function()
  assert(this.setSharedData("probe_key", "probe_value"), "setSharedData 应返回 true")
  assert(tostring(this.getSharedData("probe_key")) == "probe_value", "读回的值不一致")
  assert(tostring(this.getSharedData("absent_key", "fallback")) == "fallback",
      "缺键应返回给定默认值")
end)

probe("global_data", function()
  local g = this.getGlobalData()
  g.put("probe_mem", "in_process")
  assert(tostring(g.get("probe_mem")) == "in_process", "GlobalData 读回不一致")
end)

-- ── 6. json 编解码（Android 自带 org.json，与桌面那份行为不同） ───────────
probe("json", function()
  local encoded = json.encode({ id = 42, name = "probe" })
  local decoded = json.decode(encoded)
  assert(decoded.id == 42, "json 往返 id 不一致")
  assert(decoded.name == "probe", "json 往返 name 不一致")
  -- JSON null 须落 Lua nil（Android 的 opt() 返回 JSONObject.NULL 哨兵）
  local withNull = json.decode('{"a":null,"b":1}')
  assert(withNull.a == nil, "JSON null 应落 nil，实为 " .. tostring(withNull.a))
  assert(withNull.b == 1, "非 null 键应正常")
end)

-- ── 7. file 模块与 filesDir ───────────────────────────────────────────────
probe("file", function()
  local dir = this.getLuaDir()
  assert(dir ~= nil and #tostring(dir) > 0, "getLuaDir 为空")
  local path = tostring(dir) .. "/probe_write.txt"
  local fh = assert(io.open(path, "w"), "无法写入 luaDir")
  fh:write("probe")
  fh:close()
  local rh = assert(io.open(path, "r"), "无法读回")
  local content = rh:read("a")
  rh:close()
  os.remove(path)
  assert(content == "probe", "读回内容不一致：" .. tostring(content))
end)

-- ── 8. timer / thread 跨线程回到主线程 ────────────────────────────────────
-- 只验证能装上不抛；实际回调由 Java 侧等待 RESULT 里的标记。
ASYNC = {}
probe("timer", function()
  -- period 必须为正：0 会被拒（timer 是周期性的，不是一次性延迟）
  timer(function()
    ASYNC.timer = true
  end, 50, 50)
end)

probe("thread", function()
  thread(function()
    ASYNC.thread = true
  end)
end)

DONE = true
