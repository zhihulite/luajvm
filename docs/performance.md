# 性能

引擎性能的全部：结论、写代码的模式与禁忌、Continuation 模式、测量纪律、泄漏排查与判据。

## 第一部分：结论

- 全强度基线（`_soft=false`）下 Java 慢于 C-debug，差距为结构性；`_soft=true` 更快但不可比。
  机器与口径变化时必须重跑基准，不把任何值当常量。
- reference 的剩余差距是多类 JVM 逐操作成本的叠加，不存在尚未打开的总开关。
- FlatTFor 在被覆盖的长循环中可达到或反超 C-debug，但完整套件循环占比有限，
  端到端收益远小于微基准收益。
- 任何新方向必须先证明真实覆盖率与 Amdahl 上限再实现。
- callOnStack 在 `runtime_app` 上已全量栈直调；绑定侧补直调判 NO-GO（HotSpot 波动淹没）。
- FlatTFor 命中率无提升路径：被拒主体全在标量模型外（CALL/NEWTABLE/CONCAT/串），
  是模型边界问题而非白名单缺口；while/自递归通道同样判死。
- 真实应用运行期口径（196 个真实模块形态）：元表 OOP 反超 C，差距集中在表字段读写
  与闭包+pcall——这两项官方套件几乎不测。已交付 `PcallFn.callOnStack`。
- 全值模型重写 NO-GO：装箱只占差距个位数百分比，剩余是弥散逐操作成本；表段早已扁平。
- FlatTFor 覆盖率实测：官方全套件仅执行百余次，叶子内联/字段提升/浮点通道零命中。
- JFR 复测无新热点：`execute` 行号分散，其后无单一热点超过 8%。


## 已验证的有效优化

| 优化 | 证据与边界 |
|---|---|
| GC `GrayList` 侵入式链表 | 对齐 C 的 `g->gray` 结构，完整套件有可观收益 |
| GC 热循环索引访问 | 消除 `ArrayList$Itr`，有可观收益 |
| 类和热方法 `final` 化 | 帮助 JVM/ART 去虚化，有稳定小幅收益 |
| FlatTFor 数值循环 | 纯算术和表循环充分预热后接近或反超 C-debug |
| FlatTFor TFOR | pairs/ipairs 微基准大幅快于 reference，但完整套件近中性 |
| FlatTFor 读改写填充 | `fillMode + twoPass` 让读后写表循环不再立即回退，微基准近半收益 |
| `string.rep` 倍增复制 | 用算法替代线性追加，有稳定小幅收益 |
| `callOnStack` 的已确认覆盖 | MathLib 热函数与 `table.move` 避免部分 Varargs 往返；行为测试通过 |
| `valueOfLong` 哈希折叠 | 数字写入 byte 数组时同步计算哈希，避免再次扫描 |
| ltests `stacklevel` 改读 `L.nci` | 对齐 C 的 O(1)；消除 cstack.lua 深度递归 O(n²) |

这些数字来自不同日期和环境，只证明方向曾有收益，不能相加得出当前总收益。

## 已证伪或不再实施的方向

| 方向 | 实测结论 | 不再尝试的原因 |
|---|---|---|
| 通用替代解释循环 | 完整套件几乎不命中；自身解释循环占绝对多数采样 | 扩展语义的成本远高于覆盖收益 |
| 双表示 sidecar 栈 | 实验遇 `FORPREP/FORLOOP` 即回退，循环实际未执行 | 同步和物化也有固定成本 |
| 全值模型重写 | 装箱只解释差距的个位数百分比 | 剩余是弥散的逐操作成本，无单点可消；表的数组段/哈希段早已扁平（sidecar + `Node.value_*`），全值模型剩余增量只有栈那一半 |
| 原始数组逐 opcode 执行 | 弱于 FlatTFor 局部变量 | 数组 load/store 不能等同于 C2 寄存器化 |
| ASM Method JIT | 微基准有效，但完整套件出现回归 | 36 个套件候选均因混合类型操作编译失败；编译成本主导，且 Android 不兼容 |
| FlatTFor 叶子调用内联 | A/B 为负 | 入口、guard 和物化成本超过短函数收益 |
| C 到 Lua 调用去递归化 | 无净收益且复杂度高 | C 本身也使用递归调用路径，Java 差距不在这一处 |
| 内置函数身份识别内联 | 微基准好看，泛化性为零 | 用户自定义同名函数无法命中，属于跑分特化 |
| 小整数缓存 | 无收益甚至回归 | 缓存查找阻止 C2 标量替换 |
| resize 跳过未变化数组段 | 回归 | 热方法新增分支破坏 C2 优化 |
| String 转 byte[] 快路径 | 无净收益 | 新分配抵消比较优化，热点实际在探测循环 |
| 短串表大幅预分配 | 回归 | cache locality 变差 |
| StringFormat ByteBuf 默认路径 | 证据矛盾 | 高方差且 best 回归，不改变默认 |

## 新方向的准入条件

只有同时满足以下条件才进入生产实现：

1. JFR 或等价证据显示目标占完整工作负载至少约 5%。
2. 自动计数证明候选路径在目标工作负载中真实命中。
3. 最小实验给出足以超过噪声的收益上限。
4. 方案兼容 JVM 与 Android，不依赖运行时字节码、JNI、GraalVM 或预览特性。
5. 已建立行为测试、完整套件命令和隔离进程 A/B。

不满足时记录假设和数据即可，不修改 reference 热路径。

**第 2 条最容易被跳过**：微基准大幅提速不代表端到端有收益——`OP_GETI` 形态在官方
全套件里只执行 1 次，微基准再快端到端收益也为零。挂计数器只要几分钟，
必须在提交之前做。

各候选的终态见下方「结论索引」。


## 结论索引

### 已交付

| 主题 | 结论 |
|---|---|
| 真实应用运行期口径 | 196 个真实模块形态；元表 OOP 反超 C，差距在表字段与闭包+pcall；`PcallFn.callOnStack` |
| Continuation 第三模式 | 协程密集基准多项反超 C；Android 不可用（内部 API） |
| callOnStack | 库函数 `runtime_app` 全量栈直调 |
| analyze 补 OP_GETI | 通道对称性修复，端到端为零 |
| 预编译字节码加载 | 唯一过准入门槛的冷启动候选；Hydrogen 已用 `-PluaMode=luac` |
| Android 三项 | LuaLayout 桥接 / JavaClass 按需构建 + 名称索引 / 次级属性直调 |
| bind 层四项快路径 | 短串缓存 / 直调 / cacheable 分层 / 内部类按需包装 |
| cacheable Android 修复 | `getSystemClassLoader()` 非 app loader 致全类误判不可缓存 |
| 移除 MethodHandle | minSdk 回到 24；ART 上 `Method.invoke` 等价 |
| WebView 预启动 | 首次构造显著缩短；开关 `-Dluajvm.webviewprewarm` |
| FlatTFor | analyze 冷路径拆分腾出预算；仅保留 TFOR（pairs）通道，扩展到 ipairs 反超 C |
| FlatIFor | 数值 for 通道贴平 C；设备侧验证显著快于装箱路径 |
| `str..int` 直写拼接 / intmod | 跳过中间数字串驻留 / 整数取模微优化 |

### NO-GO / 证伪（勿重复立项）

| 主题 | 结论 |
|---|---|
| FlatTFor 覆盖率三通道 | 被拒主体全在标量模型外，是模型边界而非白名单缺口 |
| ltests `alloccount` 记账口径对齐 | 须故意多分配中间 buffer 才能让调试计数器读数相同，热路径代价不可接受 |
| `tryRun`/`execute` 进一步抽离 | 都是热路径；`analyze` 能抽是因为它是冷分析器 |
| 全值模型重写 | 装箱只占差距个位数百分比；表段早已扁平 |
| JFR 热点复测 | 无新杠杆，`execute` 行号分散无单一热点 |
| `__index` 指向函数 / 协程 resume | 成本在装箱与嵌套 execute / 已由 Continuation 解决（Android 除外） |
| FlatTFor 浮点 / 布尔物化通道 | 安全判据过窄收益不达 / 零覆盖 |
| OP_FORLOOP 计数槽等五项 | 各自实测无收益或负收益 |
| 设置页预热 / 剩余候选 | 无收益且引入 janky 帧 / 已证否 |
| MaxFrame 高模归因 | 长帧是 loadlayout 单帧建 View（Android 侧），引擎属性分派占比极小 |
| 短串驻留 miss 五候选 | 软引用策略已在最优点 |
| FlatTFor 存废 | 裁剪而非全删：pairs 通道有真实收益 |
| gsub / 干净场景 / S3 深挖 | 无单一主成本或全在噪声下，无新达标项 |
| 回答页首开 vs 重开 | 一次性成本（编译/import/类加载）+ 首帧 View 构造；`.luac` 只省编译段 |

### 归因记录（无生产改动）

| 主题 | 结论 |
|---|---|
| Android 回答页首开/重开 | 首开中引擎类首触占比小；重开更快 |
| 结构极限 | 剩余差距是 JVM 逐操作成本叠加，无总开关 |


## 第二部分：写代码的模式与禁忌

## 编写 Lua 库函数（JavaFunction）：高频函数必须 override `callOnStack`

**问题**：默认 `LuaFunction.call(Varargs)` 每次调用都要打包 Varargs（`new LuaValue[]`）
并解包（`arg(i)` 提取），这是高频 C 函数的主要调用成本。

**模式**（对齐 C 的 `lua_CFunction` 栈直调约定）：

```java
// LuaFunction.callOnStack 基类返回 -1（不支持）——必须 override 才生效
public int callOnStack(LuaThread L, int func, int narg) {
    // 参数在 L.stack[func+1 .. func+narg]；结果写到 L.stack[L.top] 并更新 L.top
    // 返回压入的结果数；返回 -1 则调用方回退 Varargs 路径
}
```

- 判断标准：**高频 + 参数/结果形态简单**（纯数值、短字符串、1-3 个参数）才值得 override；
  低频函数用默认 Varargs 路径即可。
- `LuaCall.precallC` 会**先试** `callOnStack`，返回 -1 才走 Varargs——两个路径并存，行为一致。
- 已覆盖 48 个库函数（BaseLib 18 / MathLib 11 / StringLib 13 / TableLib 6），新函数照抄风格。
  （计数口径：各 lib 里 `public int callOnStack` 的实现数）
- 引擎**内部**调用 Lua 函数（table.sort 比较器、metamethod callTM/tryBinTM/callOrderTM）
  用 `LuaCall.callOnStack1to1/2to1/3to1(target, args...)`——参数直接放 `L.stack[]` 再
  `callNoYield`，结果从 `L.stack[func]` 取，免 Varargs 往返。
- A/B 开关：`-Dluajvm.libcallonstack=false` 禁用库函数 callOnStack 覆盖（不影响既有已验证的）。

## 编写 Java 互操作（bind）：用纯反射，不引入 MethodHandle

已移除 MethodHandle 并有门禁钉死。要点：

- ART 无 native 通用路径（`asSpreader` 等全是纯 Java transform）；构造远慢于 JVM；
  真实路径上与反射无差距；且引入它会把 minSdk 从 24 顶到 26（D8 对签名多态的要求）。
- **引用任何性能数字前先确认口径是裸调用还是真实路径。**
- 门禁 `bindFastPathTests` 解析 class 常量池断言无 `MethodHandle` 引用；
  不能按字符串扫（`MethodHandle` 是 `MethodHandles$Lookup` 的前缀，会误报）。
- 调 JDK 内部 API 用 `Method.invoke`；注意它把目标异常包进
  `InvocationTargetException`——须解包后按原规则分类，否则 `LuaError` 的错误对象
  与 traceback 被包装层吞掉。
- varargs 方法只能走 `Method.invoke`（MethodHandle 会展开数组参数，破坏
  `setColorSchemeColors(int...)` 语义）；VarHandle（API 33+）不引入。


## bind 层内部的 Java->Java 调用：不要建 Lua 帧

目标是 `JavaBinding` 的实现者（`JavaMethod`/`JavaConstructor`/两者的 `Overload`/
`JavaOOMethod`）时，函数体是纯 Java，完整 Lua 调用协议（`prepCallInfo`、栈拷贝、
`callOnStack` 试探、Varargs 打包、`poscall`、结果打包退栈）全是净开销。

```java
// 宿主构造 Java 对象（等价 Lua 侧 Cls(args...)）
JavaCall.construct(viewClass, ctxLua)          // 而不是 LuaCall.call(viewClass, ctxLua)
// 宿主调绑定成员（参数已是 LuaValue）
JavaCall.invokeMember(obj.getJavaMethod("addView"), child)
// bind 层内部
LuaCall.callJavaBinding(fn, args)              // 而不是 LuaCall.callLua(fn, args)
```

- 实测（`bindBench ctor_frame2/1/0`）：剥离两层帧后 View 构造显著变快。
- **判定必须精确到 `JavaBinding`**：只按 `instanceof LuaFunction` 判会把 `LuaClosure`
  （真正的 Lua 函数）也算进来，绕过 Lua 函数必须的帧建立 = 语义错误。
- `JavaCall.construct` 必须查 `hasDefaultJavaCall()`：用户改写 `__call` 后要回到完整元方法
  协议。同理 `JavaObject.get/set` 的 VM 快路径查 `hasDefaultJavaIndex/NewIndex`。
- `callJavaBinding` 的三条保真：hook 存在时回退完整路径；仍计一层 `nCcalls`；
  不触碰 `L.top`/`L.ci`/`savedpc`。
- 开关 `-Dluajvm.bindfastcall=false` 回到旧行为。

## LuaString 转 Java String：短串走缓存

`LuaString.toJavaString()` 对**纯 ASCII 短串（≤40B）**复用既有的 `cachedString` 字段
（ASCII 下 UTF-8 与 ISO-8859-1 解码逐字符相同），实测大幅变快。
长串不缓存（可能是整个文件内容，缓存会让内存翻倍），非 ASCII 仍走 UTF-8 解码。
开关 `-Dluajvm.strjcache=false`。

**写新代码时**：Java 绑定的成员名/属性名分派会反复调 `toJavaString()`
（`JavaClass.get` 的 String switch、`JavaObject.set` 的 setter 名拼接、
`JavaClass.getMethod` 的 `methodIndex` 查表），能用 `LuaString` 常量 `raweq` 比较就
不要转 String。

## 反射元数据要"先建索引、后建包装"

`JavaClass` 的三个懒建表都遵循同一个模式：**沿继承链扫一次得到纯元数据索引（可进程级
共享），只为真正被访问到的名字建包装对象**。

- `methodIndex`：`String → List<Method>`，进程级 `SHARED_METHOD_INDEX`；
  只为命中的方法名建 `JavaMethod`。
- `innerClassIndex`：`LuaValue → Class<?>`，进程级 `SHARED_INNER_CLASS_INDEX`；
  只为命中的名字建 `JavaClass`（避免一次包装沿继承链全部 public 内部类——
  Android View 子类动辄数十个）。
- **加新的懒建表时照抄这个两段式**，不要"一次建全部包装"。包装对象往往携带
  `Globals`（不能共享）而元数据不携带（可以共享），两段式同时解决性能与所属状态问题。

`getFields()` 的 `fieldMap` 目前仍按 Globals 重建，实测 ART 上很快，
不值得为它动 8 张表；不要据此推广"所有表都要共享"。

## 进程级缓存的 ClassLoader 判据

`JavaMethod.cacheable(Class)` 决定五处进程级缓存是否收下某个类。**判据必须沿
「本引擎自身 loader」的父链**——Android 上 `getSystemClassLoader()` 不是 app 的 loader
（libcore 现造的另一个 `PathClassLoader`，父链只到 BootClassLoader），沿它判会把全部
app 类误判为不可缓存，每个 Globals 重付 `getMethods()`（ART 上极贵且不缓存）。

- 加新缓存时照抄 `cacheable`，只沿**父**链向上——自定义子 loader 永远判 false，
  不会重新引入 ClassLoader 泄漏。
- 门禁：`:luajvm-core:loaderCacheTests`。


## `view.onXxx = fn` 每次重建 Proxy：已知候选，当前收益不足（不要顺手改）

`JavaObject.javaSetListener` 每次赋值都要：拼 `"setOn"+X+"Listener"` → `getMethod` →
`new LuaTable` → `getParameterTypes()`（数组克隆）→ **`Proxy.newProxyInstance`** →
调 setter。ART 实测这段远慢于预建 Proxy 直调 setter；
RecyclerView 复用 ViewHolder 时每次 `onBind` 都重设 ⇒ 每次重建。

**但设置页实测这一段占比远低于设备噪声，故不做。**
将来若 bind 段成为瓶颈，做法是**按 JavaObject + listener 名缓存 Proxy 实例，
只替换 handler 表里的 Lua 函数**；语义边界（先想清楚再动手）：

- Proxy 已装在 View 上，复用时**不能**再调 setter，否则白付一次 setter；
- 外部 `setOnClickListener(null)` 或 `setOnClickListener(别的)` 会让缓存失效，
  必须能检测到（如读回 getter 比对身份），否则赋值静默无效 —— 这是**正确性**问题；
- 缓存挂在 `JavaObject` 上会延长 View 生命周期，要走弱引用
  （static 容器只能持不可变值/弱引用/有清除机制）。

## Android 与定位手法

- **定位"某段 Lua 慢"**：按语句块分段插桩，不要按调用次数推算——"单次成本 × 次数"
  可与实测差一个数量级（成本极不均匀，少数控件占大头）。
- **判 Android 长帧**：拆 `framestats` 的 23 列时间戳，不要只看 `MaxFrameMs`——
  高模长帧是 TRAVERSAL（Java 布局），低模长帧在 GPU，成因完全不同。
- **加法/减法归因**：先检查"各项之和是否等于总量"——不等说明测到的是共享资源。
- **引擎最大的单点收益是 ART AOT，不是算法**：纯 JIT vs Baseline Profile 下脚本密集
  负载显著提速（`execute` 贴着 `HugeMethodLimit`，AOT 直接绕过预热窗口）。三条实操：
  1. `baseline-prof.txt` 要跟着包名走——ART 对匹配不到类的规则**静默丢弃**，改包名后
     profile 悄悄变哑弹；门禁 `baselineProfileTests` 断言每条规则命中真实类。
  2. 判"Android 上慢"前先确认构建不是 debuggable——`DEBUGGABLE` 标志使 **ART 一律
     拒绝 AOT**（`compile -m speed` 报 Success 但 status 仍是 verify），读数整体偏高。
  3. 微基准的"冷态"有两层：JIT 未预热（只有 AOT 能解）+ 类元数据未建（绑定层类首触
     成本），别混着归因。


## 参数/返回值转换与其余禁忌

- 参数/返回值转换统一走 `Coercion`（Adapter 构造时缓存），热路径不查表、不自己 new。
- 热路径禁忌（已证伪不要再试）：小整数缓存、strCache、热方法加分支、resize 跳段、
  字符串转 byte[] 快路径、短串表预分配。
- 类和热方法用 `final` 帮助 JVM/ART 去虚化；GC 热循环用索引循环而非 foreach。
- 每项优化：JFR 证明命中 → 行为测试 → 隔离进程交替 A/B → 双平台编译；收益不达标就回退。


## 第三部分：Continuation 模式（协程第三实现）

基于 JDK 内部 API `jdk.internal.vm.Continuation`，以栈帧切换替代线程调度。

| 模式 | 底层技术 | 单次往返 | Android |
|---|---|---:|---|
| 虚拟线程 | Virtual Thread (JDK 21+) | 慢 | 不可用（ART 无 Thread.ofVirtual，JEP 444 未落地 Android） |
| 平台线程 | Platform Thread | 慢 | 可用（ART 上的唯一模式，自动回落） |
| Continuation | jdk.internal.vm.Continuation | **远快于线程，反超 C** | **不可用**（ART 无该内部 API） |

定性结论：Continuation 模式反超 C；两种线程模式都慢于 C 一个台阶，且该差距是
OS 线程唤醒的固定成本（纯 Java 握手探针可复现，与引擎代码无关），无代码层优化空间。
本机实测里虚拟线程反而慢于平台线程——mount/unpark 是叠在线程唤醒之上的第二层开销。

启用需同时给两个 JVM 参数（Android 上无此入口，自动回落线程模式）：

```bash
java -Dluajvm.cont=true --add-exports java.base/jdk.internal.vm=ALL-UNNAMED -cp ... Main
```

微基准远快于虚拟线程；vs C Lua 差距大幅缩小。协程密集基准多项反超 C。**但官方套件（协程占比极低）
端到端反而略慢——协程占比低的负载收益无法传导**。微基准快的机制：零线程切换
（无 park/unpark）、零锁（无 ReentrantLock）、JVM 内部栈帧管理。

适用：协程密集（占比 > 10%）、高并发 I/O、生成器/流式。不适用：CPU 密集、Android、
对内部 API 稳定性敏感的生产环境。挂起的 Continuation 不占线程，
「挂起协程钉死平台线程」的问题在此模式下结构上不可能。诊断栈帧在调试器不可见，
用 Lua 侧 `debug.traceback()`。

## 第四部分：测量纪律

- 每个样本独立进程；至少 warmup 一轮并 best-of-N；置信区间跨 1.0 或轮次矛盾即不确定。
- A/B 两侧同脚本、同 JVM 参数、同 class 来源；不得换可执行文件或参数掩盖回归。
- **A/B 开关必须先确认传到了 fork 的测试 JVM**：Gradle 命令行 `-D` 只落 daemon，
  须经 `-P` 透传列表登记（漏登记则「OFF 跑全套件」其实一直在 ON 下跑，两侧自然相同）。
  验证：`--info` 抓 fork JVM 命令行确认 `-Dluajvm.<name>` 真在参数里。
- **扩优化通道前先用探针**（暂时放行 opcode → 继续扫 → 最终仍 REJECTED → 只记统计）：
  拆一道拒收墙只是撞上体内下一道墙。判优先级按执行量而非站点数。
- **判「该做哪种优化」先 dump 真实 opcode 序列**，别从拒收原因反推形态。
- **比较不同通道的命中率前核对分母同源**（循环入口数 / 回边迭代数 / 函数调用总数
  语义不同，不可比）。
- **零收益改动有预算成本**：`analyze` 加 case 吃字节码预算，顶到 HugeMethodLimit
  即真回归。「测试通过」≠「可以落地」。
- **不要在同一 shell 循环里交替 spawn 两个 JVM 做 A/B**：编译线程与 CPU 状态互相
  干扰可造假读数。
- **同一文件里多个用例互相污染**：前序用例改变 JIT 与 GC 时机，读数可差数倍；
  发现巨大差距的第一反应是拆独立文件复测。
- **判定 opcode 覆盖不能靠静态扫 `case` 名**：多标签 `case A: case B:` 会让字符串
  扫描漏算。
- **微基准构造段移出计时段**；新脚本先验单位（拿已知量级操作对照），µs 标成 ns
  会得出「无优化空间」的假结论。
- 每次改动后确认 class 比 source 新；增量构建留旧字节码则全部数据失效。
- 性能门禁必须验证候选路径真实命中——对拍通过而计数为零不是性能证据。

### 机器状态漂移

- 跨批比较无效（改前改后的绝对读数说明不了任何事）；改动理论上不影响性能却
  测出大变化时先怀疑机器。
- 唯一可靠对照：`git worktree` 签出父提交，两侧交替跑；worktree 需手工补
  `gradle-wrapper.jar`。**不要用 `git stash` 切基线**——会丢未提交改动。
- 「四对全部同向」也可能是批次效应；一组内全部同向时必须换一组（最好反序）重跑。
- 配对差「有点像真的」时用 base-vs-base 判噪声：同一份 class 自比，若基线自身相邻差
  与候选差同量级即噪声。只认区间不重叠的读数。
- 先算量级上界（改动触及的路径占总耗时多少），上界低于噪声底就不立项。

### 测量口径陷阱

- `officialAllTests` 是编译器套件，core 改动必须另跑真实应用口径（`runtime_app.lua`）。
- 配额/计数类探针的 pcall 保护帧必须在设配额**之前**建立。
- `uiautomator dump` 在部分环境每次 segfault——「等待控件超时」多读到旧 dump；
  报错先翻 dump 内容确认包名。
- 别把 `gradlew` 整体墙钟当主指标：混着 daemon 冷热与 up-to-date 检查，可造出
  「三对同向」假信号。用套件自带的 Lua 内计时。


## Amdahl 与覆盖率

- 要把数倍的端到端差距拉平，即使局部无限快也至少要覆盖大部分总耗时；局部提速幅度
  越小所需覆盖率越高。先算量级上界再动手。
- **降分配不等于降 wall-time**：分代 GC 下短命对象近乎免费（C2 标量替换）；
  分配字节下降而 wall-time 不动的实验反复出现。
- **热方法不能随意加分支**：`execute`/`findShortString`/`resize` 等热方法新增分支
  会造成稳定回归；快路径原则是「不加判断直接做，失败走慢路径」。
- 微基准只能证明机制；死代码删除不影响性能（别把死代码当优化空间）。
- **行为保真门禁**：`T.querytab` 表形状、`T.alloccount` 分配序列、错误文本、整数回绕、
  元方法、hook、协程、GC barrier——交付前 C/FlatTFor ON/OFF 三态对拍 + 完整套件对照。
- **容易被工具掩盖**：编辑工具显示成功不等于落盘（回读验证）；退出码单独取；
  PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会添 BOM 致编译失败；短名替换必须
  精确匹配并回读；验证「不可达」先证明入口在被测环境里存在（`runLuaFile` 不装 luajava）。
- **分析器陷阱**：箭头 switch 的 `case -> { }` 里 `break` 可能跳出外层 `for`——
  消费多槽 opcode 时只推进循环变量让 case 自然结束。
- **门禁空转比没有门禁更危险**：新门禁先在未修复态确认 FAIL。
- **均值藏一次性初始化**；**Android 判据必须含最大帧**（总时间改善而最大帧暴涨仍 NO-GO）；
  **改包名/改 assets 后装机先 `pm clear`**（否则首页假绿、按名 bindClass 的页面运行期才炸）。

## 泄漏排查与判据

- **trace C 双端对比（最有效）**：C 侧加打印重编译，Java 侧同条件打印——按 top 值
  时间序列对齐，找第一个分歧点。
- **计数类泄漏**：溢出时打印帧链深度对比计数值——深度浅而计数高即纯泄漏。
  配对审计：++ 与 -- 序列分别收集对比，无配对处即泄漏点。
- **Heisenbug**：调试日志改变时序/栈布局会产生推进假象——必须无日志复跑确认。
- **`_U` 假象**：`_U` 下 `T=nil` 清掉 ltests 库，T 守卫段被跳过，聚合的
  `final OK` 是部分通过假象。runner 去 `_U`、保留 `_soft/_port/_nomsg` 与 T。
- **ltests 编译宏**：`MAXINDEXRK=1` 压缩 K 索引范围 ⇒ 带 ltests 编译的字节码与生产版
  不同——「生产 vs 带 ltests」必须分别编译验证；Java 编译器对齐生产版。


## 核心结论：一种探针只能看见一种形状的泄漏

真泄漏各是既有探针的结构性盲区：

| 查出的泄漏 | 用的探针 | 为什么既有探针看不见 |
|---|---|---|
| 宿主 `callLua` 入口不退栈 | 登记表条目数随轮数增长 | 泄漏在 Lua 栈上，栈不是登记表 |
| 标准流登记进进程级 `openHandles` | 存活堆字节斜率 | 单 `Globals` 内跑不暴露跨状态残留 |
| 挂起协程钉死整个状态 | 按特性的 `Globals` 回收矩阵 | 同一状态内跑不出「整状态泄漏」；它不是每轮涨几百字节，而是一次性几百 KB |

**教训**：连续多轮「全 BOUNDED」不等于干净，只等于「这种形状的泄漏没有了」。
与其把同一探针的轮数加倍，不如换一个提问方式。

## 五类探针与各自的盲区

按「问题的提法」分类，按需组合。前四类以轮数为自变量，第五类换成类数/线程数。

| 探针 | 看得见 | 盲区 | 关键做法 |
|---|---|---|---|
| 登记表计数 | 登记表/缓存的无界增长 | 不在登记表里的对象（栈槽、CallInfo、Java 局部） | 峰值取样在循环内（增量 GC 边跑边回收） |
| 堆字节斜率 | 任何形状的每轮净增 | 整状态泄漏；per-class/per-thread | 加 `-XX:SoftRefLRUPolicyMSPerMB=0`（软引用不清会淹没信号）；单段高次段回落=缓存充填非泄漏；取样前 `fullGC`+`execute("return 1")`+`System.gc()`（那句 Lua 不能省） |
| 回收矩阵（按特性） | 整状态泄漏、特性建立的 GC 根链 | 状态活着时内部的无界增长 | 状态只在一个方法内可达（catch 变量的死槽会钉住对象误报） |
| per-class / per-thread | 按 Class 键的进程级表错收；ThreadLocal 池随线程累积 | 单轮内的结构增长 | N 个独立 loader 各定义一个类；**前置自检是判别力的全部**（断言表里真写进了、池真建出了） |
| 堆转储反向引用链 | 确定的持有者 | — | `dumpHeap(live=true)` 后解析 hprof BFS 回溯 |


## 判别力：三条不可退让的规则

「门禁空转」（看着 PASS，其实什么都没守住）须按以下三条规则防范。

1. **「全 BOUNDED」必须配正对照**：全零既可能是真干净也可能是探针没判别力——
   先加已知无界的正对照确认探针能报 LEAK，那些 0 才有意义。
2. **新门禁先在未修复态确认 FAIL**：反向注入——把守护的修复改回缺陷态跑一遍，
   确认 FAIL 且信号精确归因。一次只改一处。
3. **前置自检是判别力的载体**：对照组和前置断言比主断言更容易恒真。真实案例：
   对照组被引擎优化架空（尾调用帧复用不扩容、纯整数体命中扁平通道零 Lua 栈——
   体内须放表构造器拒收）；载荷从不进入被测路径（只走 `parse(byte[])` 而
   reader 只由 `parse(Reader)` 赋值）；前置断言独自挡住「登记表恒空」缺陷态的空转。


## 观测对象的陷阱

- **弱引用判「可回收」前先确认观测的是什么**：用 `e.luaError` 判表错误可回收性会恒报
  泄漏——该字段是字符串化形式，短串被驻留表软引用持有，`System.gc()` 不清软引用。
  凡断言「X 应可回收」，先回答「我拿到的这个引用到底指向谁」。
- **软引用的可回收性只能靠堆压力证明**：`fullGC`/`System.gc()` 都不清软引用；须制造
  真实堆压力到 OOM（JLS 保证 OOM 前必清软引用）再 intern 新串触发 `purge()`。
  `internPressureTests` 的判据。


## 有些泄漏不该「修掉」

挂起协程钉死状态是个闭环：要自动收尾就得检测「宿主已丢弃状态」，
而那要求状态不可达；但 park 着的线程恰恰让它永远可达。`Cleaner`/`PhantomReference`
挂在 `Globals` 上也永不触发。

这种情况正确的做法是**提供显式出口 + javadoc + 门禁**（本例为 `Globals.close()`），
而不是硬造一个检测不到的自动机制。判断标准：如果「检测条件」本身被「泄漏对象」否定，
就是闭环，停止找自动解。

## 代码判据

- 给会抛错的 C 函数包 `callOnStack`：出错不能 `return -1` 交回退重跑；`closeUpvals` 的
  level 是被保护函数的槽。
- 静态字段跨 `Globals` 残留是真实 bug 温床（gc.lua 的模式污染过后续 cstack 测试）；
  修法是迁入 `Globals` 或按状态归属。
- 栈布局保真：C 函数帧的 func 槽位偏差即断言失败；`luaD_checkminstack` 语义是
  `stack_last - top`。
- `JavaClass.getMethod` 按名索引而非全量预建：ART 的 `getMethods()` 极贵且不缓存。
- 宿主桥接入口必须显式退栈——Java 已把结果复制进 `Varargs`，C 的「留栈交宿主 pop」
  契约不适用；不退栈则栈永不收缩且返回值被钉死。
