# luajvm

Lua 5.5.1 C 源码的**纯 Java 移植**，同时可用于 JVM 与 Android。

- 逐文件逐函数对齐官方 Lua 5.5.1，基线为 [github.com/lua/lua](https://github.com/lua/lua) 的 [`7579fc9d`](https://github.com/lua/lua/commit/7579fc9d)
- 纯 Java、Java 21 LTS；引擎（`luajvm-core`）无运行时字节码生成、无 JNI C 后端、无 GraalVM native image（Android 平台层的 `luajava.override` 经 dexmaker 生成 dex 代理类）
- 协程兼容 ART/Dalvik
- 官方测试套件全部通过（`final OK !!!`），另有 Java API 合约、内存泄漏、分层依赖等门禁

制品发布到 [zhihulite/maven-repository](https://github.com/zhihulite/maven-repository)。

## 导入

### settings.gradle

```groovy
dependencyResolutionManagement {
    repositories {
        maven {
            name = 'zhihulite-releases'
            url = 'https://raw.githubusercontent.com/zhihulite/maven-repository/main/repository/releases'
            content {
                includeGroup 'io.github.zhihulite'
            }
        }
        google()
        mavenCentral()
    }
}
```

Kotlin DSL 写法一样，只是 `url = '...'` 换成 `url = uri("...")`。

### build.gradle

Android 工程：

```groovy
dependencies {
    // AAR 已经 api 依赖了 luajvm-core，不用再单独声明 core
    implementation 'io.github.zhihulite:luajvm-android:VERSION'
}
```

纯 JVM 工程（不要 Android 平台层）：

```groovy
implementation 'io.github.zhihulite:luajvm-core:VERSION'
```

其中 `VERSION` 是本分支的发布版本号（当前为 `1.0.0`）。

### 用 luajvm-android 必须额外放行定制 material

`luajvm-android` 依赖了 `com.google.android.material:material:VERSION`（当前为 `1.14.0`），**这是定制版**。如果不用本仓库的 material 而换回官方版本，会丢失预测性返回动画支持和相关的性能优化。

要确保用的是定制版，需要在 `content { }` 里放行：

```groovy
content {
    includeGroup 'io.github.zhihulite'
    // 只拦 material 这一个模块，其余 com.google.* 仍走 google()
    includeModule 'com.google.android.material', 'material'
}
```

用 `includeModule` 而不是 `includeGroup 'com.google.android.material'`：后者会把该组下所有模块都拦到本仓库，但本仓库只有 `material` 一个，其余的（如 `material-icons`）会解析失败。

定制版的源码和改动见 [zhihulite/material-components-android](https://github.com/zhihulite/material-components-android)。

### 版本目录写法

`gradle/libs.versions.toml`：

```toml
[versions]
luajvm = "VERSION"

[libraries]
luajvm-core = { group = "io.github.zhihulite", name = "luajvm-core", version.ref = "luajvm" }
luajvm-android = { group = "io.github.zhihulite", name = "luajvm-android", version.ref = "luajvm" }
```

### 网络连不上时

`raw.githubusercontent.com` 在某些网络环境下可能连不通，可以换镜像域名，`repository/releases` 后面的路径不变。

## 模块结构

```
luajvm/
├── luajvm-core/          # 纯 Java 内核（无 Android 依赖）
│   ├── src/main/java/org/luajvm/
│   │   ├── core/         # LuaValue/LuaTable/LuaString/LuaThread/LuaGC/Globals 等内核类型
│   │   ├── compiler/     # 5.5.1 编译器（Opcodes/Lexer/Parser/CodeGen/SyntaxNodes）
│   │   ├── lib/          # 标准库（base/string/table/math/os/io/utf8/debug/coroutine/package）
│   │   ├── vm/           # LuaVM 解释器 + FlatTFor/FlatIFor 扁平通道 + LuaChunk
│   │   ├── bind/         # Java 互操作（Coercion/JavaObject/JavaLib/反射绑定）
│   │   ├── spi/          # 可插拔 SPI（Compiler/Loader/Logger/LuaConfig/LuaJavaContext）
│   │   └── tools/        # LuacCompiler（把 .lua 预编译成 .luac）
│   ├── src/test/java/    # 官方套件 runner + Java API 合约 + 泄漏/分层门禁
│   └── src/test/resources/  # official_tests/(lua-5.5.1-tests) + luajvm_tests/ + benchmarks/
└── luajvm-android/       # Android 平台层
    ├── src/main/java/org/luajvm/android/
    │   ├── api/          # Lua 可见的宿主接口
    │   ├── engine/       # 引擎装配与生命周期
    │   ├── host/         # Activity/Fragment 宿主委托
    │   ├── lib/          # Lua 扩展（loadlayout/json/http/bitmap/thread/timer 等）
    │   ├── widget/       # 控件绑定
    │   └── net/ proxy/ runtime/ util/
    ├── src/test/         # 纯 JVM 门禁：源码扫描、字节码合约、反射签名、纯算法
    └── src/androidTest/  # 真机 APK：dexmaker 代理、设备 org.json、端到端页面
```

没有独立的门禁模块——测试代码与被测代码同模块。

用到这个引擎的宿主 App 在 [zhihulite/Hydrogen](https://github.com/zhihulite/Hydrogen)。

## 运行与测试

测试任务**自动先编译再执行，无需单独编译**，不构建 JAR：core 侧的门禁任务是手写
JavaExec/Exec；android 侧的 `testDebugUnitTest`/`connectedDebugAndroidTest` 由 AGP
装配（Gradle 标准 test 任务已禁用）。
测试分两层，按「用例读什么」选层：读源码/字节码、只测反射签名与纯算法的走纯 JVM（秒级、
无设备也能跑）；碰真实 Android API 的走真机 APK（桌面实现与设备实现行为不同，留在 JVM
层会让用例恒绿）。分层判据与逐项通过条件见 `docs/GATES.md`。

```bash
# 第一层：纯 JVM 全量门禁（含 C 行为对照；check 挂的是它）
./gradlew :luajvm-core:runCoreTests

# 第二层：真机 APK 仪器化门禁（需连接设备，前置 adb uninstall）
./gradlew :luajvm-android:runAndroidTests

# 分项
./gradlew :luajvm-core:officialTests     # 29 个官方单文件测试
./gradlew :luajvm-core:officialAllTests  # luajvm 跑 official_tests/run.lua
./gradlew :luajvm-core:lua55DebugTests   # lua55-debug 对照基准
./gradlew :luajvm-core:luajvmTests         # Java 绑定测试
./gradlew :luajvm-core:javaApiTests      # Java 公共 API 合约
./gradlew :luajvm-core:androidGates      # android 侧纯 JVM 门禁（合约 + 单测）

# 执行单个 Lua 文件
./gradlew :luajvm-core:runLuaFile --args="<lua文件路径> [超时秒]"

# LuaVM.execute 方法体大小检查（C2 HugeMethodLimit）
./gradlew :luajvm-core:checkVMExecuteMethodSize
```

完整的 Lua 对照必须先跑 debug 版 C Lua，再跑 luajvm（`lua55DebugTests` 和 `officialAllTests`），两路都必须以 `final OK !!!` 结束。

### lua55-debug 二进制

`lua55DebugTests` 需要 `build/lua55-debug/lua55-debug-5.5.1.exe` —— 由 Lua 5.5.1 的 debug/ltests 构建（同样是基线 `7579fc9d`），**不随仓库分发**，缺了这个文件该任务直接报错退出。它的 ltests.h 宏覆盖必须逐条对齐，否则行为和字节码都会不一样，判据见 `docs/GATES.md`。

## 发布制品

发布不走 HTTP 上传：往 `zhihulite/maven-repository` 的本地工作副本写文件，再由那个仓库 `git commit` + `git push` 完成分发。所以「更新包/删包」就是普通提交。

先把制品仓库 clone 到与本仓库**并排**的位置：

```
GitHub/
├── luajvm/                        # 本仓库
├── material-components-android/   # 定制 material 源码
└── maven-repository/              # 制品仓库
```

然后：

```bash
# 发布（默认写入 ../maven-repository/repository/releases）
./gradlew publish

# 换位置
./gradlew publish -PmavenRepoDir=<制品仓库路径>

# 改版本号
./gradlew publish -PluajvmVersion=VERSION

# 发 snapshot（版本号必须带 -SNAPSHOT 后缀）
./gradlew publish -PluajvmVersion=VERSION-SNAPSHOT
```

版本号带 `-SNAPSHOT` 落到 `repository/snapshots/`，否则落到 `repository/releases/`。也可以加 `-PpublishChannel=releases|snapshots` 显式指定，如果和版本号后缀对不上构建会失败 —— 只靠后缀隐式判定的话，忘了写 `-SNAPSHOT` 就会把开发版发成正式版，而正式版按约定是不该被覆盖的。

`luajvm-core` 和 `luajvm-android` 必须同版本发布：后者以 `api` 依赖前者，版本对不上会让用到的人 classpath 上出现两份不同的 core。两者的 `group`/`version` 统一在根 `build.gradle` 读取 `gradle.properties`，不在各模块单独声明。

根 `build.gradle` 的 `publishableModules` 白名单里只有 core 和 android 可以发布，不在名单里的模块一旦应用 `maven-publish` 构建会失败。两个模块的 `src/test` 里有大量门禁代码与 Lua 套件，但 test 源集不进制品（`withSourcesJar` 只打 main）。

写入完成后到制品仓库提交推送：

```bash
cd ../maven-repository
git add -A && git commit -m "release luajvm VERSION" && git push
```

## 架构要点

- **reference** 是逐函数对齐 Lua 5.5.1 C 源码的唯一语义基线，对应 commit [`7579fc9d`](https://github.com/lua/lua/commit/7579fc9d)
- **FlatTFor** 只保留了 TFOR（`pairs`/`ipairs` 泛型 for）通道 —— 端到端实测只有它有实际收益；数值 for/while/自递归等走装箱路径，行为等价由 `flatloop_equiv` 门禁把关
- **FlatIFor** 是数值 for 的扁平通道
- 不存在第二套发行 VM，也没有仍可启用的 ASM JIT、JNI 或 GraalVM 后端

### 双平台硬约束

1. 引擎（`luajvm-core`）纯 Java，禁止运行时字节码生成；Android 平台层只有 `luajava.override` 例外，经 dexmaker（`com.android.dx.stock.ProxyBuilder`）建 dex 代理类
2. 使用 Java 21 LTS，禁止 JDK 22+ 预览功能
3. 禁止 JNI 调 C 后端和 GraalVM native image
4. 协程必须兼容 ART/Dalvik
5. 所有优化必须在 JVM 和 Android 上都能编译
6. `LuaVM.execute` 方法体不能超过 C2 HugeMethodLimit（8000 字节 code_length）
7. 保真优先：`T.querytab`、`T.alloccount`、错误文本和整数边界行为必须和 C 一致

## 文档

| 文档 | 内容 |
|---|---|
| `AGENTS.md` | 项目规范唯一维护处（C 对齐、双平台约束、命名与注释、性能工作流、测试、发布） |
| `docs/GATES.md` | 当前门禁表 |
| `docs/performance.md` | 性能结论、写代码模式与禁忌、Continuation、测量纪律、泄漏排查与判据 |
| `docs/globals-lifecycle.md` | Globals 生命周期、并发语义、结构差异与静态字段归属 |
| `docs/mapping.md` | C↔Java 映射表（文件级 + 函数级 + java-only 清单，`scripts/gen-mapping.py` 生成） |
| （注释规范） | 见 AGENTS.md「注释与命名规范」 |

## License

MIT，见 [LICENSE](LICENSE)。