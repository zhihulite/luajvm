# Globals 生命周期契约（宿主向）

本文说明宿主该如何创建、共享和释放 `Globals`，并给出并发语义。
泄漏排查方法见 `performance.md` 的「泄漏排查与判据」节。

## 并发语义与不可变边界

- 同一 `Globals` 在任一时刻只允许一个线程执行；并发调用由 `Globals` 的
  `ReentrantLock executionLock` **串行化**（可重入，从 Lua 回调进来不自死锁）。
- **执行由调用者线程完成**：不为状态创建专用守护线程，也不设任务队列。
- 不存在「当前状态」这种全局概念：每个 `Globals` 自持全部状态。
- **禁止跨 `Globals` 传递可变 Lua 对象**，尝试即报错（`table belongs to another Globals`）。
- 只读常量（元方法名等）可进程级共享。
- Java 回调经 `Globals.invoke` 作显式入口 —— C 由宿主在每个 API 调用传裸
  `lua_State *`，Java 没有这个位置，故用显式入口代替。代价是同一状态的错误并发调用
  必须等待，而不是未定义行为。

### 静态可变字段的归属判据

| 可进程级共享 | 必须按 `Globals` 归属 |
|---|---|
| 不可变值、无状态库函数、短串驻留表、方法索引（`JavaMethod.cacheable` 判据分层） | 元表、`LuaClosure`、任何持上值或状态的函数 |

规则：**只有不可变值与无状态函数能跨状态共享；一旦持状态就必须唯一归属。**
迁移这类字段必须**原子**完成——弱键清扫与 GC 记账拆到两个归属域会让完整套件超时。


**仍为进程级的可变静态状态**：
参与 Lua 语义的可变静态已归属 `Globals`；剩余进程级可变静态全部列于下表，
均不产生可观察的跨状态串扰：

| 位置 | 字段 | 保持进程级的依据 |
|---|---|---|
| `LuaGC` | `luaMemoryLimitBytes`、`afterGCMem`、`allocCountLimit`、`allocFailNext` | 对齐 C 的 `l_memcontrol`（ltests 进程级单例），是测试分配器状态而非 Lua GC 的按状态状态；总量取所有登记状态之和 |
| `LuaString.Intern` | `table`（`volatile`）、`count`、`LOCK`、`QUEUE`；`LuaString.stringCount`、`initialized` | 短串不可变且不计入字节记账 ⇒ 共享不产生跨状态语义串扰。`stringCount` 进程级正是对齐 C 的 `l_memcontrol.objcount[]`。**槽位持 `SoftReference`，故不再是「永不回收」**——生命周期见「短串驻留表 append-only」一节。**并发保护见下节** |
| `LuaStates` | `active`（`volatile` 快照数组） | 进程内所有活动 `Globals` 的登记表，字符串/对象分配路径需据此取归属状态。**持 `WeakReference` 且 copy-on-write**，故不保留已被宿主丢弃的状态——见「登记表强引用 Globals」一节 |
| `IoFile` | `openHandles`（`synchronized`） | 文件系统本身是进程级资源（与 C 的 fd 表同层）。只为 `closeHandlesForName` 服务：Windows 不允许删除仍被打开的文件。**只登记打开的句柄，`close()` 摘除**——见「打开句柄登记表只增不减」一节 |
| `OsLib` | `currentLocale*`、`tmpCounter`（`AtomicLong`） | C 的 `setlocale` 本身是进程级；`os.tmpname` 计数器同理 |
| `BaseLib` | `sCwd` | 进程工作目录，C 侧同样是进程级 |
| `DebugLib`、`LuaGC.dbg_*`、`LuaFunction.functionCount` | — | 纯诊断/计算暂存，不参与 GC 决策与 Lua 可观察行为 |
| `Loggers`、`LuaRuntimeConfig` | — | Java 宿主配置，非 Lua 语义 |

## 并发同步策略
不同 `Globals` 可由不同线程真并行，进程级共享结构必须自身线程安全。
**全部避开 `synchronized`**（阻塞会 pin 虚拟线程的 carrier）：
- `LuaString.shortStrings`：`volatile` 数组 + `ReentrantLock`（仅 miss）。读路径无锁
  （表 append-only，`resize` 先填满再一次性 volatile 发布）；写路径互斥——短串靠 `==`
  判等，重复对象破坏身份恒等。
- 反射缓存（`JavaMethod.methods` 等）：`ConcurrentHashMap`（value 无状态、重复构造幂等）。
- SPI 加载 / 各单例：惰性 holder 类（类初始化线程安全且恰好一次）。
- `os.tmpname` / `LuaResources.mNextId`：`AtomicLong` / `AtomicInteger`。
- `CodeGen` float→int 折叠出参：`ThreadLocal<long[]>`（静态出参并发编译互相覆盖）。
- 适配器表写入：`Globals.runGuarded`——适配器私有 monitor 挡不住 Lua 脚本同时改同一
  张表，真正的互斥单位是 Globals 执行区。
- `LuaLog.mLogs`：`CopyOnWriteArrayList`（UI 迭代快照免 CME）；`trimIfNeeded` 用
  `removeIf` 计数删前半段（`removeAll` 会误删同内容的后半段行）。

## GC 侵入式灰链的单归属约束
`LuaValue.gclist` 是 `gray`/`grayagain`/`weak`/`allweak`/`ephemeron` 五条链共用的 next 指针，
一个对象同时只能属于一条链。由此产生两条铁律：
1. **置白必须同时脱链**：用 `LuaValue.makeWhite(cw)`，不要裸写 `gcColor = cw`。
   白对象若仍留在灰链中，下次入链会覆盖 `gclist` 而截断原链（`_G` 会脱链且永久 GRAY，
   库表不被标记而被误清扫）。
2. **`clear()` 与 `detach()` 语义不同**：`clear()` 会清掉链上每个 `gclist`（用于
   `cleargraylists` 这类真正丢弃整链的场合）；若要「只摘链头、链本身交给调用方遍历」
   （C 的 `next = g->ephemeron; g->ephemeron = NULL`），必须用 `detach()`。
   用错会使 `convergeephemerons` 每轮只处理到第一个表。

## 与 C 的差别：不要求显式关闭

C 的 `lua_State` 必须由 `lua_close` 销毁，不调是用户 bug。Java 没有析构函数，
且 Android 每个 Activity 建一个状态、销毁时只丢引用，故本项目的设计目标是
**丢引用即回收**：

- 活动状态登记表 `LuaStates` 持 `WeakReference`，注册时顺带剪除已回收条目；
- `bind/` 的进程级缓存刻意不持带 `ownerGlobals` 的值（见 `JavaObject` 对共享
  `javaMetatable` 的规避），故 luajava 不钉死状态；
- 短串驻留表槽位是 `SoftReference`，堆吃紧时可回收。

门禁 `globalsRetentionTests` 守住这条：建 12 个状态再丢弃全部强引用，须全部被 JVM
回收且登记数回落到基线。

## 唯一的例外：丢弃状态时仍有协程挂起

线程模式下每个挂起协程占一个 park 着的 Java 线程。**park 着的线程是 GC 根**，
其 `runCoroutine` 帧强持 `LuaThread`，后者经 `l_G` 强持整个 `Globals`。于是：

```text
宿主丢引用 -> 状态仍被协程线程钉住 -> GC 不会跑（没人再执行 Lua）
           -> closeFromCollector 永不被调用 -> 线程永不终止 -> 闭环
```

后果是整个状态加真实 OS 线程永久滞留；Android 当前使用平台默认原生栈，
生命周期闭环仍然存在。

这条**无法自动化**：要自动收尾就得先检测「宿主已丢弃状态」，而那要求状态变得不可达，
但线程恰恰让它永远可达。`Cleaner`/`PhantomReference` 挂在 `Globals` 上同样永不触发。

### 解法一：`Globals.close()`

```java
Globals g = Platform.standardGlobals();
try {
    g.execute(script);
} finally {
    g.close();   // 仅当可能有协程挂起时必要
}
```

`close()` 遍历 `gc.allThreads`，对每个协程线程走与 GC 路径同一份 `closeFromCollector`
（关闭上值、释放栈、唤醒并令其抛 `CloseSelf` 干净展开），摘登记表项，
并从 `LuaStates` 注销。幂等；关闭后不得再执行 Lua。

### 解法二：脚本侧收尾

协程**正常跑完**或经 `coroutine.close(co)` 收尾的状态无需 `close()`——
线程自行退出后，整个状态照常被 JVM 回收。

### 解法三：Continuation 模式

`-Dluajvm.cont=true` 结构上不为挂起协程创建线程，从根上没有这个问题。
但它依赖 JDK 内部 API（`--add-exports java.base/jdk.internal.vm`），
**Android 上不可用**，故不能作为通用解。

## 何时必须调 close()

| 场景 | 需要 close() |
|---|---|
| 只跑同步脚本，无协程 | 否 |
| 用了协程，全部跑完 | 否 |
| 用了协程，脚本侧 `coroutine.close` 收尾 | 否 |
| **丢弃状态时仍有协程处于 `suspended`** | **是** |
| 不确定脚本是否留下挂起协程 | 是（幂等，多调无害）|

Android 宿主的实践建议：在 `Activity.onDestroy` 的解引擎链路里统一调一次，
不必逐个判断脚本行为。

## 实测数据

四组同构负载，只差收尾方式（平台线程模式，对齐 Android）：

| 形态 | 状态可回收 | 线程数 |
|---|---|---|
| 挂起后直接丢弃 | 否 | +1 |
| 挂起后 `coroutine.close(co)` | 是 | 不变 |
| 挂起后 resume 到底 | 是 | 不变 |
| 挂起后 `Globals.close()` | 是 | 不变 |

后三行证明 park 线程是唯一的持有者——终止它，一切照常回收。

## 其它不需要宿主收尾的资源

逐项实测过（19 个特性各建一个状态、跑完丢引用，16 个可回收；不可回收的 3 个全是协程挂起的变体，见上节）：

- `debug.sethook` 未清除
- 带 `__gc` 的对象存活
- `io.open` 未关闭的文件（`__gc` 兜底；进程级 `openHandles` 只登记非标准流）
- `luajava.newInstance` / `bindClass`
- 弱表、to-be-closed 变量、未捕获错误、模式匹配、`load` 源码、`require`、深递归

## 已记录的行为分叉（详注在代码注释）

| 场景 | 说明 |
|---|---|
| pcall/xpcall 的 `pendingError` 会过期 | **已记录并绕开**：`__gc` 等处抛错会遗留 `pendingError`，traceback 若以它作"是否在 handler 内"的判据会读到不相干旧帧链（实测同一门禁跨轮读数漂移）。改用只在 handler 执行期间非空的 `errfuncBaseCi`/`errfuncError` |
| 分代 GC 无 minor→major 机制 | **已记录**（`LuaGC.KGC_GENMAJOR` 注释）：`setParam` 的 MINORMAJOR/MAJORMINOR 两参数因此无效 |
| hook 内 `coroutine.yield` 不支持 | **已记录**（`LuaVM.traceExec` 注释）：C 置 `CIST_HOOKYIELD` 从 hook 挂起，Java 的 hook 是同步 Java 帧，无法挂起 |
| `Globals.close()` 不跑剩余 `__gc` | **已记录**（`Globals.close` 注释）：C 的 `lua_close` 会 `callallpendingfinalizers`，宿主若靠 `__gc` 释放句柄须自行显式关闭 |
| `collectgarbage` 在收集/终结中 | **已记录**（`fullGCCaller` 注释）：C 仍执行并返回 0，Java 返回 false 且不执行 |
