# CendoDrive 后端

当前实现 Spring Boot 基础工程、用户认证、普通与分片上传、文件下载、回收站、复制、收藏、隐藏空间、详情、按类型整理、真实容量统计、限时分享及跨用户独立转存。前端位于 `../frontend`；AI 模块尚未实现。文件接口契约见 [文件管理接口文档](../docs/文件管理接口文档.md)。

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

若启动提示 `Detected applied migration not resolved locally: 5.`，表示数据库已经执行 V5，但当前运行代码缺少该迁移。此分支保留原始 `src/main/resources/db/migration/V5__create_share_links.sql`，用于兼容已经执行过分享迁移的数据库；保留表结构不代表此分支已实现分享接口。

已应用迁移应在各分支保留原始文件和校验和；不要改写旧 SQL、删除 `flyway_schema_history` 记录或关闭校验来绕过错误，也不要仅因切换分支就运行 `repair`。先确认代码中包含原始迁移，再清理旧编译产物并重新启动：

```sh
# 在 backend 目录，使用前文相同的数据库环境变量
JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH mvn clean spring-boot:run
```

`FlywayMigrationTest` 使用独立 H2 数据库覆盖全新建库，以及历史 V1～V7 已执行后的校验和分享数据保留，不连接或修改开发数据库。

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

暂存目录需可写且持久化；多实例部署必须共享该目录或采用会话亲和，不能把本地分片当成跨实例对象存储。定时清理过期任务与暂存文件。取消前端上传不立即删除服务端任务。

容量统计按账户汇总文件（含回收站）和未完成上传预留量，上传 / 复制在服务端持有账户锁检查额度。永久删除或清空回收站后更新额度并在事务提交后尝试清理存储；清理失败记录日志，尚无持久重试队列。

## API

- `POST /api/auth/register`：`{"username":"maki","password":"password123","nickname":"Maki"}` → 201；用户名唯一（忽略大小写），重复返回 409。
- `POST /api/auth/login`：`{"username":"maki","password":"password123"}` → 200，返回 `token`、`expiresInSeconds`（86400）和 `user`；错误密码或禁用账户返回 401。
- `GET /api/user/me`：携带 `Authorization: Bearer <token>` → 200，返回用户摘要。
- `POST /api/auth/logout`：携带相同凭证 → 204，并即时撤销该会话；旧凭证再次请求 `/me` 返回 401。

注册和登录响应不返回密码哈希。用户 ID 为十进制字符串，`createdAt` 为带 `Z` 的 UTC 时间。输入错误返回 `code/message/fields`，登录同一用户名在 15 分钟内失败 5 次后返回 `429`（`Retry-After: 900`）。这是基于用户名的简易限流：可能被用于阻碍特定用户登录，生产环境还应在可信网关实现按来源 IP 等维度的限流与监控。真实密码和 Token 不要粘贴进提交、文档或日志。

在线接口规范：`http://localhost:8080/v3/api-docs`，交互页面：`http://localhost:8080/swagger-ui/index.html`。运行中的旧服务不会自动加载新代码；重启后再查看。

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
- 创建、列表和撤销按用户隔离；他人不能直接访问原文件的私有接口。持有有效链接的人可匿名查看/下载，无提取码。分享取消、到期、原文件删除或所在目录进入回收站后返回 `404 / SHARE_NOT_FOUND`。
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

生产环境仍需部署层加固：由反向代理/负载均衡终止 TLS 并强制 HTTPS；同源反向代理无需开放 CORS，确需跨源部署时设置 `CORS_ALLOWED_ORIGINS` 为逗号分隔的**精确**前端 Origin（如 `https://app.example.com`）。默认留空，不允许跨域；仅 `/api/**` 接受已配置来源的 GET、POST、PUT、DELETE、OPTIONS 及 Authorization、Content-Type 请求头，不使用 Cookie 凭证。开发环境继续使用 Vite 同源代理。Redis 只开放给应用内网，设置 ACL 账户及密码、限制密钥权限，并通过环境变量/密钥管理注入凭据。当前本地开发配置不等于生产安全配置。
