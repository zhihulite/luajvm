# LuaJVM 项目门禁

## 两层测试：纯 JVM 与真机 APK

分层判据是「这个用例读什么」，不是「代码属于哪个模块」：

| 用例读什么 | 例子 | 层 | 理由 |
|---|---|---|---|
| `.java` 源码文本 | `BitmapHeaderContractTest`、`InstallDirSemanticsTest` | 只能纯 JVM | APK 里没有源码 |
| `.class` 常量池 | `LayerContractTest`/`HostContractTest`/`ThreadEntryContractTest` | 只能纯 JVM | d8 之后设备上只有 dex |
| 反射签名、纯算法 | `SyncHttpAnrContractTest`、`LuaUtilContractTest`、`FilePathResolverContractTest`、`LuaLogConcurrencyTest` | 纯 JVM | 两边都能跑，上设备零收益且慢得多 |
| 碰 Android API | `ClassProxyInstrumentedTest`（dexmaker）、`JsonInstrumentedTest`（Android 自带 org.json） | 必须 APK | 桌面实现与设备实现行为不同 |

第四类的代价是实打实的：`json` 用例曾留在纯 JVM 层依赖 Maven 的 `org.json:json`，而设备上是
Android 自带那份 —— `opt()` 对 JSON null 返回 `JSONObject.NULL` 哨兵而非 Java `null`。JVM 上
恒绿，搬到设备立刻暴露 `json.decode` 把哨兵包成 userdata、Lua 里 `t.a == nil` 为假。

## 怎么跑

测试代码与被测代码同模块，没有独立的门禁模块：

| 层 | 入口 | 代码在 | 跑在 |
|---|---|---|---|
| 纯 JVM | `:luajvm-core:runCoreTests` | `luajvm-core/src/test`、`luajvm-android/src/test` | 桌面 JVM |
| 真机 APK | `:luajvm-android:runAndroidTests` | `luajvm-android/src/androidTest` | 设备/模拟器的 ART |

```bash
./gradlew :luajvm-core:runCoreTests        # 纯 JVM 全量，check 挂的是它
./gradlew :luajvm-android:runAndroidTests  # 真机 APK，需连接设备
./gradlew :luajvm-core:androidGates        # 只跑 android 侧的纯 JVM 部分
./gradlew :luajvm-core:officialTests       # 单项
```

两条命令合起来即全量。`runCoreTests` 已含 `:luajvm-android:testDebugUnitTest`，故改了
android 源码也不会漏跑。

`luajvm-android` 按普通 Android 项目对待：`src/test` 是纯 JVM 单测、`src/androidTest` 是仪器化
测试，各自只带自己的资源。测试 APK 的 assets 只有 `apk_probe.lua` 一个文件 —— Lua 套件
（`official_tests`/`luajvm_tests`/`benchmarks`）是 `luajvm-core` 的测试资源，仪器化测试不读它们。

android 侧的四个静态门禁（`LayerContractTest`/`HostContractTest`/`ThreadEntryContractTest`/
`BaselineProfileTest`）在 `luajvm-android/src/test/.../gate/`，由 `AndroidStaticGatesTest` 经
JUnit 转发到各自 `main()`——它们零 `System.exit`、全走 `AssertionError`，故可安全包装。
找不到编译产物时自行 SKIP 并打印原因，不静默通过。

`runAndroidTests` 前置 `uninstallTestApks`（`adb uninstall`）：全新 `filesDir` 才能测到 assets
解压与 `pm clear` 语义，残留目录会让 `extractAssetsIfNeeded` 走错分支、把缺陷测成通过。
设备不在线时 `connectedDebugAndroidTest` 直接失败退出，不静默跳过。

`src/androidTest/AndroidManifest.xml` 指定 `LuaApplication` 为 Application 并声明
`LuaActivity`（主清单是空清单，库不声明组件）：宿主的 Application 即继承 `LuaApplication`，
`luajava.override` 的 dex 缓存目录、`sGlobalData`、SharedData 都挂在它上面，测试 APK 因此测的
是生产装配而非测试专用旁路。`LuaActivity` 的主题必须是 AppCompat 系，否则
`AppCompatActivity` 在 `onCreate` 抛 `IllegalStateException`。androidTest 不进 AAR。

不进自动化的两项：无障碍服务与通知监听需用户在系统设置里手动授权，`am instrument` 起不来，
只能人工验证。别把 APK 门禁全绿读成这两项也覆盖了。

## 当前门禁

| 层级 | 入口 | 通过条件 |
|---|---|---|
| 官方 Lua 测试 | `:luajvm-core:officialTests` | 配置的 29 个官方文件全部退出码 0；完整入口末尾为 `final OK !!!` |
| 内部 Lua 特性测试 | `:luajvm-core:luajvmTests` | 单进程执行 `luajvm_tests/run.lua` 聚合入口（不扫描目录）：入口内硬编码的 25 个用例文件依次 `dofile`，失败不中断但最终 `error` 收尾，进程退出码须为 0 |
| Java API 合约测试 | `:luajvm-core:javaApiTests` | 公共 Java API 合约进程退出码 0；含 userdata 的 Class 判型三件套一致性（`isuserdata(Class)`/`touserdata(Class)`/`optuserdata(Class,d)` 同判据，桩恒 false 会使 loadlayout 构造器缓存、src Drawable 分支、typeface、getter CharSequence 转字符串全部失效） |
| chunk dump/load | `:luajvm-core:chunkTests` | 13 项：round-trip、内存流 load（识别 `0x1B` 前缀）、strip 后更小且仍可执行、签名 `\x1bLua` 与 `LUAC_VERSION = 0x55`。Hydrogen 的 `-PluaMode=luac` 正走 undump，`javaApiTests` 不覆盖这条路径 |
| Continuation 协程语义 | `:luajvm-core:contModeTests` | 五项：yield/resume、嵌套协程 6 条追踪、错误传播、挂起态 Continuation 可被 GC 回收（残留 0/10）、状态机转移。用例在模式未启用时打印 SKIP 并返回 0，故任务捕获输出、见 SKIP 即 FAIL —— 判别力已对拍：删掉 `jvmArgs` 即 `SKIP` 并中止 |
| Java 方法包装按需构建 | `:luajvm-core:methodGroupCacheTests` | 构造后只缓存 `new`；实际方法名按需加入；2000 个缺失键不写 `methodMap`，负缓存 ≤ 64；构造器、重载与返回值语义正常 |
| 短串驻留可回收 | `:luajvm-core:internPressureTests` | `-Xmx256m` 下生成 40 万互异短串再压到 OOM，purge 后计数须回落（判据取 `SoftReference` 的「抛 OOM 前必被清除」保证） |
| 类初始化顺序 | `:luajvm-core:classInitOrderTests` | 以 `LuaString.newStr` 作为首个 luajvm 调用不得 NPE，且 clinit 重入期 intern 的串身份须与后续一致 |
| C 行为对照 | `lua55-debug run.lua` | debug 版输出成功；禁止使用 release Lua |
| Java 行为对照 | `luajvm RunLuaFile run.lua` | 与 C 输出和退出状态一致，末尾为 `final OK !!!` |
| Android 编译 | `:luajvm-android:compileDebugJavaWithJavac`、`:luajvm-android:assembleRelease` | 全部成功；真机运行另行记录 |
| Android API 面 ≤ minSdk | `:luajvm-android:lintRelease`（挂在 `AbstractPublishToMaven`，发布必跑） | `NewApi` 零命中。javac 用 compileSdk 37 的 android.jar 编译，超过 `minSdk 24` 的调用一律放行，只有 lint 能判；`NewApi` 是 AGP 默认 fatal issue，命中即 `lintRelease FAILED` 且 publish 不执行。判别力已对拍：把 `HttpCore.urlEncode` 换回 API 33 的 `URLEncoder.encode(String, Charset)` 即 1 error 中止发布 |
| android 侧合约门禁 | `:luajvm-core:androidGates`（手动别名；已含于 runCoreTests） | layer/host/threadEntry/baselineProfile 四个合约门禁 + `testDebugUnitTest` 全过 |
| Android 纯 JVM 单元门禁 | `:luajvm-android:testDebugUnitTest`（已接入 `androidGates`） | 13 个测试类、共 55 项全绿（只含静态检查：源码扫描、反射签名、纯算法）。碰 Android API 的用例不得放这里 |
| Android 仪器化门禁 | `:luajvm-android:runAndroidTests`（手动，需设备） | `src/androidTest` 全绿，当前 5 类共 40 项 |
| 运行期性能 | `benchmarks/runtime_app.lua`（手动，需 C 侧对照） | 真实应用形态 4 场景；S1 元表 OOP 已反超 C，S3/S4 仍有差距。`BENCH_ITERS` 钉死迭代数，各配置隔离进程重复取中位 |
| LuaVM.execute 方法大小 | `:luajvm-core:checkVMExecuteMethodSize`（手动） | `LuaVM.execute` 的 `code_length` ≤ 8000 字节（C2 `HugeMethodLimit`）。越界会让整个 `execute` 退回解释执行、造成数量级回归，而它是全引擎唯一逼近该限制的方法（实测 6876、余量 1124；第二名 603）。须用 class parser 取 Code attribute —— `javap` 末偏移不是 `code_length` |

官方 29 文件全部纳入门禁；优先回归：`calls.lua`、`db.lua`、`locals.lua`、`coroutine.lua`、`pm.lua`。
