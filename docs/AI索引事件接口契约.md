# CendoDrive AI 索引事件接口契约

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 协作方 | Lucky：文件生命周期与可靠触发；Anna：文档提取与索引生成 |
| 适用范围 | 普通上传、分片合并、回收站、恢复、永久删除与 AI 索引的联动 |
| 接口性质 | 服务端内部异步事件接口 |

### 1.1 当前确认状态

| 项目 | 当前结论 |
| --- | --- |
| Anna Base URL | 待提供；Lucky 侧先使用配置占位，默认关闭真实投递 |
| 接口鉴权 | 当前约定为无鉴权，仅允许受控内部网络访问 |
| FastDFS `storageKey` | 确认为 `group1/M00/...` 形式的完整路径 |
| Anna 真实文件读取 | 尚未联调验证 |
| Anna 接收事件后的读取失败重试 | 待 Anna 确认 |
| Anna 是否在读取文件前检查 `revision` | 待 Anna 确认 |
| 复制文件 | 本期不触发 `UPSERT` |
| 分享转存文件 | 本期不触发 `UPSERT` |
| Lucky 投递重试策略 | 接受本文第 7.6 节的默认配置 |
| 契约文档 | 随本功能分支提交 |

## 2. 目标与边界

本契约只约定 CendoDrive 文件服务与 Anna 索引服务之间的最小协作边界：Lucky 负责可靠地产生并投递文件生命周期事件，Anna 负责幂等地处理事件并维护索引状态。

实现必须满足以下要求：

- AI 索引不可用时，文件上传、下载、回收站和永久删除等原有功能仍可正常完成。
- 索引任务不得写入 `drive_files`，不得重复计入用户容量。
- 不修改现有上传、下载和前端接口协议。
- 不在文件请求线程中执行文本提取、Embedding 或 Milvus 写入。

本期不包含语义搜索接口、AI 问答、索引进度前端、回调接口、文件下载接口、Milvus Collection 结构、Chunk 参数和模型参数。

本期也不为复制文件和分享转存产生的新文件触发 `UPSERT`。如后续需要让这些副本参与语义搜索，双方应先扩展触发范围和验收用例。

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

### 3.1 外部 FastDFS 访问

FastDFS 部署于独立主机。CendoDrive 与 Anna 分别通过各自的运行配置连接同一个 FastDFS Tracker 和 Storage，网络地址与连接参数不通过索引事件传递。

- 索引事件只传递 `storageBackend` 和 `storageKey`，不传递 Tracker 地址、Storage 地址、密码或其他连接凭据。
- FastDFS 连接参数由双方通过环境变量或本地配置维护，不得写入事件、日志或代码仓库。
- CendoDrive 负责上传、下载和物理删除原始文件；Anna 对原始文件仅具有读取职责，不得删除、移动或覆盖 FastDFS 内容。
- Anna 尚未完成使用真实 `storageKey` 读取外部 FastDFS 文件的联调验证。在该项通过前，不能将 AI-11 记为验收完成。
- Anna Base URL 尚未确定。Lucky 侧应使用可配置占位值，并在未配置时默认关闭真实事件投递；关闭投递不得影响文件业务或丢失本地待投递任务。

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
| `storageKey` | String | `UPSERT` 必填 | Anna 从 FastDFS 读取文件使用的完整路径，例如 `group1/M00/00/01/example.pdf` |

`fileId` 和 `ownerId` 使用字符串传输，避免不同语言处理大整数时出现精度问题。`DEACTIVATE` 和 `DELETE` 请求可以不携带 `fileName`、`storageBackend` 和 `storageKey`。

Anna 读取 FastDFS 的连接配置由 Anna 的服务自行维护；本接口不传递 FastDFS 密码或其他存储凭据。

`storageKey` 取自 CendoDrive 上传成功后保存的 FastDFS `StorePath.getFullPath()`。其格式为 `group/path`，不带协议、主机、端口、查询参数或开头斜杠。Anna 必须使用 `storageBackend + storageKey` 读取内容，不得根据 `fileName` 推导物理路径。

## 5. 操作语义

### 5.1 `UPSERT`

触发场景：

- 普通文件上传成功；
- 分片上传合并成功；
- 文件从回收站恢复。

本期明确不包含以下触发场景：

- 文件复制；
- 分享文件转存。

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

### 7.6 Lucky 默认投递与重试配置

| 配置 | 默认值 |
| --- | --- |
| 每次领取任务数 | 20 |
| Worker 扫描间隔 | 5 秒 |
| 连接超时 | 3 秒 |
| 响应超时 | 10 秒 |
| 最大投递尝试次数 | 10 次 |
| 退避间隔 | 1 分钟、5 分钟、15 分钟、30 分钟、1 小时、2 小时、4 小时、8 小时、12 小时 |
| 成功任务保留期 | 7 天 |
| 达到最大次数 | 标记为 `DEAD` 并保留错误信息，等待人工排查 |

`400`、`401`、`403` 直接标记为 `DEAD`；`429`、`5xx`、连接失败和超时按上述间隔重试。即使当前约定为无鉴权，仍保留 `401`、`403` 的处理规则，以便发现网络代理或未来鉴权配置错误。

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
- 对 FastDFS 原始文件仅执行读取，不删除、移动或覆盖文件。
- 在处理 `UPSERT` 并读取 FastDFS 前检查当前最大 `revision`；该项尚待 Anna 确认。
- 对已经返回 `2xx` 的事件，后续 FastDFS 暂时不可用或读取失败由 Anna 内部负责重试；该项尚待 Anna 确认。

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
| AI-11 | Anna 使用真实 `storageKey` 读取外部 FastDFS | 下载字节与原文件一致 |
| AI-12 | Tracker 可达但 Storage 暂时不可达 | Anna 记录可重试失败，不丢失已经接收的事件 |
| AI-13 | Anna 接收 `UPSERT` 后 FastDFS 暂时离线 | Anna 内部重试，CendoDrive 文件业务不回滚 |
| AI-14 | 旧 `UPSERT` 晚于新版 `DELETE` 执行 | Anna 忽略旧事件，不读取文件、不重建向量 |
| AI-15 | FastDFS 原文件已经不存在 | `DELETE` 仍按幂等成功处理 |

## 11. 联调前确认清单

双方开始联调前确认以下配置：

- Anna 服务的内部访问地址；
- 双方使用的接口路径和 JSON 字段与本契约一致；
- Anna 已具备读取相同 FastDFS 文件的网络和配置条件；
- Anna 已使用 `group1/M00/...` 格式的真实 `storageKey` 完成文件读取测试；
- Anna 确认返回 `2xx` 后自行负责 FastDFS 读取失败的重试；
- Anna 确认在读取 FastDFS 文件前完成 `revision` 检查；
- Lucky 的连接超时、响应超时及重试间隔；
- `eventId` 生成规则和 `revision` 初始值；
- 测试用 PDF、DOCX、TXT、Markdown 文件及对应用户、文件 ID。

