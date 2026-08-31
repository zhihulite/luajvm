# LuaJVM 项目规范

> 本文件是项目规范的**唯一维护处**；`CLAUDE.md` 只是指向这里的入口，不重复内容。

## 项目概述

LuaJVM 是 Lua 5.5.1 C 源码的纯 Java 移植，官方测试套件全部通过。当前发行实现：

1. **reference 执行路径**：逐函数、逐控制流对齐 Lua 5.5.1 C 源码，是语义基线。
2. FlatTFor 只保留 TFOR（pairs/ipairs 泛型 for）通道 —— 端到端实测只有它有真实收益
   （pairs 密集负载反超 C）；数值 for 走 FlatIFor，其余循环形态走装箱路径，
   行为等价由 flatloop_equiv 门禁把关。

文档与说明不得声称不存在的能力（运行时字节码生成、C 后端等，见双平台兼容约束）。

## 双平台兼容约束

所有代码必须同时兼容 JVM 和 Android（ART/Dalvik）：

1. 纯 Java，禁止 ASM、CGLIB、Javassist 等运行时字节码生成。
2. 禁止 JDK 22+ 预览特性；使用稳定 Java 21 LTS 能力。
3. 禁止 JNI 调用 C 的 `luaV_execute`。
4. 禁止 GraalVM native image 作为发行实现。
5. 协程必须兼容 Android；虚拟线程只能作为 HotSpot 可用时的可选实现。
6. 性能改动必须同时保持 JVM 和 Android 可编译，并通过行为门禁。
7. **luajvm-android 的 API 面不得超过 `minSdk 24`。** `compileSdk 37` 的 android.jar 把
   API 25..37 的符号一并交给 javac，而 javac 不看 minSdk，写成新 API 照样编过（`URLEncoder.encode(String, Charset)`
   这类 API 33 重载在 24..32 的设备上是运行期 `NoSuchMethodError`）。判据只有 Android lint 的
   `NewApi`（AGP 默认即 fatal，无需在 `lint {}` 里声明），`lintRelease` 已挂到发布路径
   （`AbstractPublishToMaven`）⇒ 制品出仓前必跑一次。改 android 侧代码后自查一条：

   ```powershell
   .\gradlew.bat :luajvm-android:lintRelease
   ```

## C 源码基准

- 唯一参考：Lua 5.5.1 C 源码（[github.com/lua/lua](https://github.com/lua/lua)，基线 commit `7579fc9d`）。
- 不照抄 luaj 等既有 Java 实现来判断 Lua 语义。
- C 行为与性能对照必须使用 `lua55-debug`，不得用 release 版 Lua 替代。
- **行为对照用 `lua55-debug`（ltests 构建），字节码/dump 对照必须用 vanilla 构建** ——
  ltests 的 `MAXINDEXRK=1` 等宏会改变编译产物（`GETFIELD`/`SETFIELD` 与寄存器编码的取舍），
  两种对照不可混用。
- 热路径（`execute`/`findShortString`/`hashGet`/`resize`/`format` 快路径）的写法不对齐 C
  （HOT-EXEMPT，A/B 依据在代码注释与 `docs/performance.md`），只核语义。
- `*_align.lua` 差分门禁的期望值一律以 `lua55-debug` 实测为准，不得转述 ——
  转述会骗人。

## 命名与函数结构

1. C 中每个独立函数，Java 中也必须有独立对应函数，不得为了简化而合并。
2. 参数、参数顺序、局部变量名和控制流尽量与 C 源码保持一致。
3. C 调用哪个子函数，Java 必须调用对应实现，不能用手写等价逻辑替代。
4. C 中的宏和工具函数也必须翻译为 Java 方法，不能无说明地省略或内联。
5. Java 方法名可采用项目现有风格，例如 `luaG_runerror` 映射为 `runError`。
6. Java 语言限制导致无法一一对应时，必须在文档中记录原因、映射关系、实际特性、优点和缺点。

## 注释与命名规范

### 三条禁令
1. **禁止"为什么不这么做"论证**：不写"用 A 而非 B，因为 B 会…"的替代方案对比
   （例："直接继承 SoftReference 而非持有一个 Reference 字段：后者每次要分配两个对象"）。
   只写当前设计是什么、机制上为什么需要。
2. **禁止历史叙事**：不写"旧版/曾经/原实现是 X"、"曾尝试 Y 净损失"、"已随裁剪删除"
   等演变故事。注释只描述代码现状。
3. **禁止 § 类章节符号**：注释内引用文档不用 `§`、`§N` 等符号；需要指路时直接写
   文件名与章节标题文字（如"见 docs/performance.md 的『新方向的准入条件』一节"），
   或干脆删去指引只留结论。

### 目标

- 代码与 C 源码的**映射可追溯**：每个 Java 方法/关键字段标注对应 C 函数；
  全量映射表见 `docs/mapping.md`（由 `scripts/gen-mapping.py` 生成，不手改）
- **保留所有 `java diff:` / `java-only:` 差异说明**——它们记录引擎与 C 的行为差异，是排障的根因线索
- 删除冗余解释与"AI 味"的长篇叙述，让注释**信息密度高、一眼看懂**

### 文件头（必须，按类区分）

| 代码类型 | 文件头要求 |
|---|---|
| **翻译自 C 的类**（core/lib/compiler/vm/ops 等，有 C 对应） | `// ref: <C源文件>` + `// diff: <一句话差异总结>`，位于 package 之前 |
| **core / tests 中 Java 独有类**（无 C 对应，如 bind/spi/FlatArith/FlatTFor/测试 Runner） | `// java-only: <一句话说明>`——**必须写** |
| **android 模块** | **无需**任何文件头要求（平台绑定代码） |

要求：
- **ref**：列该文件对齐的 C 源文件（`lvm.c`、`lstrlib.c`…）
- **diff**：概括类级差异——数据结构替代（数组 vs 指针）、API 形态（OO 子类 vs 联合体）、行为取舍（异常 vs 错误码）。只写差异，不写翻译过程
- 长 diff 可换行，每行以 `// diff: ` 开头，总长 ≤3 行
- **只有翻译代码写 ref/diff；Java 独有代码不要硬编 ref/diff，标 java-only 即可**

### 保留（必须）

| 类别 | 示例 | 说明 |
|---|---|---|
| **C 函数映射** | `// lgc.c: luaC_barrier` | 格式 `C文件名: C函数名`，**无行号**；只有行号无函数名的，按行号查 C 源码补函数名 |
| **java diff** | `// java diff: C 用 bitmask，Java 用 0/1` | 引擎与 C 的**行为差异**——保留，可精简措辞，**不删除** |
| **java-only** | `// java-only: 分配触发 GC 参数` | Java 独有实现/说明——**不删除**（含裸标记行） |
| 字段/方法必要用途 | `// java-only: debug flag for GC tracing` | 一行点明用途，字段名不足以自明时 |
| 系统属性/A-B 开关 | `// -Dluajvm.allocfast=true|false` | 防止后人改回/误删优化开关 |
| bug 修复关键原因 | `// 长串须受 barrier 保护，否则 sweep 误回收` | 1-2 行说明"为什么这样写"，省略推理过程 |

### 删除（"太 AI"的冗余）

| 类别 | 示例 | 处理 |
|---|---|---|
| **行号** | `lgc.c:342-345`、`lgc.h:245` | 去掉数字，保留 `C文件名: C函数名` |
| **A/B 测试数据** | `JFR 1325 samples`、`512KB median 3.50s vs 256KB 3.59s`、`(4x)` | 删数字，保留结论（如"批量摊薄 dispatch 开销"） |
| **commit hash 引用** | `C（2a7cf4f3）加…`、`commit dee6c682` | **不写任何 commit hash 分支引用**——行为差异直接用 `// java diff:` 描述，不需要溯源 commit |
| **重复说明** | gray 链表在类级与方法级各写一遍 | 只保留一处 |
| **推理过程/故事** | `这导致...否则...所以...（长链）`、`memerr.lua 死循环是因为...` | 压缩为 1 句结论（bug 修复原因本身可留） |
| **显而易见的废话** | `// returns true when...`（函数名已说明） | 删 |
| **长注释块**（>3 行） | 保留首行（C 映射/主题）+ java diff/java-only 行，其余删 | 压缩到 ≤3 行 |

### 格式约定

- 映射注释：`// <C文件名>: <C函数名> — <简短说明>`（`—` 后说明可选）
- 差异注释：`// java diff: <一句话差异>`
- 独有注释：`// java-only: <一句话说明>`
- 单个注释块（含连续多行）**不超过 3 行**；超过的按"删除"标准精简
- 注释语言中英皆可，优先中文（团队惯例），C 函数名保持英文

### Java 书写规范

1. **禁止内联全限定类名**：除 `package`/`import` 语句外，代码不得直接写
   `org.luajvm.core.LuaTable`、`java.math.BigDecimal` 这类全限定名；
   统一在文件头 import，调用处写短名（`new Foo(`、`Foo.bar(`，泛型与参数类型同理）。
2. 例外只有三种：字符串字面量里的类名（反射目标、常量池扫描串，改了就找不到目标）；
   同名类冲突必须消歧时（`android.R` 与业务 `R` 的 ID 表命名空间属此类，可保留全限定）；
   就地一行注释说明原因。
3. javadoc 的 `{@link}` 同理优先短名，类已 import 时不写全限定链接。

## luajvm-android：Lua 可见面的改名硬约束

改任何类名/方法名/字段名前，先想 Lua 脚本是否以字符串引用它。业务 Lua（Hydrogen 的
`assets/`，586 个文件）经全量 grep 对 `org.luajvm` 的引用恰好 8 处、全在 4 个适配器；
`AndroidManifest.xml` 与 `res/**/*.xml` 零命中 ⇒ XML 侧对类名零约束。约束只来自
Lua 的 `import`/`bindClass`/`createProxy` 字符串，与宿主 app 的 Java `extends`。

| 类别 | 具体名字 | 为何禁改 |
|---|---|---|
| 类名（Lua 字符串硬编码） | `LuaCustRecyclerAdapter`、`LuaListItemAdapter`、`LuaPagerAdapter`、`LuaPager2Adapter` 及嵌套 `$Creator`／`$…Holder` | `import` + `luajava.createProxy("...$Creator")` |
| Creator 回调 | `getItemCount`、`getItemViewType`、`onCreateViewHolder`、`onBindViewHolder`、`onViewRecycled` | Lua 表实现、Java 反向调用 |
| Holder 访问器 | `getViews`／`setViews` | Lua 写 `holder.views`，属性式访问 |
| 适配器方法 | `LuaPagerAdapter.add`／`set`、`LuaPager2Adapter.add` | Lua 直接调 |
| `HttpResult` 字段 | `code`、`text`、`bytes`、`headers` | `public final` 字段，改名后 Lua 静默拿 `nil` |
| 宿主属性 | `getRootDir`、`setDebug`、`getWidth`、`setContentView`、`getSharedData`、`setSharedData` | `activity.rootDir` 等属性式访问 |
| 字符串键 | `LuaLayout` 全部属性键（`id` 单独出现 700 处）、`loadmenu` 键名、`json.encode/decode` | 键名即 API |

判据是「Lua 走访问器还是走字段」，不是「名字看着像不像公开 API」：`LuaCustRecyclerHolder`
的字段名 `Tag` 因 Lua 全程只走 `views` 访问器而可改；`getViews` 不可改。
同理，**零调用不构成死代码证据**——Lua 侧桥接的引用本仓 grep 不可见，
处置规则见「重构与审计」第 5 条。

## 宏与动态配置

1. C 源码中通过 `#define` 暴露的可配置项必须映射为运行时配置。
2. 配置必须有与 C 默认值一致的默认行为，并记录取值范围、线程可见性和修改时机。
3. 文档必须比较 Java 运行时配置与 C 编译期配置的适用场景、优点和风险。
4. VM 语义常量不得伪装成可配置项；若 C 常量不能安全动态修改，必须记录原因和约束。

## 重构与审计

1. 每个 Java 文件和内部函数都必须逐一对照 C 源码审计，不得遗漏宏、工具函数或冷路径。
2. 允许解耦 Java 特有的平台层，但最终可观察行为必须符合 C 源码。
3. Java 文件内部若存在重复的 Java-only 功能，可重构为共享实现；不得合并 C 中原本独立的函数。
4. 所有重构、解耦和不一致点必须写入文档，并比较两种方案在不同场景下的优缺点。
5. **零调用不是删除依据**：本库是发布制品（`io.github.zhihulite`），使用方可在 Lua 侧经
   `luajava.bindClass` / `createProxy` / 属性式访问 / 反射桥接任意 public 成员——这些引用
   不在本仓任何 grep 范围内。零调用的 public 类/方法/字段一律保留，需要时在注释标注
   "零调用，可能经 Lua 桥接使用"；删除属破坏性 API 变更，须经发布契约评审，不以代码搜索为判据。

## 性能工作流：先证收益，再写实现

生产代码改动前必须先完成以下步骤：

1. 定义真实目标工作负载、基线命令、主指标和允许误差。
2. 用现有代码运行自动化基线，并记录隔离进程 wall-time。
3. 用覆盖计数、字节码检查或 JFR 证明目标路径确实是热点；JFR 只用于定位，不用于验收。
4. 先用独立最小实验验证关键假设和收益上限。无法证明命中率或理论收益时停止，不进入完整实现。
5. 写好会检测回归的自动化测试和 A/B 命令后，才允许修改生产路径。
6. 实现后使用同一 class 输出、同一参数、独立进程 best-of-N 对比；不比较不同 JAR，不用 `git stash` 切基线。
7. 只有行为完全一致且 wall-time 稳定不回归的改动才能保留。

完整规则见 `docs/performance.md`。

**编写新代码时的性能要求**（不限于性能改动）：
- 新增 Lua 库函数若属高频调用，必须 override `LuaFunction.callOnStack`（免 Varargs 往返）；
  引擎内部调用 Lua 函数用 `LuaCall.callOnStack1to1/2to1/3to1`。
- 新增 Java 绑定（方法/构造器/字段）必须用 `InvokeSupport.spreaderFor*` 预 asSpreader，
  **禁止**在调用路径上用 `invokeWithArguments`（每次 LambdaForm 适配，远慢于直调）。
- 参数/返回值转换统一走 `Coercion`（Adapter 构造时缓存），热路径不查表。
- 已证伪方向（小整数缓存、strCache、热方法加分支等）不得重复尝试。
- 完整清单见 `docs/performance.md`。

## 编译与自动测试

测试任务**自动先编译再执行，无需单独编译**，不构建 JAR：core 侧门禁任务是手写
JavaExec/Exec（`test { enabled = false }`，Gradle 标准 test 不用）；android 侧的
`testDebugUnitTest`/`connectedDebugAndroidTest` 由 AGP 装配。
验证编译与测试**只使用 gradle 任务**（含 Android 侧：`:luajvm-android:compileDebugJavaWithJavac`、
`:luajvm-android:assembleRelease`），不得用 javac/自拼 classpath 做门禁替代。
宿主 App 在 zhihulite/Hydrogen，本仓库不含可打包的 application 模块——仪器化测试的 APK
由 AGP 从 `luajvm-android/src/androidTest` 生成（自 instrument），不是 application 模块。

**测试代码与被测代码同模块**，没有独立门禁模块：
core 的测试在 `luajvm-core/src/test`，android 的纯 JVM 测试在 `luajvm-android/src/test`，
需要设备的在 `luajvm-android/src/androidTest`。

**分两层，判据是「这个用例读什么」而不是「代码属于哪个模块」**：读 `.java` 源码或
`.class` 常量池的只能留纯 JVM（APK 里没有源码，d8 后没有 class）；只测反射签名与纯算法的
留纯 JVM（上设备零收益）；**碰真实 Android API 的必须上 APK**——桌面实现与设备实现行为
不同，留在 JVM 层是负价值，它会让用例恒绿并掩盖缺陷。分层表与逐项判据见 `docs/GATES.md`。

```powershell
.\gradlew.bat :luajvm-core:runCoreTests       # 第一层：纯 JVM 全量（check 挂的是它）
.\gradlew.bat :luajvm-android:runAndroidTests # 第二层：真机 APK（需连接设备）
```

两条合起来即全量。`runCoreTests` 已含 `:luajvm-android:testDebugUnitTest`，零漏跑：
android 源码未改动时 compile/单测均 UP-TO-DATE（近零成本）；改了 android 源码时
这些成本本就是必须支付的验证。
只跑 android 侧的纯 JVM 部分用手动别名：

```powershell
.\gradlew.bat :luajvm-core:androidGates
```

`runAndroidTests` 前置 `adb uninstall`：全新 `filesDir` 才能测到 assets 解压与 `pm clear`
语义。设备不在线时直接失败退出，**不得改成静默跳过**——那等于门禁空转。

APK 层含一个端到端页面测试：真实 `LuaActivity` 跑 `androidTest/assets/apk_probe.lua`，
页面逐项 `pcall` 把结果记进 `RESULT`，Java 侧读表断言。新增覆盖项就往那个页面加一条 `probe`。
**读 `RESULT` 必须判「是布尔 true」而不是真值**：失败项存的是错误字符串，而 Lua 里非空
字符串为真，用 `toboolean()` 会把失败读成通过。无障碍服务与通知监听需系统设置手动授权，
`am instrument` 起不来，那两项只能人工验证。

可分别运行：

```powershell
.\gradlew.bat :luajvm-core:officialTests     # 29 个官方单文件测试（official_tests/run.lua 聚合入口）
.\gradlew.bat :luajvm-core:officialAllTests  # luajvm 跑 official_tests/run.lua（RunLuaFile）
.\gradlew.bat :luajvm-core:lua55DebugTests   # 用 lua55-debug 跑同一聚合入口（对照基准）
.\gradlew.bat :luajvm-core:luajvmTests         # luajvm_tests/run.lua 聚合入口（Java 绑定测试）
.\gradlew.bat :luajvm-core:javaApiTests      # Java 公共 API 合约测试
```

执行任意单个 Lua 文件（替代手动 `java -cp`）：

```powershell
.\gradlew.bat :luajvm-core:runLuaFile --args="<lua文件路径> [超时秒]"
```

强制对照顺序：

1. 使用 **`lua55-debug` 在 debug 模式下运行全部 Lua 5.5 测试**。
2. 使用 **luajvm 运行同一批测试**，比较输出和退出状态。
3. 完整套件入口为 `luajvm-core\src\test\resources\official_tests\run.lua`，成功标志为 `final OK !!!`。
4. 性能或 VM 改动后运行 `:luajvm-core:checkVMExecuteMethodSize`（直接解析 class 二进制，各方法 code_length ≤ 8000 字节；当前 `LuaVM.execute` 余量见 `docs/GATES.md`），验证方法见 `docs/performance.md`。

优先回归：`calls.lua`、`db.lua`、`locals.lua`、`coroutine.lua`、`pm.lua` 和完整套件。

`lua55DebugTests` 需要 `build/lua55-debug/lua55-debug-5.5.1.exe`，不随仓库分发。
缺失时该任务直接报错退出，`runCoreTests` 就地终止、其后门禁根本不跑 ——
**别把「只有 5 个 PASS」读成通过**。

保真优先：`T.querytab`、`T.alloccount`、错误文本和整数边界行为必须与 C 一致；
任何优化不得以牺牲这些可观察行为为代价。

## 制品发布

- 只有 `luajvm-core` 与 `luajvm-android` 发布，坐标 `io.github.zhihulite`，
  版本统一在 `gradle.properties` 的 `luajvmVersion`。
- 只有 core 与 android 可发布：根 `build.gradle` 的 `publishableModules` 白名单未列名的模块
  一旦应用 `maven-publish` 即构建失败。
- 发布目标是 `zhihulite/maven-repository` 的本地工作副本（`mavenRepoDir`，默认取同级目录），
  写文件后由那个仓库 git push 分发。
- 宿主 App 在 zhihulite/Hydrogen，本仓库无 application 模块。

## 工作流程

1. 对照 C 源码确认问题或差异。
2. 为行为改动建立对照测试；为性能改动先建立基线和收益门槛。
3. 实现最小范围改动。
4. 编译并运行自动测试。
5. 使用 `lua55-debug` 和 luajvm 对同一测试做最终对照。
6. 更新 `docs/GATES.md`；性能与排查工作更新 `docs/performance.md`，宿主契约更新 `docs/globals-lifecycle.md`。
7. 仅在用户明确要求时提交，且精确 stage 本次文件，不使用 `git add .`。

## 已知差异

| 项目 | 状态 | 说明 |
|---|---|---|
| memerr.lua `testbytes` | 架构差异 | C 立即记账释放，Java 在 GC sweep 时记账；功能测试通过 |
| reference 相对 C-debug 的速度 | 结构性差距 | 解释器分派、对象值模型、GC 和调用约定共同造成；见 `docs/performance.md` |

已修复缺陷的关键判据与泄漏探针方法论见 `docs/performance.md` 的「泄漏排查与判据」节。

## 文档目录

- `docs/GATES.md`：当前门禁表（唯一权威）。
- `docs/performance.md`：性能的五个切面——结论（已验证/已证伪）、写代码的模式与禁忌、
  Continuation 模式、测量纪律、泄漏排查与判据。
- `docs/globals-lifecycle.md`：宿主向的 `Globals` 生命周期契约（何时必须 `close()`）。
- `docs/mapping.md`：C ↔ Java 映射表（文件级 + 函数级 + java-only 清单），由
  `scripts/gen-mapping.py` 扫描代码注释生成，**不手改**；改注释口径后重跑脚本。

注释规范在本文「注释与命名规范」一节。

## 已确认的性能边界

1. 当前主线是 reference + FlatTFor（TFOR 迭代优化），不再维护第二套通用 VM。
2. 微基准反超 C 不代表完整套件提速；必须以端到端 wall-time 判定。
3. 降分配不等于降 wall-time；JVM C2 可能消除短命对象。
4. `execute`、`findShortString`、`resize` 等热方法新增分支会造成稳定回归。
5. ASM JIT、JNI、GraalVM 和 Valhalla 不符合 Android 兼容约束。
6. 没有新的自动化证据时，不重新实施已失败的原始值 sidecar、替代解释循环或字节码 JIT。