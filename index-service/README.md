# 文档索引服务（index-service）

CendoDrive AI 索引链路中由 Anna 负责的**文档索引服务**：接收 Lucky 投递的文件生命周期事件，
从存储读取 PDF / DOCX / TXT / Markdown，完成**文本提取与分块**。

- 上游入口契约：`docs/AI索引事件接口契约.md`（Lucky）
- 本服务的回执与参数口径：`docs/文档索引服务对接说明.md`
- 本服务的实现假设与待确认项：见本文末「假设与待确认」一节

## 当前范围

已完成：

- `POST /internal/ai/index-events` 事件受理：幂等（`eventId`）、乱序（`revision`）、响应码契约；
- PDF / DOCX / TXT / Markdown 的文本提取，含编码兜底与换行规范化；
- 文本分块（默认 800 字符、重叠 100，段落与句子边界优先）；
- 本地任务表：幂等键、`revision` CAS、状态记录、分块中间结构；
- 内部重试：异步入队 + 退避（12 次）。

未完成（等待队友）：

- **Embedding 与 Milvus 写入**：等 Maki 提供模型、向量维度与集合结构；
- **FastDFS 直读**：等 Lucky 提供 Tracker / Storage 连接信息（契约 AI-11）；
  在此之前 `fastdfs` 事件会以 `FAILED` 终止并保留明确错误，不做无意义重试；
- **索引状态查询接口**：与 Lucky 的状态通道尚未约定。

因此 `UPSERT` 的终态是 `CHUNKED`——文本与分块已就绪，向量写入属于后续阶段，
本服务不会把未写入向量的文件标记为 `INDEXED`。

## 快速开始

```bash
python -m venv .venv
.venv/Scripts/activate          # Windows PowerShell
pip install -e ".[dev]"
cp .env.example .env
python -m index_service         # 默认监听 0.0.0.0:8000
pytest
```

本地自测（不依赖任何队友）：

```bash
mkdir -p data/files && printf '第一段内容。\n' > data/files/notes.txt
curl -X POST http://localhost:8000/internal/ai/index-events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"file-1-1-UPSERT","operation":"UPSERT","fileId":"1","ownerId":"1",
       "revision":1,"fileName":"notes.txt","storageBackend":"local","storageKey":"notes.txt"}'
```

## 目录结构

```text
index-service/
├─ pyproject.toml          依赖与 pytest 配置
├─ .env.example            可配置项示例（统一 INDEX_ 前缀）
└─ src/index_service/
   ├─ config.py            运行配置
   ├─ models.py            事件、状态、分块模型
   ├─ store.py             SQLite：幂等键 / revision CAS / 分块 / 状态
   ├─ sources.py           文件来源（LocalFileSource 可用，FastDfsSource 待接入）
   ├─ extractors.py        PDF / DOCX / TXT / Markdown 文本提取
   ├─ chunker.py           文本分块
   ├─ pipeline.py          受理与处理流水线、重试
   ├─ api.py               HTTP 接口与 worker 生命周期
   └─ __main__.py          python -m index_service 入口
```

## 接口

`POST /internal/ai/index-events`（无鉴权，仅内网）

| 情况 | HTTP | 响应体 |
| --- | --- | --- |
| 首次受理 | `202` | `{"eventId":"...","accepted":true}` |
| 重复 / 旧事件 | `200` | `{"eventId":"...","accepted":true,"ignored":true}` |
| 请求内容错误 | `400` | `{"code":"INVALID_INDEX_EVENT","message":"...","retryable":false}` |
| 内部异常 | `503` | `{"code":"INDEX_SERVICE_UNAVAILABLE","message":"...","retryable":true}` |

另提供 `GET /healthz` 供部署探活。

## 处理语义

- **受理**：接口线程只做轻量判断并入队，立即返回；不在请求线程内解析文档。
- **幂等**：`eventId` 重复直接返回 `200 ignored`。
- **乱序**：按 `ownerId + fileId` 记录已生效的 `revision`；更旧的事件忽略。
  写入向量前还会做一次原子 CAS，防止旧事件覆盖新状态（契约 AI-14）。
- **操作**：`UPSERT` 提取并分块；`DEACTIVATE` 置 `active=false` 且保留分块；
  `DELETE` 删除分块与状态。
- **重试**：`FileMissingError` 有界重试（默认 3 次）后置 `FILE_MISSING`；存储暂不可达按退避重试；
  格式损坏、超出大小或页数上限、解析超时、来源未接通则直接终止。

## 配置

环境变量统一使用 `INDEX_` 前缀，默认值与 `docs/文档索引服务对接说明.md` 第 6、7 节一致，
完整清单见 `.env.example`。其中 `INDEX_SOURCE_KIND=local` 仅用于自测。

## 假设与待确认

本服务在队友信息到位前先按以下假设实现，待确认后逐条替换：

| 编号 | 假设 | 状态 | 待谁确认 |
| --- | --- | --- | --- |
| A1 | 分块 800 字符、重叠 100、段落句子边界优先 | 已实现，可配置 | Maki（如片段有长度要求） |
| A2 | 编码顺序 UTF-8(BOM) → GB18030 → 替换兜底 | 已实现 | 无 |
| A3 | 换行统一为 LF（CRLF 规范化） | 已实现，对接说明未提及 | 无 |
| A4 | 限制：单文件 50MB、PDF 1000 页、解析 120s | 已实现，可配置 | 无 |
| A5 | 内部重试 12 次、退避 30s…12h | 已实现，可配置 | Lucky（已按契约默认） |
| A6 | `revision` 前置判断 + 写入时 CAS | 已实现 | Lucky（已在对接说明回复） |
| A7 | 新增状态 `IGNORED`（事件被更高 revision 取代） | 已实现，并已同步至对接说明 v0.2 第 9 节 | 已闭环 |
| A8 | 解析超时归入「不重试」 | 已实现，并已同步至对接说明 v0.2 第 6 节 | 已闭环 |
| A9 | 分块中间结构：`chunk_index / text / char_start / char_end` | 已实现 | 待 Maki 的 Milvus schema 后加适配层 |
| A10 | `LocalFileSource` 仅自测；`fastdfs` 未接入 | 待 Lucky 提供连接信息 | Lucky |
| A11 | 状态仅本地存储，暂不对外提供查询 | 待约定通道 | Lucky / Landen |

> `TODO(contract)` 标记散布在代码注释中，与上表一一对应。

## 已知限制

- 单进程、单 worker 轮询；多实例部署需要先引入任务领取锁，否则会重复处理。
- worker 处理过程中若状态被置为 `PARSING` 后进程异常退出，该事件不会自动回到队列。
- 分块参数与 Milvus 集合结构尚未对齐，向量写入前还需要一层适配。
