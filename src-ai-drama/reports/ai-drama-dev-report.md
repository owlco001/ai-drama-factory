
## v0.4 修复：剧本模式导入后资产生成入口缺失
- 根因：剧本模式仅写 stage_flags.script_mode=true 并跳过文本分析自动建卡，AssetsPage/AssetsLogic 无剧本→资产卡入口，资产列表恒为空，评审门无法放行，分镜渲染因缺资产ID被卡死。
- 修复：
  - AssetsLogic 新增 ScriptAssetExtractor 纯函数：解析「角色/人物/场景/道具：」清单行与场次标题行（第X场 / 场景N / SCENE N / 内景 / 外景 / INT. / EXT.），去重保序；isScriptMode(stage_flags) 宽松识别。
  - AssetsLogic.extractFromScript()（kind+prompt判重一键建卡）、pendingIdsOfKind(kind)。
  - AssetsViewModel：init 读 episodes.script_json + stage_flags；extractFromScript 提取→落库→逐卡触发生成；generatePendingOfKind。
  - AssetsPage：scriptMode=true 时显示「剧本模式 · 资产生成」卡片：「一键从剧本提取资产卡」+「逐类生成图像」按钮及结果提示。
- 测试：新增 ScriptAssetExtractionTest（6用例）。:app:assembleDebug 通过，全量30个单测全绿。

## 第六轮：资产卡BUG+本地上传/图生图/图生视频/视频参考
### 一、提取资产卡无反应（BUG修复）
- 根因：AssetsViewModel 用普通字段 `var scriptText` 在 `init` 协程异步读取 episodes.script_json；用户点「一键提取」时 init 协程可能尚未返回，`scriptText` 仍为 null → `extractFromScript()` 直接 return，资产列表永远不更新（点击无反应）。
- 修复：`scriptText` 改为 `CompletableFuture<String?>`；`extractFromScript()` 提取前 `scriptText.get()` 等待剧本加载完成；同时 init 预载本项目已存本地资产，保证列表不空窗。空剧本给出可读提示而非静默。
- 回归：新增 `Round6LocalUploadI2iTest` 用例「提取按钮_clicked_before_script_loaded_仍建卡」「空剧本_提取给出提示不崩溃」，验证点击即建卡。

### 二、本地上传资产（新功能）
- DB v2（Room version 1→2，ALTER TABLE 增量迁移保留数据）：assets 增 `source`(generated/local)/`image_uri`/`video_uri`/`reference_image_uri`；shots 增 `first_image_uri`/`last_image_uri`/`reference_video_uri`。
- AssetsViewModel.uploadLocal(imageUri/videoUri/prompt)：经 AssetsLogic.addLocalAsset 建卡（kind=LOCAL）并持久化到 assets 表。
- UI（AssetsPage）：「本地上传」卡片四类入口——拍摄图片(TakePicture)、拍摄视频(TakeVideo)、相册图片(GetContent image/*)、相册视频(GetContent video/*)；本地资产卡展示 image/video URI。
- 提供 FileProvider(capture 路径) + CAMERA/READ_MEDIA 权限（AndroidManifest）。

### 三、图生图 image-to-image（新功能）
- 资产卡加 `referenceImageUri`；AssetsPage 本地图片卡可「设为参考图/取消参考图」；生成按钮有参考图时标签变「用参考图生成」。
- 生成链路：AssetsViewModel.generateHandler 读取 card.referenceImageUri，非空则作为 `ImageGenRequest.inputImages` 传给 AgnesProvider.generateImage（对齐 agnes_client.generate_image 的 input_images 语义），并持久化到 assets.reference_image_uri。

### 四、图生视频 image-to-video（新功能）
- 渲染队列 DefaultRenderQueue 已支持 keyframes（first_image_uri/last_image_uri 双帧 → mode=keyframes）；新增 `shotKeyframeResolver` 从 shots 表读取本镜已设参考图。
- UI（QueuePage）：每镜「设参考图」入口（GetContent image/*），调 QueueViewModel.setShotKeyframe 落库 shots 表 → 渲染时走 AgnesVideoAdapter keyframes 模式。
- AgnesProvider.submitVideo 补充单参考图分支：`referenceImageUri` 非空（非双帧）时作为 image 首帧。

### 五、视频参考 video reference（新功能）
- core-engine：VideoSubmitRequest 增 `referenceVideoUri`；ModelSpec 增 `supportsVideoReference`（Agnes 视频模型标记 true，其余默认 false）。
- AgnesProvider.submitVideo 在模型支持时把 referenceVideoUri 作为 `reference_video` 字段组装进提交体。
- 渲染队列 DefaultRenderQueue 增 `shotReferenceVideoResolver`；QueueViewModel 注入从 shots 表读取（仅当当前视频模型 supportsVideoReference=true 才回填）。
- UI（QueuePage）：「上传参考视频」入口仅在 `vm.videoModelSupportsReference()` 为 true 时显示（MVP能力门控）。

### 测试与编译
- 新增 Round6LocalUploadI2iTest（12用例）：本地URI持久化、图生图inputImages、keyframes/单参考图/视频参考参数组装（Ktor MockEngine 验证请求体）、渲染队列resolver透传、模型能力标记。
- AppGraph.dao/agnes 可见性放宽为 internal set 以支持单测桩；app 模块新增 ktor-client-mock 测试依赖。
- :app:assembleDebug 通过；全量单测（含 core-engine）保持绿。


## 第六轮：资产卡BUG+本地上传/图生图/图生视频/视频参考（2026-08-25）

用户反馈：提取资产卡无反应；缺本地上传/图生图/图生视频/视频参考。

### 修复/新增
- 提取资产卡无反应根因：AssetsViewModel.init 异步加载剧本，旧实现直接读可为null的scriptText字段→提取直接return。改为 CompletableDeferred.await()（挂起非阻塞，避免单线程调度器死锁）。
- 本地上传（拍摄/相册图/相册视频）：AssetsViewModel.uploadLocal + AssetsLogic.addLocalAsset，AssetEntity 落库。
- **真bug修复**：uploadLocal 原用纯 UPDATE（updateAssetLocal）落库，行不存在时静默无操作→资产永不持久化。改为先 upsertAsset（INSERT OR REPLACE）再 UPDATE。
- 图生图：generateHandler 把 referenceImageUri 作为 input_images 传给图像API。
- 图生视频：VideoSubmitRequest 增 firstImageUri/lastImageUri/referenceImageUri；AgnesProvider 组装 mode=keyframes（双帧）或单图首帧。
- 视频参考：VideoSubmitRequest.referenceVideoUri；AgnesProvider 组装 reference_video；仅模型 supportsVideoReference 时 UI 显示入口。
- DefaultRenderQueue 增 shotKeyframeResolver / shotReferenceVideoResolver 透传。
- ScriptAssetExtractor 纯函数解析「角色/场景/道具：」清单与场次标题。

### 测试
- Round6LocalUploadI2iTest 12 用例（含修复测试调度：viewModelScope用真实IO线程，测试侧 runBlocking+真实等待）。
- 全量：app 17 + engine 35 = 52 全绿（验收时累计）。
