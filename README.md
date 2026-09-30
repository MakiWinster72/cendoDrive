<p align="center">
  <img src="./frontend/public/cendo_logo_light.svg" width="180" alt="CendoDrive 千度网盘标志">
</p>

<h1 align="center">千度网盘 · CendoDrive</h1>

<p align="center">
  Cendo 谐音千度，仿照百度网盘的一个web App
  <br>
  Vue 3 前端 + Spring Boot API，文件内容存储于 FastDFS。
</p>

## 产品一览

CendoDrive 面向个人文件的上传、归类和管理。前端提供文件列表及文件夹视图；后端负责身份校验、文件元数据、存储读写与回收站操作。目前仍处于开发阶段，分享页面不是可用的真实分享服务。

| 能力       | 当前实现                                           |
| ---------- | -------------------------------------------------- |
| 账号与会话 | 注册、登录、查询当前用户、退出；Redis 管理登录状态 |
| 文件整理   | 按目录浏览、新建文件夹、重命名和移动               |
| 文件传输   | 上传至 FastDFS、经鉴权下载；单文件上限 100 MB      |
| 回收站     | 移入、恢复、永久删除和清空                         |
| 分享       | 尚无后端分享接口；前端相关页面仅供展示             |

## 它如何工作

```text
浏览器（Vue 3）
    │  /api 请求、Bearer Token
    ▼
Spring Boot API
    ├── 认证与会话 ───── Redis
    ├── 用户和文件元数据 ─ MySQL / Flyway
    └── 文件上传与下载 ── FastDFS tracker + storage
```

文件接口按登录用户隔离数据；上传后元数据写入 MySQL，文件内容存入 FastDFS。下载经后端鉴权，不直接向浏览器公开存储服务地址。历史本地存储文件保留读取兼容。

## 快速开始

需要 **Docker Compose**。一键构建并启动前端、后端、MySQL `cendo`、Redis、FastDFS tracker 和三个 storage：

```sh
docker compose up -d --build
# 如端口已被现有容器占用，请先停止现有容器；不要删除原有数据卷。
```

打开 `http://localhost`（仅本机可访问）；后端 API 位于 `http://localhost:8080`，前端 Nginx 将 `/api/` 代理至后端。Compose 端口仅绑定宿主机回环地址；MySQL 开发账号为 `cendo` / `cendo_dev_password`。可在启动前通过 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD` 环境变量覆盖，后端自动使用相同的 MySQL 密码。数据存放在 Compose 命名卷；`docker compose down` 不删除数据，**不要执行 `down -v`**。更新代码后运行 `docker compose up -d --build`。已有独立容器占用端口时先停止旧容器，注意新命名卷不会自动迁移旧数据。

```sh
# 可选：在宿主机分别开发时，需 Java 21、Maven、Node.js/npm；只启动依赖：
# docker compose up -d mysql redis tracker storage1 storage2 storage3
# 终端 1：后端
cd backend
cp src/main/resources/application.example.yml src/main/resources/application.yml
# 示例默认 DB_USER=cendo、REDIS_HOST=localhost、FASTDFS_TRACKER=localhost:22122
# Compose 默认密码需同步设置；也可编辑本地 application.yml
DB_PASSWORD=cendo_dev_password mvn spring-boot:run
# 若已在 application.yml 设置密码，则直接运行 mvn spring-boot:run
```

```sh
# 终端 2：前端（从仓库根目录执行）
cd frontend
npm ci
npm run dev
```

打开 `http://localhost:5173`；后端默认监听 `http://localhost:8080`。开发服务器默认把 `/api` 代理到后端 8080；如需调整，设置 `VITE_API_PROXY_TARGET`。不要将密码或 Token 放入 `VITE_*` 环境变量，它们会暴露在浏览器中。本地配置文件不要提交到仓库。

> [!NOTE]
> Compose 面向本机开发，不提供生产级 HTTPS、访问控制或备份；默认密码不适用于生产。

## 技术架构

| 层级 | 技术                                                       |
| ---- | ---------------------------------------------------------- |
| Web  | Vue 3、TypeScript、Vite、Vue Router、Axios                 |
| API  | Java 21、Spring Boot 3.3、Spring Security、Spring Data JPA |
| 数据 | MySQL、Flyway、Redis                                       |
| 文件 | FastDFS（兼容读取历史本地文件）                            |
| 测试 | Vitest、JUnit / Spring Boot Test                           |

## 项目结构

```text
cendoDrive/
├── frontend/    Vue 页面、组件与 API 客户端
├── backend/     Spring Boot 接口、业务逻辑与数据库迁移
└── docs/        设计说明、运行记录和接口文档
```

## 接口与质量检查

后端启动后访问 [Swagger UI](http://localhost:8080/swagger-ui/index.html) 或 [OpenAPI JSON](http://localhost:8080/v3/api-docs)。主要接口包括 `/api/auth/*`、`/api/user/me`、`/api/files/*`；受保护接口需要 `Authorization: Bearer <token>`。

```sh
(cd backend && mvn test)
(cd frontend && npm test)
(cd frontend && npm run build)
```

需要连接数据库和 Redis 的集成测试配置参见 [后端说明](./backend/README.md)。
