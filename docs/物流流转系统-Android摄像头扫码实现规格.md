# 物流流转系统 Android 摄像头扫码实现规格

更新时间：2026-09-13
状态：架构冻结，待开发 Agent 实现
范围：手机摄像头扫码；工业扫码枪作为同一 `ScannerRepository` 的另一适配器
目标设备：Android 8.0+、8GB RAM 中端 Android

## 1. 目标流程

```text
扫码页面
  → 检查 CAMERA 权限
  → 请求权限（首次）
  → 显示 CameraX 预览
  → ML Kit Barcode Scanner 分析帧
  → 条码格式过滤
  → 1500ms 去重
  → 返回 rawValue
  → POST /api/v1/scan/resolve
  → 根据 type 进入订单/物料/库位/流转单页面
```

摄像头只负责读取条码原文，不在客户端硬编码业务类型判断。类型识别由服务端 `/api/v1/scan/resolve` 完成。

## 2. 技术选型

- CameraX `camera-camera2`、`camera-lifecycle`、`camera-view`：生命周期绑定稳定，兼容 Android 8.0+，避免直接管理 Camera2 session。
- Google ML Kit Barcode Scanning：本地识别，扫码不依赖网络；仅把条码原文发送给业务 API。
- Compose `AndroidView` 承载 `PreviewView`：复用现有 Compose 页面，同时保留 CameraX 所需 View。
- `ScannerRepository`：隔离相机、工业扫码枪和手动输入，业务层不依赖具体硬件。

依赖版本由开发 Agent 按当前 Gradle/Compose 兼容矩阵锁定，禁止使用动态版本号。

## 3. Android 权限

Manifest 必须声明：

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

运行时行为：

- 首次进入扫码页：请求 `Manifest.permission.CAMERA`。
- 用户允许：绑定生命周期并启动预览/分析。
- 用户拒绝一次：显示“不允许使用摄像头”，提供“重新授权”按钮和手动输入入口。
- 用户选择“不再询问”：显示系统设置跳转入口，同时保留手动输入，不循环弹权限框。
- 页面离开、Activity `onStop` 或权限失效：停止分析并释放 CameraX use case。
- 禁止在登录页或后台提前占用摄像头。

## 4. 分层接口

### 4.1 Domain 接口

```kotlin
interface ScannerRepository {
    fun observeScanEvents(): Flow<ScanEvent>
    suspend fun startCamera(owner: LifecycleOwner, preview: PreviewView): Result<Unit>
    suspend fun stopCamera(): Result<Unit>
    suspend fun resolve(rawValue: String): Result<ScanResolution>
}

data class ScanEvent(
    val rawValue: String,
    val format: BarcodeFormat,
    val capturedAt: Instant
)

enum class BarcodeFormat {
    QR_CODE,
    CODE_128,
    CODE_39,
    EAN_13,
    EAN_8,
    DATA_MATRIX,
    UNKNOWN
}
```

硬件适配器：

```kotlin
interface HardwareScannerAdapter {
    fun connect(): Flow<HardwareScannerState>
    fun disconnect()
    fun observeRawValues(): Flow<String>
}
```

### 4.2 ViewModel 接口

```kotlin
class ScannerViewModel(
    private val scannerRepository: ScannerRepository
) : ViewModel() {
    val uiState: StateFlow<ScannerUiState>
    fun onPermissionResult(granted: Boolean, permanentlyDenied: Boolean)
    fun onManualInput(rawValue: String)
    fun onRetryPermission()
    fun onScanConsumed()
}
```

UI 状态：

```kotlin
sealed interface ScannerUiState {
    data object PermissionRequired : ScannerUiState
    data object PermissionDenied : ScannerUiState
    data object Ready : ScannerUiState
    data object Processing : ScannerUiState
    data class Resolved(val resolution: ScanResolution) : ScannerUiState
    data class Error(val code: String, val message: String, val retryable: Boolean) : ScannerUiState
}
```

## 5. CameraX 实现约束

- 使用 `ProcessCameraProvider`，绑定 `Preview` 和 `ImageAnalysis`。
- `PreviewView.scaleType = FILL_CENTER`，取景框区域固定，不因文字或状态变化跳动。
- `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`，避免中端设备堆积帧导致内存增长。
- 分析器只处理有效的 `ImageProxy`，任何路径都必须调用 `imageProxy.close()`。
- 分析线程使用单线程执行器；不得在主线程执行 ML Kit 识别、网络请求或图片处理。
- 页面进入才启动，页面离开立即解绑；不保存相机帧、不截图、不写本地文件。
- 默认仅识别 QR、Code 128、Code 39、EAN-13、EAN-8、Data Matrix。
- 识别到第一个有效值后，暂停分析，进入 `Processing`；服务端解析结束后由页面决定继续扫码。
- 摄像头不可用时显示手动输入，不阻塞库存业务。

## 6. 防抖与输入规则

- 同一 `rawValue` 在 1500ms 内只发送一次解析请求。
- 防抖键为 `rawValue + barcodeFormat`，不是只按时间全局屏蔽。
- 原文先执行 Unicode trim 和长度校验，长度范围 1-128；不得修改业务字符内容。
- 每次调用 `/scan/resolve` 新建 UUID `clientOperationId`；它只标识解析请求，不替代流转提交的幂等 ID。
- 网络失败不丢失扫码原文；用户可重试，重试必须生成新的解析请求 ID。
- 扫码声音和震动由平台层提供，可在设置中关闭；默认成功短震动一次，失败不连续震动。

## 7. 页面交互要求

扫码页必须提供：

- 摄像头预览和固定取景框
- 闪光灯开关（设备不支持时禁用并给出原因）
- 手动输入入口
- 当前处理状态：准备扫码、识别中、查询中、识别失败
- 返回按钮
- 权限拒绝后的设置入口
- 识别成功后的结果确认，不允许连续误触发多个业务页面

扫码页不得显示或记录 access token、完整请求头、服务端内部错误堆栈。

## 8. 验收标准

### 功能

- [ ] 首次进入扫码页能请求摄像头权限。
- [ ] 授权后能显示实时预览并识别 QR/Data Matrix/Code 128 至少三类条码。
- [ ] 识别结果调用 `/api/v1/scan/resolve`，请求包含 `rawValue` 和 UUID `clientOperationId`。
- [ ] 服务端返回 `PRODUCTION_ORDER`、`FLOW_NO`、`MATERIAL_CODE`、`LOCATION_CODE` 或 `UNKNOWN` 后进入对应流程。
- [ ] 同一条码 1500ms 内不会重复发起请求。
- [ ] 用户拒绝权限后仍可手动输入并继续业务。
- [ ] 离开页面或锁屏后再次进入，摄像头能正确恢复且不重复占用。

### 异常

- [ ] 无摄像头、摄像头被其他应用占用、权限永久拒绝均有可操作提示。
- [ ] 网络失败、401、409、5xx 显示统一错误结构，不崩溃。
- [ ] 条码为空、超长或不支持格式不调用业务接口。
- [ ] 快速连续扫描 30 分钟无明显内存增长或分析线程堆积。

### 设备

- [ ] Android 8.0+ 权限行为通过。
- [ ] 8GB 中端设备连续扫码 30 分钟通过。
- [ ] 横竖屏策略固定并验证预览不变形；首版锁定竖屏。
- [ ] 工业扫码枪输入通过 `HardwareScannerAdapter` 接入，不能复制相机逻辑到业务页面。

## 9. 开发交付证据

开发完成后必须回写：

```text
Android Manifest 相机权限：文件路径 + 行号
CameraX/ML Kit 依赖：build.gradle.kts + 版本
扫码实现：ScannerRepository、ScannerViewModel、ScanScreen 路径
测试命令：完整 Gradle 命令
构建产物：APK 绝对路径、字节数、SHA-256
测试结果：用例总数、通过、失败、跳过
设备结果：型号、Android 版本、权限/连续扫码/断网结果
```

没有以上证据，只能标记为“摄像头扫码代码已实现”，不能标记为“摄像头扫码验收通过”。
