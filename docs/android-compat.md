# Android 兼容层（luajvm-core-android）

luajvm-core 是纯 Java 21 实现，源码直接使用超版本 JVM API（`InputStream.readAllBytes`、
`Executable` 反射、`Thread.ofVirtual` 等）。这些 API 在 Android 上最早 API 26/33/35 才有，
直接消费会在低版本设备 `NoSuchMethodError`。兼容层在**构建期**把它们改写掉，core 源码零改动。

## 三层制品

| 坐标 | 内容 | 消费方 |
|---|---|---|
| `io.github.zhihulite:luajvm-core` | 纯 Java 21 原始 core | 桌面 JVM（Hydrogen 的 luacTool 预编译等） |
| `io.github.zhihulite:luajvm-core-android` | core 源码直编（JDK 21，无 android.jar 限制）+ 构建期字节码重写 | Android（经 luajvm-android 传递） |
| `io.github.zhihulite:luajvm-android` | Android 平台层（host/布局/控件） | Android app |

依赖链只有一条边不同：`luajvm-android` 依赖 `project(':luajvm-core-android')` 而非 core，
POM 与 Gradle Module Metadata 的传递坐标天然指向兼容版，消费方零改动。

## 字节码重写（CoreRewriter，buildSrc）

jar task 对每个 core 类跑 ASM，两层处理：

1. **ClassRemapper 类型映射**：`Executable → Member`（API 26→API 1 父接口）、
   `MatchException → IllegalStateException`（构造器签名 `(String,Throwable)` API 1 即有）。
   作用于方法签名、字段、checkcast——调用点重写覆盖不到的隐式引用一网打尽。
2. **方法调用重写**（`rewrites` 表）：命中规则的调用改写为
   `JavaApiCompat.xxx` 静态调用（兼容实现只用 API 1 的 `java.*`）。
   `descContains` 字段区分同名重载（如 `toString()` / `toString(Charset)`）。
   特判：`Executable.isAccessible/setAccessible` 栈序改写（remap 后 owner 变 Member，
   接口无此方法）、`ProcessBuilder.redirectXxx` 三件套、`Redirect.INHERIT/PIPE` 字段读。

新增超版本 API 时：在 `JavaApiCompat` 加等价实现 → `rewrites` 表加规则 →
`sniffArtifact` 不过会精确报出漏配项。

## 制品级 API 门禁（sniffArtifact）

[Animal Sniffer](https://www.mojohaus.org/animal-sniffer/) 对**重写后的 jar** 做签名核对
（okhttp 等 JVM 库的行业标准做法），签名集是
[GummyBears](https://github.com/open-toast/gummy-bears) 的 Android API 24 签名包：

- 重写覆盖项已变 compat 调用（API 24 安全）→ 自然通过
- 未覆盖的超版本 API → `Undefined reference` 构建失败
- 挂在发布链上（`AbstractPublishToMaven dependsOn sniffArtifact`），不过不出仓

比 lint 强的地方：lint 查源码看不见制品层重写，且 `com.android.lint` 插件对 JVM 模块的
NewApi 检测被 `LintModelModuleJavaLibraryProject.isAndroidProject() == false` 硬编码关闭
（lint.xml / configFile / checkDependencies 均无法绕过）；签名核对查的就是最终字节码，无盲区。

### 不可达引用排除

| 类 | 排除原因 |
|---|---|
| `PackageLib` 的 nio/ProcessHandle | `isWindows()` 守卫的 Windows 死码，Android 不可达 |
| `LuaThread$1` 的 `Thread.ofVirtual` | 无 SPI 注入时的默认实现；Android 恒注入 `AndroidCoroutineThreads`，永不执行 |
| `Record.<init>` | D8 在 app 打 dex 时做 record backport（已实证：最终 APK dex 里 Record 引用为 0） |

## 协程线程分派（CoroutineThreads SPI）

core 定义 `org.luajvm.spi.CoroutineThreads` 接缝，默认实现虚拟线程（Java 21）；
`luajvm-android` 经 `META-INF/services` 注入平台线程实现（ART 无虚拟线程）。
core 不含任何平台探测（vm.name 判断等），按装配的组合点决定行为。
