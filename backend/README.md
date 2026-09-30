# CendoDrive 后端

当前实现 Spring Boot 基础工程和用户注册、登录、退出、查询当前用户；尚无前端、文件模块或 AI 模块。

## 环境

Java 21、Maven 3.9+。整个项目可在仓库根目录通过 `docker compose up -d --build` 启动；仅需依赖服务时运行 `docker compose up -d mysql redis tracker storage1 storage2 storage3`；勿将真实密码提交到仓库。以 `src/main/resources/application.example.yml` 为配置示例。

```sh
cd backend
cp src/main/resources/application.example.yml src/main/resources/application.yml
# 按需修改 application.yml，或通过环境变量覆盖；Compose 默认 DB_PASSWORD=cendo_dev_password
JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH mvn test
DB_PASSWORD=cendo_dev_password JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH mvn spring-boot:run
```

首次启动时 Flyway 创建 `users` 表。默认监听 8080，使用 `PORT` 更改。MySQL 账户需要迁移所需的建表、索引及读写权限。生产部署应使用 TLS 与受控的 Redis 网络/凭据，避免公开数据库端口。

## API

- `POST /api/auth/register`：`{"username":"maki","password":"password123","nickname":"Maki"}` → 201；用户名唯一（忽略大小写），重复返回 409。
- `POST /api/auth/login`：`{"username":"maki","password":"password123"}` → 200，返回 `token`、`expiresInSeconds`（86400）和 `user`；错误密码或禁用账户返回 401。
- `GET /api/user/me`：携带 `Authorization: Bearer <token>` → 200，返回用户摘要。
- `POST /api/auth/logout`：携带相同凭证 → 204，并即时撤销该会话；旧凭证再次请求 `/me` 返回 401。

注册和登录响应不返回密码哈希。用户 ID 为十进制字符串，`createdAt` 为带 `Z` 的 UTC 时间。输入错误返回 `code/message/fields`，登录同一用户名在 15 分钟内失败 5 次后返回 `429`（`Retry-After: 900`）。这是基于用户名的简易限流：可能被用于阻碍特定用户登录，生产环境还应在可信网关实现按来源 IP 等维度的限流与监控。真实密码和 Token 不要粘贴进提交、文档或日志。

在线接口规范：`http://localhost:8080/v3/api-docs`，交互页面：`http://localhost:8080/swagger-ui/index.html`。运行中的旧服务不会自动加载新代码；重启后再查看。

可选数据库与 Redis 集成测试（会临时创建用户、使用独立随机用户名并在测试后删除）：

```sh
DB_PASSWORD=cendo_dev_password RUN_INTEGRATION_TESTS=true JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH mvn -Dtest=AuthIntegrationTest test
```

生产环境仍需部署层加固：由反向代理/负载均衡终止 TLS 并强制 HTTPS；同源反向代理无需开放 CORS，确需跨源部署时设置 `CORS_ALLOWED_ORIGINS` 为逗号分隔的**精确**前端 Origin（如 `https://app.example.com`）。默认留空，不允许跨域；仅 `/api/**` 接受已配置来源的 GET、POST、OPTIONS 及 Authorization、Content-Type 请求头，不使用 Cookie 凭证。开发环境继续使用 Vite 同源代理。Redis 只开放给应用内网，设置 ACL 账户及密码、限制密钥权限，并通过环境变量/密钥管理注入凭据。当前本地开发配置不等于生产安全配置。
