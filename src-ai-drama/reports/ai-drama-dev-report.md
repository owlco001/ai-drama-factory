
## 第九轮：QualityEngine（对齐 pavo 质量方法论）（2026-08-26）

**目标：** 把 pavo-drama 技能的核心质量方法论移植进「AI短剧工厂」安卓 App，新增 QualityEngine 模块（core-engine 的 `quality` 包 + app 接线），让端上出品质量对齐 pavo。

**交付概览（A→H 全落地）：**

| 子模块 | 落地位置 | 说明 |
|---|---|---|
| A. G1 文件级硬校验 | `core-engine/.../quality/AssetInspector.kt` | 纯 Kotlin：魔数/尺寸/正方形/文件大小/Bitmap人脸占比近似/不可解码。0 模型成本。 |
| A2. G2 多模态审计 | `core-engine/.../quality/AssetAuditor.kt` | 调 agnes-2.5-flash 带图打分，解析 {score,defects,face_ratio}；defects 非空直接拒（`ERROR_DEFECT_DETECTED`），失败重试≤3。TextProvider 注入可测。 |
| B. 角色 DNA 6 姿态资产包 | `AssetsLogic.buildPosePack` + `StylePreset.characterPoses` | 母卡→6 张子图（front_anchor/side_45/full_body_riding/expression_serious·angry·calm），中英双语构图指令注入；UI「生成6姿态」入口。 |
| C. 时代红线 | `StylePreset`(西汉默认) + `EraConsistencyChecker` | era.negative 折叠进 negative_prompt，era.positive 折叠进 suffix；按剧集 `allowed_cross_era` 放行（episodes 列 + UI 勾选）。 |
| D. 分镜六铁律闸门 | `StoryboardGate.validateStoryboard/compileStoryboard` | 台词逐字/人物完整/action词表/beat_ref单调/carry_over/真实资产ID。任一 error→整集中止。 |
| E. 提交前忠实性校验 | `FidelityGate.gateShot` | 资产真实/台词逐字/时间逆转 30 词表/状态不漂移/跨镜一致；blocked 镜不提交。 |
| F. 道具 i2i + 开场帧重渲染 | `ShotDirector` | 道具主资产双图 i2i 合成进场景静帧；开场帧 i2i from 资产图+场景指令重渲染（否定孤立原图，对齐 pavo `_render_opening_frame`）。 |
| G. 成片色彩统一 | `FfmpegAssembler.gradeBatch/gradeClip` | 复用拼接管理器，三级策略前对每镜统一色彩分级（CINEMA/COOL/WARM/NEUTRAL）。 |
| H. 中文配音指令 | `ChineseAudioInjector`（既有 Q9 核对一致） | 每段 prompt 以中文台词/旁白开头主导 + 显式「全程使用中文普通话配音」。 |

**不可移植（GPU 类）明确标注（需服务端 / 降级为 DNA 参考注入）：**
- **InsightFace 锁脸**：手机端不跑人脸比对，降级为「角色 DNA 6 姿态参考注入」+ G2 多模态人脸占比近似校验。
- **GPT-SoVITS 音色克隆**：手机端不跑 TTS 模型，保留 Q9 中文配音指令链路，音色克隆交由服务端。
- **Demucs 人声分离**：手机端不跑音频分离，BGM/人声混流交由服务端。

**质量落库（DB v2→v3 迁移，保留既有数据）：** assets 新增 `quality_score/audit_state/defects_json/q_reject_reason/g1_error_code/face_ratio/pose_role`；episodes 新增 `allowed_cross_era`。

**单元测试：** 新增 `QualityEngineTest` **34 例**（G1/G2/六铁律/时间逆转/era注入/开场帧重渲染/道具i2i/B六姿态全覆盖）。全量 **128 例**（engine 70 + app 58）全绿，`:app:assembleDebug` 编译通过。

**构建环境补完：** 项目缺 Gradle wrapper（上游仅源码），本机无发行版——已下载 Gradle 8.7 生成 `gradle/wrapper/` 并用 `/opt/android-sdk`(android-34) 离线构建通过。
