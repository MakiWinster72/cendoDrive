# CendoDrive 后端

当前实现 Spring Boot 基础工程、用户认证、普通与分片上传、文件下载、回收站、复制、收藏、隐藏空间、详情、按类型整理、真实容量统计、限时分享及跨用户独立转存。前端位于 `../frontend`；AI 模块尚未实现。文件接口契约见 [文件管理接口文档](../docs/文件管理接口文档.md)。

文件生命周期现已持久化 AI 索引事件：普通上传和分片合并产生 `UPSERT`，回收站产生 `DEACTIVATE`，恢复再次产生更高版本的 `UPSERT`，永久删除产生 `DELETE`。事件由后台任务异步投递，不在文件请求线程调用索引服务；复制和分享转存本期不进入索引范围。Anna 的索引实现和语义搜索仍由独立服务负责，双方契约见 [AI 索引事件接口契约](../docs/AI索引事件接口契约.md)。

## 环境

Java 21、Maven 3.9+。整个项目可在仓库根目录通过 `docker compose up -d --build` 启动；仅需依赖服务时运行 `docker compose up -d mysql redis tracker storage1 storage2 storage3`；勿将真实密码提交到仓库。以 `src/main/resources/application.example.yml` 为配置示例。

```sh
cd backend
cp src/main/resources/application.example.yml src/main/resources/application.yml
# 按需修改 application.yml，或通过环境变量覆盖；Compose 默认 DB_PASSWORD=cendo_dev_password
JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH mvn test
DB_PASSWORD=cendo_dev_password JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH mvn spring-boot:run
```

首次启动时 Flyway 创建 `users`、`drive_files` 和 `share_links` 等表，并应用后续迁移。已执行过的迁移文件不要修改；表结构变更应新增版本迁移。默认监听 8080，使用 `PORT` 更改。MySQL 账户需要迁移所需的建表、索引及读写权限。生产部署应使用 TLS 与受控的 Redis 网络/凭据，避免公开数据库端口。

## 切换分支后的迁移校验

若启动提示 `Detected applied migration not resolved locally: 5.`，表示数据库已经执行 V5，但当前运行代码缺少该迁移。此分支保留原始 `src/main/resources/db/migration/V5__create_share_links.sql`，用于兼容已经执行过分享迁移的数据库；当前已实现分享接口，V11 在此表追加可选提取码散列（V9/V10 为聊天及聊天文件迁移）。

已应用迁移应在各分支保留原始文件和校验和；不要改写旧 SQL、删除 `flyway_schema_history` 记录或关闭校验来绕过错误，也不要仅因切换分支就运行 `repair`。先确认代码中包含原始迁移，再清理旧编译产物并重新启动：

```sh
# 在 backend 目录，使用前文相同的数据库环境变量
JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH mvn clean spring-boot:run
```

`FlywayMigrationTest` 使用独立 H2 数据库覆盖全新建库，以及历史 V1～V8 已执行后补齐 V9～V11、校验和分享数据保留，不连接或修改开发数据库。

## 群号

新建群使用固定五位小写十六进制群号（`00000`～`fffff`），保留前导零；搜索和加入支持大小写及首尾空白。数据库主键保证唯一，随机碰撞最多重试 32 次，分配失败返回 HTTP 503，可稍后重试。群号不是访问凭证，私密群仍不可通过搜索加入。已有 UUID 群号及私聊 ID 不改写，历史消息和链接保持有效。

## 聊天文件消息

私聊和群聊支持文本与文件消息。`POST /api/chat/rooms/{room}/files` 接收 `{"fileId":"42"}`，仅允许成员发送本人有效的普通云盘文件，不支持文件夹或隐藏/回收站文件。本地文件使用已有上传接口先上传到发送者云盘根目录，再发送文件消息；发送失败可重试已上传文件，不会重复上传。

`GET /api/chat/rooms/{room}/messages` 的文件消息附带 `attachment: {name, size}`。成员通过 `GET /api/chat/rooms/{room}/files/{messageId}` 获取可操作文件摘要，通过同路径的 `/download` 获取预览或下载内容；两个读取接口均禁止缓存，校验聊天成员、消息归属和源文件状态，不暴露发送者私有目录。

成员通过 `POST /api/chat/rooms/{room}/files/{messageId}/save` 转存，传入 `{"parentId":"8"}` 选择自己的目录；省略请求体或传入 `{"parentId":null}` 则保存到根目录。文件操作页支持图片、PDF、文本、Markdown、DOCX 和浏览器可播放视频预览；不支持的格式显示“不支持预览此文件”，仍可下载或转存。选择页支持目录路径、当前目录搜索、名称/最近修改排序，以及发送前确认。转存页仅显示文件夹，加载失败时禁止确认；成功后明确提示独立副本。服务端从该聊天的消息解析发送者及源文件，不接受客户端指定所有者；转存执行容量、同名冲突和源文件有效性检查，并复制为独立存储。源文件失效后不能再转存，但已经转存的副本不受影响。新加入的群成员可以转存可见历史消息中的文件。

V10 仅新增可空文件字段，旧文本消息保持兼容，不改写已应用迁移。`ChatFileWorkflowTest` 使用 H2 与内存存储替身验证上传、发送、越权拦截、独立转存及源文件删除后的副本下载。

## 文件名搜索

认证接口 `GET /api/files/search` 直接查询 MySQL 8 的文件元数据，无新增数据库迁移、环境变量、容器或搜索服务。参数、返回路径和权限规则见[文件管理接口文档](../docs/文件管理接口文档.md#文件名搜索)。

通过递归 CTE 先限定本人可见目录树，再按文件名 `LIKE`（字面量转义）、类型和目录范围筛选、计数与数据库分页。结果排序追加 ID；路径祖先只为当前页读取并缓存。图片、扫描 PDF、文档等都不读取内容或 OCR。

包含搜索 `%关键词%` 无法通常借助 B-tree 索引加速；此版面向现有网盘规模，不声称全文索引性能。目录递归受 MySQL `cte_max_recursion_depth`（默认 1000）限制，超深目录需按数据库运维策略评估。`FileSearchIntegrationTest` 在独立 H2 MySQL 模式下覆盖认证、用户隔离、隐藏/回收站后代、范围、类型、分页和通配符转义。

## 上传与容量配置

普通上传以流方式传给存储服务；大文件使用[分片上传协议](../docs/分片上传接口协议.md)。服务端持久化任务和分片，校验合并内容 MD5、所有者、目录状态、同名冲突和容量。分片最大 5 MiB、最多 2048 片，每用户最多 20 个未完成任务。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `CENDO_UPLOAD_ROOT` | `./storage/uploads` | 暂存分片和合并文件目录 |
| `CENDO_UPLOAD_TTL_HOURS` | `24` | 上传任务有效期（小时） |
| `CENDO_AI_ENABLED` | `false` | 是否向 Anna 投递已持久化的索引事件 |
| `ANNA_INDEX_BASE_URL` | 空 | Anna 内部服务地址；未配置时不投递、不丢弃任务 |
| `CENDO_AI_WORKER_DELAY_MS` | `5000` | 索引事件投递扫描间隔 |
| `CENDO_STORAGE_CLEANUP_DELAY_MS` | `30000` | 物理文件清理任务扫描间隔 |

暂存目录需可写且持久化；多实例部署必须共享该目录或采用会话亲和，不能把本地分片当成跨实例对象存储。定时清理过期任务与暂存文件。取消前端上传不立即删除服务端任务。

容量统计按账户汇总文件（含回收站）和未完成上传预留量，上传 / 复制在服务端持有账户锁检查额度。永久删除或清空回收站后立即更新额度，并持久化独立的存储清理任务。

索引任务、Chunk 和向量不写入 `drive_files`，因此不计入网盘容量。永久删除会在删除元数据的同一事务中创建独立的物理清理任务；FastDFS 或历史本地文件删除失败会记录错误并退避重试，达到上限后保留为 `DEAD` 供人工排查。

## API

- `POST /api/auth/register`：`{"username":"maki","password":"password123","nickname":"Maki"}` → 201；用户名唯一（忽略大小写），重复返回 409。
- `POST /api/auth/login`：`{"username":"maki","password":"password123"}` → 200，返回 `token`、`expiresInSeconds`（86400）和 `user`；错误密码或禁用账户返回 401。
- `GET /api/user/me`：携带 `Authorization: Bearer <token>` → 200，返回用户摘要。
- `POST /api/auth/logout`：携带相同凭证 → 204，并即时撤销该会话；旧凭证再次请求 `/me` 返回 401。

注册和登录响应不返回密码哈希。用户 ID 为十进制字符串，`createdAt` 为带 `Z` 的 UTC 时间。输入错误返回 `code/message/fields`，登录同一用户名在 15 分钟内失败 5 次后返回 `429`（`Retry-After: 900`）。这是基于用户名的简易限流：可能被用于阻碍特定用户登录，生产环境还应在可信网关实现按来源 IP 等维度的限流与监控。真实密码和 Token 不要粘贴进提交、文档或日志。

在线接口规范：`http://localhost:8080/v3/api-docs`，交互页面：`http://localhost:8080/swagger-ui/index.html`。运行中的旧服务不会自动加载新代码；重启后再查看。

## 账号管理与七天注销

资料、头像、改密、精确用户名查询和恢复接口见[账号管理接口文档](../docs/账号管理接口文档.md)。新增 V8 迁移，不修改历史迁移；启动后 Flyway 自动应用。用户 ID 仍由数据库自动分配，用户名和 ID 不因修改昵称而变化。

- 密码修改 / 注销 / 恢复均递增 `users.auth_version`。每次鉴权同时检查数据库的启用状态和版本，所有旧设备 Token 立即失效；不依赖 Redis 扫描删除。旧版仅含用户 ID 的会话只兼容版本 0。过期 Redis 会话由原有 TTL 自动清理。
- 注销立即标记 `active=false`、记录 UTC `deleted_at` 并撤销本人所有分享。7×24 小时内可凭原用户名和密码恢复，恢复后必须重新登录；旧分享和旧 Token 不恢复。期间用户名仍被占用，不能重新注册。
- 满 7 天后后台自动清理文件、隐藏 / 回收站文件、上传暂存、分享、头像和账号。账户锁防止与恢复 / 配额写操作并发冲突；先逐个物理删除文件并提交进度，再删除文件夹和账号，避免级联删除丢失存储键。存储失败保留尚未完成的记录，下次重试，不提前删库假装成功。FastDFS 已不存在的文件视为已清理；其他错误仍重试。已经转存给他人的独立副本不受影响。
- `cendo.user.cleanup-delay-ms` 默认 `3600000`（1 小时，启动后首次执行也延迟此间隔）；示例配置支持 `CENDO_ACCOUNT_CLEANUP_DELAY_MS`。实际物理清理发生在到期后的下一次任务，存储故障会进一步延迟；即使尚未清理，到期也无法恢复。服务必须保持运行，所有实例必须能访问相同存储和上传暂存目录。
- 头像只接受真实 PNG / JPEG，输入最多 2 MiB、尺寸最多 2048×2048；裁成 256×256 PNG 去除原图元数据，独立存数据库、不占网盘额度。头像读取需要登录，禁用用户不公开。

`UserAccountIntegrationTest` 使用 H2 执行真实 Flyway / JPA / MockMvc 流程，隔离替身模拟 Redis、时钟和文件存储；覆盖七天边界、跨用户隔离、会话撤销、失败重试及物理清理顺序。它不是生产 MySQL / Redis / FastDFS 联调。

## 真实基础设施验收

先确认本机现有 MySQL（默认容器 `cendo-mysql-local`）、Redis 6379、FastDFS tracker 22122 及存储节点正常，再执行：

```sh
sh scripts/verify-real-stack.sh
```

脚本读取容器现有 `MYSQL_ROOT_PASSWORD`，不打印密码，使用 Java 21，在 MySQL 新建 `cendo_acceptance_<时间>_<进程>` 数据库。`RealStackAcceptanceTest` 额外检查数据库前缀和 MySQL 类型，绝不连接默认开发库。通过真实 HTTP 和实际 Redis/FastDFS 验证账号资料/头像/全端改密、七天注销恢复与物理清理、分片上传/下载/跨用户隔离/配额、分享可选码的三入口/真实限流/过期/撤销和独立副本。只有测试账号会调整删除时间来覆盖到期边界。

成功时先清理生成的用户、FastDFS 文件及精确的测试 Redis Key，再删除临时数据库和空分片目录；不使用 FLUSHDB/SCAN、不修改用户已有账号。失败保留数据库和暂存路径，输出位置用于诊断，避免丢失未清理文件的存储键。`CENDO_MYSQL_CONTAINER` 可覆盖容器名，`CENDO_ACCEPTANCE_KEEP_DB=1` 可保留成功验收后的空库。其他服务连接仍由常规环境变量配置。

默认 `mvn test` 跳过需要外部依赖的验收；请勿把跳过误认为实栈通过。真实清理后的文件下载负向断言可能打印 FastDFS“找不到节点或文件”，这是确认物理删除的预期结果。FastDFS 多节点之间异步复制删除记录，因此物理下载负向断言最多等待 10 秒，不假定所有副本在删除确认瞬间已同步。

## 分享与转存

| 接口 | 访问条件 / 行为 |
| --- | --- |
| `POST /api/shares` | 登录；`{"fileId":42,"expiresInSeconds":86400}`，返回 201 |
| `GET /api/shares` | 登录；仅列出当前用户创建的分享 |
| `DELETE /api/shares/{id}` | 登录；仅允许创建者撤销，返回 204 |
| `GET /api/shares/{token}` | 匿名；返回有效分享的文件摘要和过期时间 |
| `GET /api/shares/{token}/download` | 匿名；经后端读取文件，不暴露 FastDFS 地址 |
| `POST /api/shares/{token}/save` | 登录；`{"parentId":null}` 保存到根目录，或指定本人的目标目录，返回 201 |

- 仅支持单文件分享，有效期为 1–2592000 秒（最长 30 天）。MySQL 保存归属和历史；Redis `share:access:<token>` 保存访问凭据并设置剩余有效期 TTL。访问同时校验数据库时间、撤销状态和 Redis 凭据；Redis Key 缺失不会被自动重建，按失效处理。
- 创建、列表和撤销按用户隔离；他人不能直接访问原文件的私有接口。创建者可选择提取码（4–16 位字母或数字），数据库仅存 BCrypt 散列、响应只给 `hasExtractionCode`。未设置码时持有效链接即可匿名访问；设置后详情、下载和转存都要求 `X-Share-Code`，缺失/错误返回 403，按链接与直接来源 IP 累计 5 次错误锁定 15 分钟（429 / Retry-After）。分享取消、到期、原文件删除或所在目录进入回收站后返回 `404 / SHARE_NOT_FOUND`。
- 转存创建接收者自己的元数据和独立存储副本；原作者随后撤销分享或永久删除原文件，不影响已转存的副本。流式下载到临时文件再上传，不将整份内容放入内存；重名返回 `409 / NAME_CONFLICT`，不覆盖原文件。前端目前转存到根目录，接口支持目标目录。
- 当前已有注册、登录、会话和数据隔离；尚无管理员用户管理界面或文件夹分享。上传、复制和分享转存统一检查账户配额，并计入未完成上传的预留容量。生产环境还应设置分享/转存限流及访问审计；链接属于访问凭据，不要记录完整 Token。

分享专项集成测试只需本机 Redis，不需要 MySQL/FastDFS：H2 执行 Flyway 迁移并验证 JPA，实际 Redis 验证 TTL 和跨用户流程，外部文件存储使用隔离测试替身。测试仅创建和清理随机专用 Key，不执行 `FLUSHDB`。

```sh
CENDO_TEST_REDIS=true JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH mvn -Dtest=ShareRedisIntegrationTest,ShareWorkflowIntegrationTest test
```

可选数据库与 Redis 认证集成测试（会临时创建用户、使用独立随机用户名并在测试后删除）：

```sh
DB_PASSWORD=cendo_dev_password RUN_INTEGRATION_TESTS=true JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH mvn -Dtest=AuthIntegrationTest test
```

生产环境仍需部署层加固：由反向代理/负载均衡终止 TLS 并强制 HTTPS；同源反向代理无需开放 CORS，确需跨源部署时设置 `CORS_ALLOWED_ORIGINS` 为逗号分隔的**精确**前端 Origin（如 `https://app.example.com`）。默认留空，不允许跨域；仅 `/api/**` 接受已配置来源的 GET、POST、PUT、DELETE、OPTIONS 及 Authorization、Content-Type、X-Share-Code 请求头，不使用 Cookie 凭证。开发环境继续使用 Vite 同源代理。Redis 只开放给应用内网，设置 ACL 账户及密码、限制密钥权限，并通过环境变量/密钥管理注入凭据。当前本地开发配置不等于生产安全配置。
