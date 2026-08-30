# AI 短剧工厂 (AI Drama Factory)

从剧本到成片的智能短剧制作 Android App。输入剧本，AI 自动完成素材提取、图片生成、分镜生成、视频渲染、成片合成全流程。

## 功能特性

- **剧本驱动**：粘贴剧本文本，AI 自动提取角色/场景素材
- **七阶段流水线**：项目创建 → 素材提取 → 图片生成 → 分镜生成 → 渲染排队 → 成片合成 → 影片库
- **AI 对话助手**：自然语言驱动全流程，支持 `[ACT]` 动作指令
- **多模型路由**：支持 Agnes / DeepSeek 文本模型，可在设置中切换
- **本地持久化**：Room 数据库存储项目、素材、分镜、渲染任务、成片
- **安全存储**：Android Keystore + EncryptedSharedPreferences 管理 API Key
- **前台渲染服务**：渲染任务在 Foreground Service 中执行，支持后台运行
- **质量审核**：AI 自动审核生成的素材图片，支持跨时代一致性约束

## 技术栈

| 层级 | 技术 |
|---|---|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose (BOM 2024.10.01, Material3 1.3.x) |
| 架构 | 多模块：app (Android壳) + core-engine (纯Kotlin引擎) + desktop |
| 数据库 | Room 2.6.1 |
| 网络 | Ktor Client 2.3.12 (OkHttp engine) |
| 视频处理 | ffmpeg-kit-min-gpl 8.1.7 (含 x264) |
| 异步 | Kotlin Coroutines 1.9.0 + StateFlow |
| 图片加载 | Coil 2.6.0 |
| 安全 | androidx.security-crypto 1.1.0-alpha06 |
| 最低系统 | Android 10 (API 29) |
| 目标系统 | Android 14 (API 34) |

## 模块结构

```
src-ai-drama/
├── app/                    # Android 应用壳
│   └── src/main/java/com/dramafactory/app/
│       ├── AppGraph.kt         # 依赖注入 + 服务定位
│       ├── DramaApplication.kt # Application 入口
│       ├── MainActivity.kt     # 主 Activity
│       ├── data/               # Room 数据库、DAO、Entity
│       ├── security/           # AndroidKeyVault
│       ├── service/            # RenderForegroundService
│       └── ui/                 # Compose 页面 + ViewModel + 逻辑
├── core-engine/            # 纯 Kotlin 引擎（JVM 可单测，无 Android 依赖）
│   └── src/main/kotlin/com/dramafactory/core/
│       ├── assemble/           # 视频拼接 (FFmpeg)
│       ├── model/              # 数据模型
│       ├── orchestrate/        # AI 编排器、Agent、动作解析
│       ├── pipeline/           # 流水线阶段、预算守卫
│       ├── provider/           # 模型提供者 (Agnes/DeepSeek)
│       ├── quality/            # 质量审核、素材提取、分镜导演
│       └── storage/            # 内存存储实现
└── desktop/                # Desktop 端（Compose Desktop）
```

## 构建

```bash
cd src-ai-drama
./gradlew assembleDebug
```

输出 APK：`app/build/outputs/apk/debug/app-debug.apk`

### API Key 配置

首次启动后在「设置」页面配置 Agnes / DeepSeek API Key。Key 存储在 EncryptedSharedPreferences 中，不会明文落盘。

## 版本历史

- **v1.7.16**：优化包体积（ffmpeg-kit-full → min-gpl，APK 减小约 30MB）；AiAgent 消息滑动窗口防止 context overflow；协程安全优化（enqueueRenderFor 消除 runBlocking）
- **v1.7.15**：素材引用支持本地上传（LLM 生成 catalog + asset_id 引用）+ 分镜支持 first_asset_ids 字段
- 更早版本参见 commit 历史

## 许可证

私有项目，未经授权不得分发。
