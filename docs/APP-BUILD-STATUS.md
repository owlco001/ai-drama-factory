# 阶段 0 · `:app` 模块构建/测试验证报告

> 接手评估配套文档。本文记录 **Android SDK 无头安装** 与 **`:app` 模块真实构建/测试结果**。
> 前置：同目录 `BUILD-STATUS.md` 已记录 `:core-engine:test` = 135 通过 / 0 失败（JDK 17.0.20.1）。
> 本次为**只读验证**：未修改任何项目源码或构建脚本；唯一新增物为本报告与 SDK 根目录下的 `sdkmanager.sh` 辅助脚本。

---

## 1. 环境

| 项 | 值 |
|---|---|
| 操作系统 | Windows (git-bash / MSYS2 shell) |
| JDK | Microsoft OpenJDK 17.0.20.1 (`C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`) |
| 项目路径 | `C:\Users\owlco\WorkBuddy\2026-08-28-18-36-06\ai-drama-factory\src-ai-drama` |
| Gradle | 8.7 (wrapper `gradle-8.7-bin.zip`，首次运行自动下载) |
| AGP / Kotlin | `com.android.application` 8.5.2 / Kotlin 2.0.21 |
| compileSdk / minSdk / targetSdk | 34 / 29 / 34 |

---

## 2. Android SDK 无头安装

未安装 Android Studio（>1GB GUI）。采用 commandline-tools 方式安装。

### 2.1 安装路径与组件

- SDK 根目录：`C:\Users\owlco\Android\Sdk`
- commandline-tools：下载 `commandlinetools-win-11076708_latest.zip`（153 MB）→ 解压到
  `C:\Users\owlco\Android\Sdk\cmdline-tools\latest\`（层级：`latest\bin\sdkmanager.bat` 等）
- 通过 `sdkmanager`（直接 `java` 调用，见 §2.3）安装的组件：

| 组件 | 版本 | 校验 |
|---|---|---|
| platform-tools | r37.0.1 (`adb 1.0.41`) | `Sdk\platform-tools\adb.exe` 存在 |
| platforms | android-34 | `Sdk\platforms\android-34` 存在 |
| build-tools | 34.0.0 | `Sdk\build-tools\34.0.0` 存在 |
| licenses | — | `Sdk\licenses\` 含 `android-sdk-license` 等 7 个 |

### 2.2 `ANDROID_HOME` 配置方式（持久化）

- 通过 PowerShell（用户作用域）持久化：
  - `[Environment]::SetEnvironmentVariable("ANDROID_HOME","C:\Users\owlco\Android\Sdk","User")`
  - 并把 `C:\Users\owlco\Android\Sdk\cmdline-tools\latest\bin` 与
    `C:\Users\owlco\Android\Sdk\platform-tools` 追加进用户 `Path`。
- 回读确认：`ANDROID_HOME = C:\Users\owlco\Android\Sdk`；PATH 两项均存在。
- 注意：本机的 Bash 调用 **不跨 shell 继承环境变量**，因此每次 Gradle/sdkmanager 调用都在命令内显式
  `export ANDROID_HOME=...; export JAVA_HOME=...; export PATH=...`。持久化仅为给后续人工/CI 会话用。

### 2.3 sdkmanager 调用方式（关键坑）

git-bash **无法直接执行 `.bat` 启动器**（`sdkmanager: command not found`），且 `java` 对 `/c/...` 形式的
classpath 路径不解析。最终用 **Java 直调** 方式（Windows 风格 `C:/...` 路径 + 显式 classpath）：

```
java -Dcom.android.sdklib.toolsdir=C:/Users/owlco/Android/Sdk/cmdline-tools/latest \
     -classpath "<lib/sdkmanager-classpath.jar>;<依赖 jars...>" \
     com.android.sdklib.tool.sdkmanager.SdkManagerCli <args>
```

已封装为 `C:\Users\owlco\Android\sdkmanager.sh`（非项目文件，仅本机工具），后续可：
`bash /c/Users/owlco/Android/sdkmanager.sh "platform-tools" ...`

---

## 3. `:app:testDebugUnitTest` 结果

**命令**（项目根目录执行）：
```
./gradlew :app:testDebugUnitTest --console=plain --no-daemon
```

**结果：BUILD FAILED（测试失败导致），总耗时 ~2m31s**

| 指标 | 数量 |
|---|---|
| 测试总数 (tests completed) | **67** |
| 通过 (passed) | 66 |
| 失败 (failed) | **1** |
| 错误 (errors) | 0 |
| 跳过 (skipped) | 0 |

> 测试数与架构师在 `HANDOVER.md` 中统计的 **67** 条一致。

### 3.1 失败用例（唯一）

```
com.dramafactory.app.Round6LocalUploadI2iTest > 图生视频_双帧_组装keyframes   FAILED
```

**根因（来自 `app/build/test-results/.../TEST-...Round6LocalUploadI2iTest.xml`）：**
```
kotlinx.coroutines.test.UncaughtExceptionsBeforeTest
  ...
  Caused by: java.lang.IllegalStateException:
      Module with the Main dispatcher had failed to initialize.
      For tests Dispatchers.setMain from kotlinx-coroutines-test module can be used
  Caused by: java.lang.IllegalStateException: The main looper is not available
      at kotlinx.coroutines.android.AndroidDispatcherFactory.createDispatcher(HandlerDispatcher.kt:51)
```

**判读（诚实）：** 该用例在单元测试 JVM 上启动了跑在 `Dispatchers.Main`（Android 主线程调度器）上的协程，
但单元测试环境没有 Android 主 Looper，且该测试未用 `kotlinx-coroutines-test` 的 `Dispatchers.setMain(...)`
替换主调度器 → 初始化失败。这是**测试脚手架/调度器装配缺陷**，**不是 6 处 P0 功能缺陷之一**。
其余 66 个用例（含质量审计、时代红线、retry、四闸门等 P0 相关逻辑）全部通过。

---

## 4. `:app:assembleDebug` 结果

**命令**（项目根目录执行）：
```
./gradlew :app:assembleDebug --console=plain --no-daemon
```

**结果：BUILD SUCCESSFUL，总耗时 ~44s**

| 项 | 值 |
|---|---|
| 构建状态 | ✅ BUILD SUCCESSFUL |
| APK 路径 | `src-ai-drama\app\build\outputs\apk\debug\app-debug.apk` |
| APK 大小 | **34,090,653 字节（≈ 32.5 MB）** |
| 退出码 | 0 |

### 4.1 关于 HANDOVER 标注的两类已知风险（实测）

- **P2-3 `composeOptions.kotlinCompilerExtensionVersion="1.5.15"` 冲突**：
  **未触发。** 当前 `app/build.gradle.kts` **没有** `composeOptions { kotlinCompilerExtensionVersion = ... }` 块
  （第 24 行实为 `packaging { resources.excludes += "META-INF/*" }`）。项目采用的是 Kotlin 2.0 标准做法——
  `plugins { id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" }`，compose 编译器由该插件自动处理。
  构建日志中**无任何** composeOptions / kotlinCompilerExtensionVersion 告警。
  （HANDOVER.md 中该风险描述可能基于更早版本，本仓库当前代码已规避。）

- **P1-6 ffmpeg-kit jitpack 依赖解析**：
  **解析成功，无报错。** `implementation("com.github.arthenica:ffmpeg-kit:5.1")` 从 jitpack.io 正常下载
  （纯 Java 壳 AAR，约 54KB）。构建日志中无依赖解析失败。

### 4.2 APK 内原生库（关键诚实发现）

解包 `app-debug.apk` 检查 `lib/arm64-v8a`：

| .so | 是否存在 |
|---|---|
| `libandroidx.graphics.path.so` | ✅ 有（Jetpack 图形库，与 ffmpeg 无关） |
| `libffmpegkit.so` / `libavcodec*.so` / `libavutil*.so` 等 ffmpeg 原生库 | ❌ **全部缺失（0 个）** |

> **结论**：APK 内**没有任何 ffmpeg 原生库**（与上一轮结论一致：jitpack `ffmpeg-kit:5.1` 是 0 个 `.so` 的纯 Java 壳）。
> 因此 `assembleDebug` 虽变绿，但 App 在真机首次调用 `FFmpegKit`/`FFmpegKit.loadLibrary()` 做视频合成时会
> **抛异常崩溃**。这是 P1-6 的运行时风险，构建期无法暴露。

构建期唯一告警（非错误）：
`stripDebugDebugSymbols: Unable to strip the following libraries ... libandroidx.graphics.path.so`
—— 仅未能 strip 该 Jetpack .so，不影响产物。

---

## 5. 诚实声明 / 已验证·未验证清单

### ✅ 已验证（本机真实执行）
- [x] Android SDK 无头安装成功（commandline-tools + platform-tools + platforms;android-34 + build-tools;34.0.0 + licenses）
- [x] `ANDROID_HOME` 配置并可被 Gradle 识别（SDK location 不再报 not found）
- [x] `:app:testDebugUnitTest` 真实跑通：67 用例，66 通过 / **1 失败** / 0 错误 / 0 跳过
- [x] `:app:assembleDebug` 真实产出 APK（34.1 MB），BUILD SUCCESSFUL
- [x] composeOptions 冲突风险**未触发**（当前代码已规避）
- [x] ffmpeg 依赖**解析成功**但 APK **缺 ffmpeg 原生库**（0 个 .so）

### ❌ 未验证（本机无法做）
- [ ] **无安卓设备/模拟器**：`adb devices` 为空；APK **未安装、未真机运行**。
- [ ] **"assembleDebug 变绿" ≠ "App 能正常出片"**：
  - ffmpeg 壳导致运行时视频合成崩溃（P1-6）；
  - 6 处 P0（视频 prompt 恒空、质量审计恒过、时代红线写死西汉、retryFrom 占位剧本、
    clip 下载误用 tmpdir、四闸门恒 true）均为**运行时逻辑缺陷**，单元测试与 assemble 均不覆盖，
    **本次构建/测试未能验证也未能否定这些 P0 的存在**——它们的验证需真机/集成运行或针对性运行时测试。
- [ ] 未做 `connectedAndroidTest` / `lint` / `bundleRelease`（不在本次范围）。

### 📌 关键结论
1. `:app` 模块**可构建、可单测**，构建链路（SDK + Gradle + AGP 8.5.2 + Kotlin 2.0.21 + Compose BOM）在本机跑通。
2. 单测 67 条里 **1 条失败**，根因是测试调度器未设 `Dispatchers.setMain`（测试脚手架问题），**非 P0 功能缺陷**。
   其余 66 条含 P0 相关逻辑均通过——但这只证明"逻辑单测绿"，**不代表 P0 已修复**（P0 多为运行时行为，单测也未必断言）。
3. APK 可产出，但**缺 ffmpeg 原生库**，真机出片必然崩。修复路径见上一轮建议：
   换 `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7`（LGPL 版，30.9MB，20 个 .so，API 兼容）。
4. **6 处 P0 是否真实存在、是否已影响出片，本次未做运行时验证**——这是后续任务（改代码 + 真机/集成验证），非本次只读验证范围。

---

*生成：software-engineer-2（寇豆码）· 阶段 0 装 SDK 并验证 :app 模块*
*时间：2026-08-28 · 只读验证，未改动任何项目源码/构建脚本*

---

## 6. 阶段 1 ① · ffmpeg 依赖替换（换 community 维护版 8.1.7，重打 APK）

> 本阶段为**改代码 + 重打 APK**任务（区别于阶段 0 的只读验证）。目标：把 jitpack 纯 Java 壳
> `com.github.arthenica:ffmpeg-kit:5.1`（APK 缺 .so）换成 community 维护版
> `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7`（Maven Central，含真实原生库），让 APK 真正含 FFmpeg 原生库。

### 6.1 改动清单（4 处文件）

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts` | 依赖坐标 `com.github.arthenica:ffmpeg-kit:5.1` → `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7`；移除 jitpack.io 仓库（已无人依赖）；更新 31-38/80-86 行注释，标注现用 community 维护版 8.1.7（LGPL，Android SDK 35 兼容，无 GPL 编码器）。 |
| `core-engine/.../assemble/MovieAssembler.kt` | 分级编码器 `libx264` → `mpeg4`（质量参数 `-crf 18 -preset veryfast` → `-qscale:v 3`，因 crf/preset 是 libx264 专有，mpeg4 不支持）；同步修正类/KDoc 中「ffmpeg-kit 5.1」为 community 8.1.7。 |
| `app/src/main/java/com/dramafactory/app/AppGraph.kt` | 第 42 行注释 `ffmpeg-kit 5.1` → `ffmpeg-kit-full 8.1.7 community 维护版`。 |
| `docs/APP-BUILD-STATUS.md` | 本记录。 |

> 反射层 `androidFfmpegKitExecutor()` 包名仍为 `com.arthenica.ffmpegkit`，**未改动**（drop-in 兼容）；`AppGraph.init` 注入链路不变。

### 6.2 关于 libx264 → mpeg4 的工程判断（诚实说明）

- community 维护版为 **LGPL**，**不含 libx264 / libx265**（GPL 编码器）。若保留 `libx264`，端上执行会报 `encoder not found`，分级 step 失败并降级回原文件（虽不崩，但分级不生效）。
- 任务要求「让分级真正生效，主动换编码器」。但 `-crf` / `-preset` 是 **libx264 专有选项**，`mpeg4`（ffmpeg 内置 LGPL 编码器）**不支持**——若原样保留会触发 ffmpeg `Unrecognized option 'crf' / 'preset'` 报错，同样导致分级失败降级。
- 因此除换编码器外，将质量参数改为 `mpeg4` 原生可用的 `-qscale:v 3`（值越小质量越高，3≈高画质）。色彩分级滤镜链（`-vf eq=...colortemperature=...`）保持不变，分级功能仍生效。

### 6.3 重打 APK 结果

**命令**（项目根目录，先 `clean` 强制重新解析依赖）：
```
export ANDROID_HOME="C:/Users/owlco/Android/Sdk"
export JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot"
./gradlew clean :app:assembleDebug --console=plain --no-daemon
```

| 项 | 值 |
|---|---|
| 构建状态 | ✅ **BUILD SUCCESSFUL**（exit 0，耗时约 2m47s） |
| APK 路径 | `src-ai-drama\app\build\outputs\apk\debug\app-debug.apk` |
| APK 大小 | **65,702,868 字节（≈ 62.7 MB）**（对比阶段 0 的 32.5 MB，增量≈30MB = ffmpeg 原生库） |
| 退出码 | 0 |
| 依赖拉取 | ✅ `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7`（AAR 30.9MB）从 Maven Central 正常下载并解包 |

### 6.4 APK 内原生库清单（验收：必须含 ffmpeg 原生库）

`unzip -l app-debug.apk \| grep -E "\.so$"`（arm64-v8a，因 `app/build.gradle.kts` 限定 `abiFilters += "arm64-v8a"`）：

| .so | 大小(字节) | 归属 |
|---|---|---|
| libandroidx.graphics.path.so | 10,096 | Jetpack（与 ffmpeg 无关） |
| **libavcodec.so** | 19,101,048 | ✅ FFmpeg 编解码核心 |
| **libavdevice.so** | 56,528 | ✅ FFmpeg 设备 |
| **libavfilter.so** | 5,111,560 | ✅ FFmpeg 滤镜（含 eq/colortemperature，分级依赖） |
| **libavformat.so** | 3,558,120 | ✅ FFmpeg 封装/解封装 |
| **libavutil.so** | 559,272 | ✅ FFmpeg 工具 |
| libc++_shared.so | 1,794,776 | C++ runtime（ffmpegkit 依赖） |
| **libffmpegkit.so** | 467,632 | ✅ FFmpegKit JNI 桥 |
| **libffmpegkit_abidetect.so** | 30,576 | ✅ FFmpegKit ABI 探测 |
| **libswresample.so** | 205,376 | ✅ FFmpeg 音频重采样 |
| **libswscale.so** | 545,792 | ✅ FFmpeg 视频缩放 |

- **arm64-v8a 共 11 个 .so**，其中 **ffmpeg/ffmpegkit 原生库 9 个**（libavcodec/libavdevice/libavfilter/libavformat/libavutil/libffmpegkit/libffmpegkit_abidetect/libswresample/libswscale）+ `libc++_shared.so`（运行时依赖）。
- 项目 `abiFilters` 仅锁定 `arm64-v8a`，故**未打包 x86_64**（阶段 0 文档「预期 x86_64 同样一批」以双 ABI 计；本机真机目标只需 arm64-v8a，符合预期）。若日后需模拟器调试可放开 `abiFilters`。
- 构建日志 `stripDebugDebugSymbols` 显式列出以上 .so 已并入产物（仅未能 strip，不影响运行）。

### 6.5 结论

- ✅ 依赖替换成功：community 维护版 8.1.7 从 Maven Central 正常解析（无 404 / 无离线）。
- ✅ APK 重打成功：BUILD SUCCESSFUL，APK 真正含 FFmpeg 原生库（9 个 ffmpeg .so）。
- ✅ libx264→mpeg4 后 `assembleDebug` 仍绿（编码器改动只是命令字符串，编译期不受影响；运行时 mpeg4 编码与 eq/colortemperature 滤镜生效需真机验证）。
- ✅ 包名 `com.arthenica.ffmpegkit` 不变，反射层无需改动，AppGraph 注入链路不变。

### 6.6 诚实声明 / 未验证项

- ❌ **无安卓设备/模拟器**：APK 未安装、未真机运行。以下为构建期确定项，**运行时行为未验证**：
  - mpeg4 编码器在真机是否真的产出可播放 mp4（滤镜链 eq/colortemperature 是否在 mpeg4 输出上正常）；
  - 6 处 P0 运行时缺陷（视频 prompt 恒空等）仍待针对性验证（非本次范围）。
- ⚠️ mpeg4 为 MPEG-4 Part 2（非 H.264），同等画质下文件更大、部分老旧播放器兼容性略差；若后续需 H.264 可走 GPL 版（含 libx264）或 MediaCodec 硬编（MovieAssembler 二级策略已用 `h264_mediacodec`）。
- ⚠️ 阶段 0 文档提到的 1 条单测失败（Round6LocalUploadI2iTest，调度器未设 Dispatchers.setMain）本次**未重跑**——属测试脚手架问题，与 ffmpeg 替换无关。

---

*生成：software-engineer-3（寇豆码）· 阶段 1 ① ffmpeg 依赖替换为 community 维护版 8.1.7*
*时间：2026-08-28 · 改代码 + 重打 APK，诚实记录*
