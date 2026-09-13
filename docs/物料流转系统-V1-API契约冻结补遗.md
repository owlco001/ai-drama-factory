# 物料流转系统 V1 API 契约冻结补遗

更新时间：2026-09-13
适用范围：物流流转系统 Android、FastAPI 后端、Mock 服务
优先级：本文件覆盖旧文档中冲突的枚举、字段名和请求头示例；未覆盖内容继续遵循《物料流转系统 V1 需求冻结与接口契约》。

## 1. 固定命名

### 1.1 扫码类型

只允许以下 5 个值：

```text
PRODUCTION_ORDER
FLOW_NO
MATERIAL_CODE
LOCATION_CODE
UNKNOWN
```

禁止使用：`ORDER_NO`、`LOGISTICS_NO`、`ORDER`、`LOGISTICS`。

### 1.2 流转类型

```text
INBOUND       入库，需要审批
OUTBOUND      出库，需要审批
TRANSFER      调拨，提交后直接执行
STOCKTAKE     盘点，提交后等待仓库管理员确认
```

### 1.3 角色

```text
ADMIN
WAREHOUSE_ADMIN
MATERIAL_CLERK
OPERATOR
```

V1 不实现 `SUPERVISOR`。客户端不得通过本地角色切换获得权限，权限以服务端返回和服务端鉴权为准。

## 2. 通用请求约束

所有写接口必须包含：

```http
Authorization: Bearer <accessToken>
X-Request-Id: <UUID>
Idempotency-Key: <UUID>       # 仅创建/执行等幂等写接口必填
Content-Type: application/json
```

服务端：

- `X-Request-Id` 缺失或非 UUID 时返回 `400 INVALID_REQUEST_ID`。
- `Idempotency-Key` 与 body 的 `clientOperationId` 必须相同；不相同返回 `400 IDEMPOTENCY_KEY_MISMATCH`。
- 相同幂等键重复请求必须返回第一次请求的业务结果。
- 相同幂等键但 body 摘要不同，返回 `409 IDEMPOTENCY_PAYLOAD_MISMATCH`。
- 幂等记录至少保留 7 天。
- 所有响应错误都使用统一结构，并返回 `traceId`。

## 3. 扫码接口

### 3.1 解析

```http
POST /api/v1/scan/resolve
```

请求模型：

```python
class ScanResolveRequest(BaseModel):
    rawValue: str = Field(min_length=1, max_length=128)
    clientOperationId: UUID
```

响应模型：

```python
class ScanResolveResponse(BaseModel):
    type: Literal[
        "PRODUCTION_ORDER", "FLOW_NO", "MATERIAL_CODE",
        "LOCATION_CODE", "UNKNOWN"
    ]
    normalizedValue: str
    resourceId: str | None
```

示例：

```json
{
  "type": "PRODUCTION_ORDER",
  "normalizedValue": "SO202609120001",
  "resourceId": "ord_001"
}
```

### 3.2 订单物料状态

```http
POST /api/v1/orders/material-status
```

请求：

```json
{
  "documentType": "PRODUCTION_ORDER",
  "documentNo": "SO202609120001"
}
```

服务端拒绝 `documentType=ORDER_NO`。

## 4. 流转申请

### 4.1 创建申请

```http
POST /api/v1/transfer-requests
```

请求：

```json
{
  "clientOperationId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "OUTBOUND",
  "documentNo": "SO202609120001",
  "items": [
    {
      "materialId": "mat_001",
      "quantity": 10,
      "batchNo": "B20260912",
      "sourceLocationCode": "A-01-03",
      "targetLocationCode": null,
      "expectedInventoryVersion": 12
    }
  ],
  "remark": "生产领料",
  "evidenceIds": []
}
```

类型定义：

```python
class TransferItem(BaseModel):
    materialId: str = Field(min_length=1, max_length=64)
    quantity: int = Field(ge=1)
    batchNo: str | None = Field(default=None, max_length=64)
    sourceLocationCode: str | None = Field(default=None, max_length=64)
    targetLocationCode: str | None = Field(default=None, max_length=64)
    expectedInventoryVersion: int = Field(ge=1)

class CreateTransferRequest(BaseModel):
    clientOperationId: UUID
    type: Literal["INBOUND", "OUTBOUND", "TRANSFER", "STOCKTAKE"]
    documentNo: str | None = Field(default=None, max_length=64)
    items: list[TransferItem] = Field(min_length=1, max_length=100)
    remark: str | None = Field(default=None, max_length=500)
    evidenceIds: list[str] = Field(default_factory=list, max_length=20)
```

响应：

```json
{
  "requestId": "tr_001",
  "clientOperationId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING_APPROVAL",
  "serverTime": "2026-09-13T10:00:00Z",
  "traceId": "req_001"
}
```

### 4.2 审批

```http
POST /api/v1/transfer-requests/{requestId}/approve
```

请求：

```json
{
  "decision": "APPROVE",
  "comment": "数量与单据一致"
}
```

`decision=REJECT` 时 `comment` 长度必须为 1-500；拒绝必须填写原因。

允许角色：`ADMIN`、`WAREHOUSE_ADMIN`。

### 4.3 执行

```http
POST /api/v1/transfer-requests/{requestId}/execute
```

执行规则：

- `INBOUND`、`OUTBOUND` 只有 `APPROVED` 可执行。
- `TRANSFER` 由创建接口完成事务执行，不进入审批。
- `STOCKTAKE` 由确认接口完成差异处理。
- 审批人与执行人不能是同一用户。
- 数据库事务内按每个 item 校验 `expectedInventoryVersion`。
- 任意 item 冲突时整单回滚，库存和库位明细均不得改变。
- 成功后同一 `requestId` 再执行返回原成功结果，不重复变更库存。

## 5. 统一错误响应

```json
{
  "error": {
    "code": "INVENTORY_VERSION_CONFLICT",
    "message": "库存已变化，请刷新后重试",
    "retryable": false,
    "traceId": "req_001",
    "details": {
      "materialId": "mat_001",
      "serverVersion": 13,
      "clientVersion": 12
    }
  }
}
```

固定错误码：

```text
INVALID_REQUEST_ID
UNAUTHORIZED
FORBIDDEN
INVALID_SCAN_TYPE
IDEMPOTENCY_KEY_MISMATCH
IDEMPOTENCY_PAYLOAD_MISMATCH
INVENTORY_VERSION_CONFLICT
INSUFFICIENT_INVENTORY
TRANSFER_STATE_CONFLICT
APPROVAL_EXECUTOR_SAME_USER
VALIDATION_ERROR
RETRYABLE_UPSTREAM_ERROR
```

## 6. 数据一致性要求

执行库存变更必须在一个数据库事务中同时更新：

```text
materials.available_quantity
materials.total_quantity
materials.version
inventory.quantity
transfer_requests.status / executed_at
 audit_logs
```

出库和调拨来源数量不足、版本不一致、库位不存在时整单回滚。所有数量必须是 JSON 整数，`bool`、小数、负数、科学计数法和超出数据库范围的整数均拒绝。

## 7. 开发验收用例

- [ ] `PRODUCTION_ORDER` 可以解析并查询订单；`ORDER_NO` 返回 400。
- [ ] `FLOW_NO` 可以解析厂内流转单；`LOGISTICS_NO` 返回 400。
- [ ] 同一 `clientOperationId` + 相同 body 重试返回相同 `requestId`。
- [ ] 同一幂等键更换 quantity 返回 409，库存不变。
- [ ] 旧版本执行返回 409，库存和库位数量均不变。
- [ ] 审批用户执行同一申请返回 409。
- [ ] 出库库存不足返回 409，整单回滚。
- [ ] 所有错误响应含 `error.code`、`retryable`、`traceId`。
- [ ] Android 生产订单请求使用 `PRODUCTION_ORDER`，不再发送 `ORDER_NO`。
