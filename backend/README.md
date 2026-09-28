# CendoDrive 后端

当前实现 Spring Boot 基础工程和用户注册、登录、退出、查询当前用户；尚无前端、文件模块或 AI 模块。

## 环境

Java 21、Maven 3.9+、MySQL、Redis。当前本机已有 `cendo-mysql`（3306，数据库 `cendo`）与 `cendo-redis`（6379）；MongoDB 暂不使用。请创建只授权 `cendo` 数据库的应用用户，勿将密码提交到仓库。

```sh
cd backend
cp .env.example .env
# 编辑 .env 配置 DB_USER、DB_PASSWORD 等；.env 已加入 .gitignore
set -a; . ./.env; set +a
JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH mvn test
JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH mvn spring-boot:run
```

首次启动时 Flyway 创建 `users` 表。默认监听 8080，使用 `PORT` 更改。MySQL 账户需要迁移所需的建表、索引及读写权限。生产部署应使用 TLS 与受控的 Redis 网络/凭据，避免公开数据库端口。

## API

- `POST /api/auth/register`：`{"username":"maki","password":"password123","nickname":"Maki"}` → 201；用户名唯一（忽略大小写），重复返回 409。
- `POST /api/auth/login`：`{"username":"maki","password":"password123"}` → 200，返回 `token`、`expiresInSeconds`（86400）和 `user`；错误密码或禁用账户返回 401。
- `GET /api/user/me`：携带 `Authorization: Bearer <token>` → 200，返回用户摘要。
- `POST /api/auth/logout`：携带相同凭证 → 204，并即时撤销该会话；旧凭证再次请求 `/me` 返回 401。

注册和登录响应不返回密码哈希。真实密码和 Token 不要粘贴进提交、文档或日志。本阶段尚未实现登录限流、端到端集成测试和生产部署加固。
