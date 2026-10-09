# CendoDrive AI 索引事件接口契约

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 协作方 | Lucky：文件生命周期与可靠触发；Anna：文档提取与索引生成 |
| 适用范围 | 普通上传、分片合并、回收站、恢复、永久删除与 AI 索引的联动 |
| 接口性质 | 服务端内部异步事件接口 |

## 2. 目标与边界

本契约只约定 CendoDrive 文件服务与 Anna 索引服务之间的最小协作边界：Lucky 负责可靠地产生并投递文件生命周期事件，Anna 负责幂等地处理事件并维护索引状态。

实现必须满足以下要求：

- AI 索引不可用时，文件上传、下载、回收站和永久删除等原有功能仍可正常完成。
- 索引任务不得写入 `drive_files`，不得重复计入用户容量。
- 不修改现有上传、下载和前端接口协议。
- 不在文件请求线程中执行文本提取、Embedding 或 Milvus 写入。

本期不包含语义搜索接口、AI 问答、索引进度前端、回调接口、文件下载接口、Milvus Collection 结构、Chunk 参数和模型参数。

## 3. 交互方式

Lucky 在文件业务事务中写入本地索引任务。后台任务在事务成功提交后异步调用 Anna 的接口；调用失败时由 Lucky 保留原任务并按规则重试。

Anna 成功接收事件后自行完成文件读取、文本提取、分块、Embedding 和 Milvus 写入。Anna 内部处理失败不得反向影响已经成功的文件业务。

```text
文件状态变更
    → Lucky 在本地持久化索引任务
    → 文件业务事务提交
    → 后台异步投递事件
    → Anna 幂等处理事件
```

## 4. 索引事件接口

### 4.1 请求

```http
POST /internal/ai/index-events
Content-Type: application/json
```

```json
{
  "eventId": "file-739-3-UPSERT",
  "operation": "UPSERT",
  "fileId": "739",
  "ownerId": "18",
  "revision": 3,
  "fileName": "Java学习笔记.pdf",
  "storageBackend": "fastdfs",
  "storageKey": "group1/M00/00/01/example.pdf"
}
```

### 4.2 字段定义

| 字段 | 类型 | 必填条件 | 说明 |
| --- | --- | --- | --- |
| `eventId` | String | 始终必填 | 唯一事件编号；同一任务重试时必须保持不变 |
| `operation` | String | 始终必填 | `UPSERT`、`DEACTIVATE` 或 `DELETE` |
| `fileId` | String | 始终必填 | CendoDrive 文件 ID |
| `ownerId` | String | 始终必填 | 文件所属用户 ID |
| `revision` | Long | 始终必填 | 文件搜索状态版本；每次搜索状态变化时递增，必须大于 0 |
| `fileName` | String | `UPSERT` 必填 | 文件逻辑名称 |
| `storageBackend` | String | `UPSERT` 必填 | 当前固定为 `fastdfs` |
| `storageKey` | String | `UPSERT` 必填 | Anna 从 FastDFS 读取文件使用的 Key |

`fileId` 和 `ownerId` 使用字符串传输，避免不同语言处理大整数时出现精度问题。`DEACTIVATE` 和 `DELETE` 请求可以不携带 `fileName`、`storageBackend` 和 `storageKey`。

Anna 读取 FastDFS 的连接配置由 Anna 的服务自行维护；本接口不传递 FastDFS 密码或其他存储凭据。

## 5. 操作语义

### 5.1 `UPSERT`

触发场景：

- 普通文件上传成功；
- 分片上传合并成功；
- 文件从回收站恢复。

Anna 的处理规则：

- 尚无索引时，读取文件并完成解析、分块、Embedding 和向量写入。
- 相同事件或相同版本已经处理时，不重复生成向量，直接按幂等成功处理。
- 恢复文件时，可以复用已有向量并将文件重新设为可搜索；是否重新提取由 Anna 内部决定。
- PDF、DOCX、TXT、Markdown 之外的格式记录为不支持，不影响文件上传结果。
- 损坏、空白、暂不支持或索引失败的文件，由 Anna 记录明确状态。

### 5.2 `DEACTIVATE`

触发场景：

- 文件移入回收站；
- 文件夹移入回收站时，对其子树中的每个普通文件触发。

Anna 的处理规则：

- 使文件退出搜索结果；
- 暂时保留已有 Chunk 和向量，便于恢复；
- 没有对应索引或重复停用时仍按成功处理。

### 5.3 `DELETE`

触发场景：

- 文件被永久删除；
- 清空回收站；
- 文件夹被永久删除时，对其子树中的每个普通文件触发。

Anna 的处理规则：

- 删除 `ownerId + fileId` 对应的全部 Chunk 和向量；
- 对应索引不存在或已经删除时仍按成功处理。

## 6. 幂等与乱序处理

### 6.1 重复事件

`eventId` 是事件幂等键。Lucky 重试投递时必须复用原 `eventId`；Anna 收到重复 `eventId` 时不得重复生成或删除数据，应直接返回成功。

建议事件编号格式：

```text
file-{fileId}-{revision}-{operation}
```

### 6.2 乱序事件

Anna 按 `ownerId + fileId` 保存已处理的最大 `revision`：

| 收到的版本 | 处理规则 |
| --- | --- |
| 小于已处理版本 | 忽略业务处理并返回成功 |
| 等于已处理版本 | 按重复事件处理并返回成功 |
| 大于已处理版本 | 正常执行事件 |

该规则用于避免旧事件晚到覆盖新状态。例如恢复产生的 `UPSERT` 已完成后，旧的 `DEACTIVATE` 重试不得再次使文件退出搜索。

Lucky 负责在上传、移入回收站、恢复和永久删除时生成递增的 `revision`。

## 7. 响应约定

### 7.1 首次成功受理

```http
HTTP/1.1 202 Accepted
```

```json
{
  "eventId": "file-739-3-UPSERT",
  "accepted": true
}
```

`202` 只表示 Anna 已可靠接收该事件，不表示文本提取和索引生成已经完成。事件被 Anna 接收后，后续处理和内部重试由 Anna 负责。

### 7.2 重复或旧事件

```http
HTTP/1.1 200 OK
```

```json
{
  "eventId": "file-739-3-UPSERT",
  "accepted": true,
  "ignored": true
}
```

### 7.3 请求内容错误

```http
HTTP/1.1 400 Bad Request
```

```json
{
  "code": "INVALID_INDEX_EVENT",
  "message": "storageKey is required for UPSERT",
  "retryable": false
}
```

### 7.4 服务暂时不可用

```http
HTTP/1.1 503 Service Unavailable
```

```json
{
  "code": "INDEX_SERVICE_UNAVAILABLE",
  "message": "Index service is temporarily unavailable",
  "retryable": true
}
```

### 7.5 Lucky 的响应处理规则

| 响应 | 本地任务处理 |
| --- | --- |
| 任意 `2xx` | 标记为投递成功，不再重发 |
| `400`、`401`、`403` | 标记为不可重试失败并记录错误 |
| `429`、`5xx` | 保留任务，稍后重试 |
| 连接失败或超时 | 保留任务，稍后重试 |

建议连接超时为 3 秒，响应超时为 10 秒。接口只负责接收事件，不应同步等待文档解析和索引生成完成。

## 8. 双方职责

### 8.1 Lucky

- 在上传、回收站、恢复和永久删除事务中持久化本地索引任务。
- 普通上传和分片合并最终只产生一次有效 `UPSERT`。
- 后台异步投递事件，不在上传或下载请求线程中调用 Anna。
- 重试时保持相同 `eventId`。
- 维护递增的 `revision`。
- 文件夹生命周期操作展开为其子树中的普通文件事件。
- 索引任务不写入 `drive_files`，不参与用户容量计算。
- Anna 不可用时保证原有上传和下载链路不受影响。
- 单独实现 FastDFS 物理清理失败的任务记录和重试；该任务不属于 Anna。

### 8.2 Anna

- 根据 `eventId` 实现重复事件幂等处理。
- 根据 `revision` 阻止旧事件覆盖新状态。
- 从 FastDFS 读取 PDF、DOCX、TXT 和 Markdown 文件。
- 完成文本提取、分块、Embedding 和 Milvus 写入。
- 根据事件实现索引启用、停用和删除。
- 记录损坏、空白、不支持和处理失败状态。
- 对已经接收的事件负责后续处理和内部重试。
- 索引失败不得反向影响文件上传结果。

## 9. 容量与稳定性约束

- 用户容量仍只由现有文件元数据和未完成上传预留量计算。
- AI 任务、文本、Chunk、Embedding、Milvus 数据和临时解析文件不计入网盘容量。
- Anna 不得调用文件上传接口保存索引中间产物。
- Lucky 不因创建、重试或完成索引任务而调用容量增加逻辑。
- Anna 不可用、响应超时或处理失败时，文件上传和下载必须继续使用原有行为。
- FastDFS 物理文件清理与 AI 向量清理相互独立，任一失败都由各自任务机制重试。

## 10. 联调验收

| 编号 | 场景 | 验收结果 |
| --- | --- | --- |
| AI-01 | 普通文件上传成功 | Anna 收到一次有效 `UPSERT` |
| AI-02 | 分片文件合并成功 | Anna 收到一次有效 `UPSERT` |
| AI-03 | 重复投递相同 `eventId` | Anna 不重复生成向量并返回 `2xx` |
| AI-04 | 旧版本事件晚到 | Anna 忽略旧事件且不覆盖当前状态 |
| AI-05 | 文件移入回收站 | 收到 `DEACTIVATE`，文件退出搜索结果 |
| AI-06 | 文件从回收站恢复 | 收到更高版本 `UPSERT`，文件重新可搜索 |
| AI-07 | 文件永久删除 | 收到 `DELETE`，相关 Chunk 和向量被清理 |
| AI-08 | Anna 停机、超时或返回 `503` | 上传和下载正常，本地任务保留并重试 |
| AI-09 | 索引处理前后检查容量 | `usedBytes` 和 `availableBytes` 不因索引变化 |
| AI-10 | FastDFS 删除首次失败 | Lucky 记录独立清理任务并能后续重试 |

## 11. 联调前确认清单

双方开始联调前确认以下配置：

- Anna 服务的内部访问地址；
- 双方使用的接口路径和 JSON 字段与本契约一致；
- Anna 已具备读取相同 FastDFS 文件的网络和配置条件；
- Lucky 的连接超时、响应超时及重试间隔；
- `eventId` 生成规则和 `revision` 初始值；
- 测试用 PDF、DOCX、TXT、Markdown 文件及对应用户、文件 ID。

