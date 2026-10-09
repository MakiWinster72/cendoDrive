# AI 智能对话接口与配置

## 本期边界

- `/ai`：扣扣AI“智能对话”，连接真实模型服务，支持多轮提问、Markdown、停止等待、新建和页面内历史。
- `/ai?mode=search`：千度AI“文件智能搜索”的模式入口；原 `/ai-search` 重定向至此。
- 千度AI仅展示未来能力，不调用语义搜索。上传时索引文件名与内容 → AI 向量化 → 向量数据库 → 用户询问与内容匹配，是未来工作。本期不增加向量库、Embedding 服务或内容解析。
- 既有 [AI 索引事件契约](AI索引事件接口契约.md) 不变；索引事件投递不等于完成内容向量化或检索。

## 配置与部署

| 环境变量 | 后端配置 | 说明 |
| --- | --- | --- |
| `CENDO_AI_CHAT_BASE_URL` | `cendo.ai.chat.base-url` | HTTP(S) API 基础地址，如 `https://api.openai.com/v1` |
| `CENDO_AI_CHAT_MODEL` | `cendo.ai.chat.model` | 服务商可用的模型名称 |
| `CENDO_AI_CHAT_KEY` | `cendo.ai.chat.key` | 后端私有 Bearer 密钥 |

后端自动在基础地址追加 `/chat/completions`。基础地址不得包含账号密码、查询串或片段。生产环境应使用 HTTPS；HTTP 仅适用于可信本机测试。接口必须支持非流式 `messages` 请求，并返回 `choices[0].message.content` 文本；Responses API、纯工具调用或非文本响应不在支持范围。

三项均空时聊天不可用，其他模块正常；部分配置或非法地址会在启动时拒绝。配置齐全不等于外部模型健康；第一次发送才能实际验证凭据、权限与连通性。

Compose：根目录 `.env` 中填三项，重建/重启后端。宿主机 Maven：导出三项或修改已忽略的本地 `application.yml`，不会自动读取 `.env`。与 `CENDO_AI_ENABLED`、`ANNA_INDEX_BASE_URL` 独立，聊天不依赖索引服务。

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

成功返回 `{"content":"模型回复文本"}`。后端只向配置的地址发送 `model`、`messages` 与 `stream:false`，不接受客户端指定模型、服务地址、Key 或 system 消息。历史由客户端提供，仅用于当前上下文，不作为权限凭据。

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

模型的 401 映射为 502，不会误触发应用退出登录；错误不向浏览器转发模型响应体、密钥或内部诊断。后端连接超时 5 秒、读取超时 60 秒，前端请求超时 75 秒。

## 页面行为与验收

1. 登录，打开首页扣扣AI入口。确认顶部为“扣扣AI / 智能对话”，手机与桌面都有欢迎区和输入区。
2. 配好真实服务发送第一条问题，再追问，确认模型理解上下文。Enter 发送、Shift+Enter 换行，中文输入法组合按键不发送。
3. 打开历史并切换、新建对话。历史只保存在当前组件内存，刷新、离开或退出会清空，不保存至浏览器存储或后端。
4. 生成时停止/新建/切换模式：取消前端等待，恢复未成功的问题，迟到的响应不得污染新对话。**取消前端请求不保证取消上游推理或费用**。
5. 切换千度AI，确认只显示“文件智能搜索正在规划中”；无搜索输入或假结果。切回智能对话可以继续页面内的已完成对话。
6. 未配置、网络失败、服务商限流时给出清晰提示；失败后保留问题可重试，不重复插入消息。回答 Markdown 经过 HTML 清洗，禁止脚本、嵌入表单和外部图片追踪。

## 自动检查

```sh
cd frontend
npx vitest run src/api/aiChat.test.ts src/components/AiMessage.test.ts src/views/AiView.test.ts src/views/HomeView.test.ts
npm run build
# 另一个终端，从仓库根目录执行：
cd backend
mvn -Dtest=AiChatServiceTest,AiChatSecurityTest test
```

这些测试使用隔离模型替身，不需要真实 API Key；它们验证请求契约、身份验证、错误处理和交互，不等同于真实服务商验收。生产上线前仍须按前述步骤用获授权的模型凭据实测。
