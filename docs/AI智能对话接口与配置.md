# AI 智能对话接口与配置

## 功能

- `/ai`：扣扣AI智能对话，支持流式回答、多轮提问、Markdown、停止生成及页面内历史。
- `/ai?mode=search`：扣扣Claw，描述为“根据网盘文件内容搜索文件”；原 `/ai-search` 重定向至此。
- 上传完成后，持久任务队列提取正文、分段、调用嵌入模型并写入 Qdrant。提问时向量检索当前用户的有效文档，将命中正文交给已配置的对话模型生成带 `[1]` 等引用的回答，页面显示文件名、路径和原文片段。
- V17 保存文档索引状态与任务。重启可恢复任务，失败自动重试；回收站、隐藏目录、删除、账号注销及重新索引通过代次和权限校验使旧内容不可检索。

## 配置与部署

| 环境变量 | 后端配置 | 说明 |
| --- | --- | --- |
| `CENDO_AI_CHAT_BASE_URL` | `cendo.ai.chat.base-url` | HTTP(S) API 基础地址，如 `https://api.openai.com/v1` |
| `CENDO_AI_CHAT_MODEL` | `cendo.ai.chat.model` | 服务商可用的模型名称 |
| `CENDO_AI_CHAT_KEY` | `cendo.ai.chat.key` | 后端私有 Bearer 密钥 |

后端自动在基础地址追加 `/chat/completions`。基础地址不得包含账号密码、查询串或片段。生产环境应使用 HTTPS；HTTP 仅适用于可信本机测试。页面要求接口支持 `stream:true` 的 SSE 文本增量（`choices[0].delta.content` 与正常完成标记）；兼容保留的非流式接口使用 `stream:false`，返回 `choices[0].message.content` 文本。Responses API、纯工具调用或非文本响应不在支持范围。

三项均空时聊天不可用，其他模块正常；部分配置或非法地址会在启动时拒绝。配置齐全不等于外部模型健康；第一次发送才能实际验证凭据、权限与连通性。

Compose：根目录 `.env` 中填三项，重建/重启后端。宿主机 Maven：导出三项或修改已忽略的本地 `application.yml`，不会自动读取 `.env`。聊天只需上表三项配置，不依赖索引服务。

密钥不得写入前端、`VITE_*`、文档、提交或日志。当前实现不配置用户计费额度或服务端聊天限流，公网部署前应在网关加限流、并发限制、用户额度及监控。模型服务商将收到提问与上下文，需按组织要求评估隐私和保留策略。

## 认证接口

两项均要求当前应用的 `Authorization: Bearer <登录Token>`，响应不缓存。浏览器不会拿到模型 Key 或 Base URL。

### `GET /api/ai/chat/status`

```json
{"configured": true, "model": "服务商模型名称"}
```

未配置时返回 `{"configured":false,"model":null}`。

### `POST /api/ai/chat`

```json
{
  "messages": [
    {"role":"user","content":"你好"},
    {"role":"assistant","content":"你好，有什么可以帮你？"},
    {"role":"user","content":"帮我规划今天的工作"}
  ]
}
```

成功返回 `{"content":"模型回复文本"}`。此非流式接口保留兼容，发送 `stream:false`。两种接口均不接受客户端指定模型、服务地址、Key 或 system 消息。历史由客户端提供，仅用于当前上下文，不作为权限凭据。

### `POST /api/ai/chat/stream`

请求 JSON 与上方一致，响应为 `text/event-stream`。后端向模型发送 `stream:true`，只转发经过解析的文本增量，不透传供应商元数据、错误正文或密钥：

```text
event:delta
data:{"content":"你好"}

event:delta
data:{"content":"，有什么可以帮你？"}

event:done
data:{}

```

流开始后的失败以 `event:error` 和 `{"code":"AI_UNAVAILABLE","message":"安全提示"}` 结束，不再发送 `done`。未配置、请求验证和本地线程池满等启动前错误仍使用普通 HTTP 503 / 400 / 429 和 `application/json` 响应，即使客户端仅接受 SSE；上游 401 在流内映射为 `AI_UPSTREAM_ERROR`，不是应用退出登录。缺少完成标记或模型因长度/过滤等提前终止时为 `AI_INCOMPLETE_RESPONSE`，不能当成完整回答。

单次回答最多 64000 个 UTF-16 字符，单个上游 SSE 行/事件最多 131072 字符。模型需支持 OpenAI 兼容 SSE 文本增量；纯工具调用、非文本和普通 JSON 响应不视为流式回答。请求使用独立有界线程池（4 个核心线程、最多 8 个线程、16 个排队位置），不替换文件下载使用的异步执行器。流最长等待 75 秒；停止或断开后，下一次写入发现断开会关闭上游响应，但不能保证服务商立即停止计费。

响应设置 `Cache-Control: no-store` 和 `X-Accel-Buffering: no`。仓库 Nginx 配置仅对该路径关闭响应缓冲和压缩，读取超时为 90 秒；部署到其他反向代理时也必须关闭该路径的缓冲。

限制：1–41 条消息，必须以 user 开始和结束，user / assistant 交替；每条非空且最多 8000 个 UTF-16 字符，总计最多 64000 个 UTF-16 字符。前端保留最近最多 20 轮历史；超总长度时按完整轮次移除最早历史；发给模型的历史回复裁至每条 8000 字符，页面完整展示不变。字符限制不等于模型 Token 限制，服务商仍可能拒绝上下文过长。

| 状态 | 错误码 | 意义 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` / `INVALID_JSON` / `AI_INVALID_HISTORY` | 输入不合法或对话顺序/长度超限 |
| 401 | 应用认证错误 | 登录失效或未登录 |
| 503 | `AI_NOT_CONFIGURED` | 管理员尚未配置模型 |
| 429 | `AI_RATE_LIMITED` | 模型服务商限流 |
| 502 | `AI_UPSTREAM_ERROR` | 模型服务商返回错误，包括密钥无效的 401 |
| 502 | `AI_UNAVAILABLE` | 连接/超时/解析失败 |
| 502 | `AI_INVALID_RESPONSE` | 未返回有效文本 |

非流式请求中模型的 401 映射为 502；流式请求中返回 `AI_UPSTREAM_ERROR` 错误事件。两者都不会误触发应用退出登录；错误不向浏览器转发模型响应体、密钥或内部诊断。后端连接超时 5 秒、读取超时 60 秒，前端请求超时 75 秒。

## 页面行为与验收

1. 登录，打开首页扣扣AI入口。确认顶部为“扣扣AI / 智能对话”，手机与桌面都有欢迎区和输入区。
2. 配好真实服务发送第一条问题，确认请求尚未结束时第一段文字已经显示，再追问确认模型理解上下文。Enter 发送、Shift+Enter 换行，中文输入法组合按键不发送。
3. 打开历史并切换、新建对话。历史只保存在当前组件内存，刷新、离开或退出会清空，不保存至浏览器存储或后端。
4. 生成时点击停止：已有非空回答保留并标记“回答已停止，内容未完成”，后续提问可以接着聊；尚无回答时恢复问题。新建/切换模式取消当前未完成回答并恢复原问题，迟到的增量不得污染其他对话。异常断线或缺失完成标记会移除半截回答、恢复问题供重试。**取消前端请求不保证取消上游推理或费用**。
5. 切换扣扣Claw，上传含预算的文档，等待状态为“已索引”，询问预算并检查回答、引用和原文片段。切回智能对话可以继续已完成对话。
6. 未配置、网络失败、服务商限流时给出清晰提示；失败后保留问题可重试，不重复插入消息。回答 Markdown 经过 HTML 清洗，禁止脚本、嵌入表单和外部图片追踪。

## 自动检查

```sh
cd frontend
npx vitest run src/api/aiChat.test.ts src/api/aiChatStream.test.ts src/components/AiMessage.test.ts src/views/AiView.test.ts src/views/HomeView.test.ts
npm run build
# 另一个终端，从仓库根目录执行：
cd backend
mvn -Dtest=AiChatServiceTest,AiChatSecurityTest,AiChatStreamTest test
```

这些测试使用隔离模型替身，不需要真实 API Key；它们验证请求契约、身份验证、错误处理和交互，不等同于真实服务商验收。生产上线前仍须按前述步骤用获授权的模型凭据实测。

## 扣扣Claw配置与接口

启动前在本地 `backend/src/main/resources/application.yml` 的 `cendo.ai.claw` 配置：

```yaml
cendo:
  ai:
    claw:
      enabled: true
      embedding:
        base-url: ${CENDO_AI_EMBEDDING_BASE_URL:}
        model: Qwen/Qwen3-VL-Embedding-8B
        key: ${CENDO_AI_EMBEDDING_KEY:}
        dimensions: 4096
      vector:
        base-url: http://localhost:6333
        key: ${CENDO_AI_VECTOR_KEY:}
        collection: cendo_claw
```

嵌入地址和密钥留空时继承 `cendo.ai.chat`，对话模型继续使用原配置。模型服务必须支持 OpenAI 兼容 `POST /embeddings`（字符串数组输入、`encoding_format:float`、带 `index` 的 `data[].embedding`），指定模型必须在服务商账户中可用；配置名称不会自动下载或启动模型。4096 维默认值来自 [Qwen 官方模型说明](https://huggingface.co/Qwen/Qwen3-VL-Embedding-8B)。返回维度必须与配置一致，变更嵌入地址、模型或维度会使用独立 collection，需要重新索引已有文档。

本地 `application.yml` 已忽略，不提交密钥；仓库的 `application.example.yml` 和 Compose 提供全部可配置项。Compose 设置 `CENDO_AI_CLAW_ENABLED=true`，自动使用内部 `qdrant:6333`；宿主机运行后端时，执行下方命令启动本地 Qdrant，并使用 `http://localhost:6333`。Qdrant 持久卷为 `qdrant-data`。

文档支持 UTF-8 TXT、MD、CSV、可提取文字的 PDF 和 DOCX。单文件最多 10 MB、正文 80000 字符；PDF 最多100页。扫描件、加密文件、不支持格式和超过限制的文件仍可正常上传，只是不参与内容问答。文档约按1000字符分段、重叠200字符；每批最多4段进行嵌入。文档正文发送给配置的嵌入服务，命中片段发送给对话服务。

以下接口均要求登录，身份来自当前用户，不能指定他人 owner：

| 接口 | 行为 |
| --- | --- |
| `GET /api/ai/claw/status` | 配置状态、就绪/处理中/失败/不支持/需重建数量，以及最多20个文档的状态 |
| `POST /api/ai/claw/ask` | `{"question":"预算是多少？","previousQuestion":"可选的上一问题"}`，返回 `content`、`matched`、`sources` |
| `POST /api/ai/claw/reindex` | `{"confirmed":true}`，为已有可见文档重建索引；单次最多200个 |
| `POST /api/ai/claw/files/{fileId}/reindex` | `{"confirmed":true}`，为单个可见文档重建索引 |

问题最多2000字符，上一个问题最多300字符，仅用于指代理解。检索最多5000个已索引文件，最多引用4个文件、每个文件2段。无匹配时明确说明未找到，不调用对话模型编造回答。问答采用非流式请求，最多4个并发；浏览器停止会取消等待。模型生成前后都再次验证来源可见性、代次、文件名和路径，防止删除或移动期间返回失效资料。

主要错误：`CLAW_NOT_CONFIGURED`（503）、`CLAW_BUSY`（429）、`CLAW_FILE_UNAVAILABLE`（404）、模型与向量服务不可用或响应非法（502）。前端保留失败问题，展示安全提示；不暴露模型密钥和服务诊断。

验证覆盖：上传持久索引、文件生命周期、用户隔离、过期代次过滤、并发索引任务、来源引用、文档解析、嵌入响应格式与维度、页面问答及重试。MySQL/Qdrant 实库专项测试需显式指定隔离测试服务，默认跳过。真实服务验收需同时验证嵌入模型和对话模型连通性，不能用模拟响应代替。

### 开发验证记录（2026-10-12）

- 前端现有340项测试通过；新增Claw页面3项测试通过，模式切换12项测试通过；生产构建通过。
- 后端全量回归通过：197项测试中182项通过，15项实库专项测试默认跳过；包含内容检索集成20项。迁移验证已同步到V17。
- 使用本地已配置凭据向真实服务发送两条合成文本：指定嵌入模型成功返回2条4096维向量；已配置对话模型依据合成预算资料返回正确金额与 `[1]` 引用。未上传私人文档用于实测。
- MySQL并发专项、真实Qdrant专项及完整部署后的上传问答人工验收未在本次验证中执行。

### 迁移编号兼容修复

本机共享数据库已经执行主仓库的 V14（传输记录）、V15（传输文件引用）、V16（分享提取码）。本分支保留这些历史迁移原文及校验和，Claw 文档与任务建表顺延到 V17。启动前运行 `mvn clean spring-boot:run` 清除旧 `target/classes` 中已改名的 V14 Claw 脚本。无需 Flyway repair、关闭校验或删除数据库数据。

修复验证：新增从冻结 V14–V16 历史迁移升级的回归测试；修复前复现校验失败，修复后三项迁移测试通过。本机真实 MySQL 成功执行 V17，日志确认后端正常启动。验证进程已停止，8080 端口已释放。

### 宿主机开发的向量服务

```sh
docker compose -f compose.yaml -f compose.local.yaml up -d qdrant
curl http://localhost:6333/healthz
```

`compose.local.yaml` 仅在本机回环地址开放6333端口；Qdrant使用持久卷。普通容器部署仍使用内部 `qdrant:6333`。`configured:true` 表示配置完整，不表示依赖服务健康；`CLAW_VECTOR_UNAVAILABLE` 表示向量服务连接、认证或请求失败。服务恢复后队列会按退避时间自动重试，若已达到重试上限，使用页面的单文件“确认重试”。

本机恢复验证：启动 Qdrant 并修正空 API Key 变量导致的401后，待重试文档成功转为 READY，生成20个片段；真实 Qdrant 专项测试通过。Compose的启动包装仅在 Key 为空时移除该变量，非空 Key 保留用于认证。

### 统一界面设计

扣扣AI与扣扣Claw共用欢迎组件及样式，使用同一套机器人、建议问题、消息气泡、底部输入框和圆形发送/停止按钮。Claw的索引管理位于输入框中的“文档状态”入口，来源片段位于回答下方的折叠卡片。两种模式均支持页面内历史、新建对话；切换模式保留已完成的会话，取消未完成请求。

验证：前端345项测试及生产构建通过；浏览器检查1280px桌面和390px手机布局，确认输入框尺寸与位置一致、手机无横向溢出，模式切换后Claw的来源仍保留。
