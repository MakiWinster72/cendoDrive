# CendoDrive Docker Compose 部署

本文使用仓库根目录的 `compose.yaml` 一次启动前端、后端、MySQL、Redis、FastDFS（三个 storage）和 Qdrant。宿主机无需安装 JDK、Maven、Node.js、MySQL 或 Redis，只需 Docker Engine 与 Docker Compose v2。

> 当前 Compose 配置面向本机开发和内网验收，不是可直接暴露公网的生产方案。

## 1. 前置检查

```sh
docker version
docker compose version
```

后续命令均在仓库根目录 `cendoDrive/` 执行。默认使用以下宿主机端口：`80`、`8080`、`3306`、`6379`、`6333`、`22122`、`23000`～`23002`、`10001`～`10003`。启动前先确认没有冲突。

## 2. 创建部署配置

复制环境变量模板：

```powershell
Copy-Item .env.example .env
```

Linux/macOS/Git Bash：

```sh
cp .env.example .env
```

编辑 `.env`，至少修改：

```dotenv
MYSQL_PASSWORD=请替换为强密码
MYSQL_ROOT_PASSWORD=请替换为另一个强密码
```

`.env` 已被 Git 忽略，不要提交。注意：已经初始化的 MySQL 数据卷不会因修改 `.env` 自动改变数据库密码；密码变更需要按 MySQL 运维流程执行。

AI 为可选功能。普通聊天需同时填写：

```dotenv
CENDO_AI_CHAT_BASE_URL=https://模型服务地址/v1
CENDO_AI_CHAT_MODEL=模型名称
CENDO_AI_CHAT_KEY=服务端密钥
```

启用扣扣 Claw 时再填写：

```dotenv
CENDO_AI_CLAW_ENABLED=true
CENDO_AI_EMBEDDING_BASE_URL=https://嵌入服务地址/v1
CENDO_AI_EMBEDDING_MODEL=Qwen/Qwen3-VL-Embedding-8B
CENDO_AI_EMBEDDING_KEY=服务端密钥
CENDO_AI_EMBEDDING_DIMENSIONS=4096
```

嵌入服务地址或密钥留空时会继承聊天配置。维度必须与模型实际输出一致。未使用 AI 时保持相关值为空、`CENDO_AI_CLAW_ENABLED=false`。

## 3. 校验并启动

先检查 Compose 语法和变量替换，再构建、启动：

```sh
docker compose config --quiet
docker compose up -d --build
```

启动的服务如下：

| 服务 | 作用 | 数据卷 |
| --- | --- | --- |
| `frontend` | Nginx 托管前端并代理 `/api/` | — |
| `backend` | Spring Boot API | `backend-storage` |
| `mysql` | 元数据和业务数据 | `mysql-data` |
| `redis` | 登录会话等缓存数据 | `redis-data` |
| `tracker` | FastDFS 调度 | `tracker-data` |
| `storage1`～`storage3` | FastDFS 文件存储 | 各自独立 storage 卷 |
| `qdrant` | Claw 向量数据 | `qdrant-data` |

## 4. 检查运行状态

```sh
docker compose ps
docker compose logs --tail=100 backend
docker compose exec redis redis-cli ping
docker compose exec tracker fdfs_monitor /etc/fdfs/client.conf
```

验收标准：

- `mysql`、`redis` 为 healthy。
- 后端日志出现 `Started CendoDriveApplication`，无 Flyway 或依赖连接错误。
- Redis 返回 `PONG`。
- 三个 FastDFS storage 均为 `ACTIVE`，磁盘空间充足。
- 启用 Claw 时，`curl http://localhost:6333/healthz` 成功，并实际完成一次文档索引与检索。

打开：

- 前端：`http://localhost`
- 后端 API：`http://localhost:8080`
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`

前端容器内的 Nginx 已把 `/api/` 代理到后端，并为上传设置 100 MB 限制、为 AI 流式响应关闭缓冲。

## 5. 日常操作

```sh
# 查看日志
docker compose logs -f backend frontend

# 更新代码后重建应用
docker compose up -d --build backend frontend

# 停止但保留容器和数据
docker compose stop

# 恢复
docker compose start

# 删除容器和网络，保留命名卷
docker compose down
```

**不要执行 `docker compose down -v`**，它会删除数据库、Redis、Qdrant、FastDFS 文件以及后端暂存数据。

## 6. 数据备份与更新

更新前至少备份：

- MySQL：使用 `mysqldump` 生成逻辑备份。
- FastDFS：一致性备份 tracker 和三个 storage 的数据卷。
- Qdrant：启用 Claw 时使用 Qdrant snapshot 或停机备份数据卷。
- `backend-storage`：包含历史本地文件兼容数据和未完成上传分片。

仅复制源码不会迁移任何业务数据。跨主机迁移 FastDFS 时不能只复制单个文件目录，需要连同节点配置和数据库元数据一起规划。

更新流程：

```sh
git pull
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 backend
```

Flyway 会在后端启动时自动应用新增迁移。不要修改已经在环境中执行过的历史迁移脚本。

## 7. 只启动基础依赖

需要在宿主机运行前后端时，可只启动中间件：

```sh
docker compose up -d mysql redis tracker storage1 storage2 storage3 qdrant
```

此时按[《本地环境部署》](本地环境部署.md)配置并启动前后端。不要同时再启动另一套占用相同端口的 MySQL、Redis 或 FastDFS。

## 8. 常见问题

- 端口占用：用 `.env` 的 `MYSQL_HOST_PORT`、`REDIS_HOST_PORT` 修改数据库和 Redis 端口；其他端口需编辑 `compose.yaml`，并同步修改客户端配置。
- 后端一直重启或退出：查看 `docker compose logs backend`，重点检查数据库密码、AI 三项配置是否完整。
- 上传失败：首次启动要等待 storage 注册完成；用 `fdfs_monitor` 检查 `ACTIVE` 和剩余磁盘。
- FastDFS 错误码 28：默认保留磁盘 5%，可在 `.env` 合理调整 `FDFS_RESERVED_STORAGE_SPACE`，然后重建 tracker、重启 storage。
- 修改 `.env` 后未生效：运行 `docker compose up -d --force-recreate <服务名>`；单纯 `restart` 不会更新容器环境变量。
- Qdrant 显示 Up 但 Claw 不可用：Up 只代表进程存在，还要检查健康接口、嵌入模型配置和实际索引请求。

## 9. 生产部署注意事项

正式环境至少还应：

1. 使用固定版本镜像，避免 `latest` 漂移。
2. 在反向代理或负载均衡终止 TLS，并强制 HTTPS。
3. 只对外开放 80/443；MySQL、Redis、FastDFS、Qdrant 置于内网。
4. 使用 Secret 管理数据库密码和 API Key，不依赖明文 `.env` 长期存储。
5. 配置资源限制、日志轮转、健康检查、监控告警和定期恢复演练。
6. 跨源部署时，将 `CORS_ALLOWED_ORIGINS` 设置为精确的前端 Origin，不能使用 `*`。
