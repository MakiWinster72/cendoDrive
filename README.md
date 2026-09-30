# 千度网盘（CendoDrive）

![CendoDrive 标志](frontend/public/cendo_logo_light.svg)

一个正在开发的网盘项目：使用 Vue 3 构建前端、Spring Boot 提供认证和文件管理 API。当前重点是账号登录、个人文件管理与回收站，分享等功能尚未接入后端。

## 功能现状

| 模块 | 状态 |
| --- | --- |
| 用户 | 注册、登录、查询当前用户、退出；使用 Redis 管理登录会话 |
| 文件 | 按目录列出、新建文件夹、重命名、移动、上传和下载；接口要求登录 |
| 回收站 | 移入、恢复、永久删除和清空 |
| 分享 | 前端有展示页面，后端分享接口尚未实现，请勿作为真实分享使用 |

文件元数据存储于 MySQL；新上传的文件使用 FastDFS，旧的本地存储文件仍可读取。单文件上传限制为 100 MB。

> [!WARNING]
> 当前工作区的 `frontend/src/stores/drive.ts` 和 `frontend/src/views/HomeView.vue` 尚有 Git 合并冲突。解决冲突后才能构建、运行或验证前端；本 README 不代表冲突已解决。

## 技术栈

- **前端**：Vue 3、TypeScript、Vite、Vue Router、Axios、Vitest
- **后端**：Java 21、Spring Boot 3.3、Spring Security、Spring Data JPA、Flyway、Maven
- **基础设施**：MySQL、Redis、FastDFS

## 本地运行

准备 Java 21、Maven、Node.js/npm、MySQL 和 Redis。需要使用上传/下载功能时，还须部署 FastDFS tracker 与 storage，并保证后端能访问两者（不只是 tracker）。先创建 `cendo` 数据库，为应用账户授予迁移建表及数据读写权限。

1. 启动后端：

   ```sh
   cd backend
   cp .env.example .env
   # 编辑 .env 中的数据库与 Redis 连接信息；使用 FastDFS 时设置 FASTDFS_TRACKER
   set -a; . ./.env; set +a
   mvn spring-boot:run
   ```

2. **解决上述合并冲突后**，另开终端启动前端：

   ```sh
   cd frontend
   npm ci
   npm run dev
   ```

前端默认地址为 `http://localhost:5173`，后端为 `http://localhost:8080`。Vite 将 `/api` 代理到后端；若后端使用其他地址，可设置 `VITE_API_PROXY_TARGET`。不要把密码、Token 等机密写入 `VITE_*` 变量，它们会暴露在浏览器中。`.env` 不要提交到版本库。

## 接口与测试

后端运行后可查看 [Swagger UI](http://localhost:8080/swagger-ui/index.html) 或 [OpenAPI JSON](http://localhost:8080/v3/api-docs)。主要入口为 `/api/auth`、`/api/user/me` 和 `/api/files`；访问受保护接口须携带 `Authorization: Bearer <token>`。

```sh
(cd backend && mvn test)
(cd frontend && npm test)
(cd frontend && npm run build)
```

前端测试和构建应在解决合并冲突后执行。数据库/Redis 集成测试的额外设置参见 [后端说明](backend/README.md)。

## 更多文档

- [认证与用户接口](docs/认证与用户接口文档.md)
- [文件管理接口](docs/文件管理接口文档.md)
- [详细设计](docs/CendoDrive详细设计.md)
- [后端说明](backend/README.md) · [前端说明](frontend/README.md)
