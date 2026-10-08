# 使用 Docker 和 Docker Compose 运行

本文用于在新设备上运行 CendoDrive，提供两种等价的部署方式：

- **Docker Compose**：自动组织服务、网络、数据卷和启动依赖，推荐使用。
- **纯 Docker**：手动执行 `docker build`、`docker run`，不需要 Compose 插件。

两种方式都把前后端、MySQL、Redis、FastDFS 放进容器，因此宿主机不需要安装 Java、Maven 或 Node.js。本文是**本机开发环境**说明，不是公网生产部署方案。

## 一、准备工作

1. 安装并启动 Docker；使用 Compose 时还需要 Docker Compose v2 插件。
2. 获取包含最新修复的源码，确认存在 `infra/fastdfs/tracker-entrypoint.sh`。
3. 在仓库根目录执行后续命令。命令使用 POSIX Shell 语法；Windows 可在 WSL 终端执行。ARM 设备应先确认所用镜像支持目标架构。

```sh
docker version
# 仅 Compose 方式需要：
docker compose version
```

通过 Git 获取源码时，将下面的 `仓库地址` 替换为实际 URL：

```sh
git clone '仓库地址'
cd cendoDrive
# 当前预览和 FastDFS 修复位于 feat/preview；按实际发布分支选择。
git switch feat/preview
```

只有已经推送的提交才能在新设备拉取到。**Git 只同步代码，不迁移账号、已上传文件或 Docker 数据卷。**

以下容器名称和数据卷名称用于首次创建。如果已有同名容器，先检查它的配置和数据归属，不要直接删除重建。两种方案不要同时启动，以免占用相同的 `80`、`8080` 端口。

## 二、使用 Docker Compose

### 2.1 前端服务与访问配置

Compose 前端使用 Dockerfile 的 Node 构建阶段，运行 `vite preview` 提供构建后的页面；通过 `VITE_API_PROXY_TARGET=http://backend:8080` 将 `/api` 代理至后端。访问地址仍为 `http://localhost`，无需 Nginx 或 `compose.local.yaml`。

Vite Preview 仅用于本机开发/验收，不是生产服务器。页面与 API 同源访问，无需额外配置后端 CORS。

旧部署不要再加载 `compose.local.yaml` 中的 Nginx `command` 覆盖。以下命令只使用仓库配置；保留原项目名和环境变量。纯 Docker 方式仍使用第 3 节的 Nginx 方案；FastDFS 镜像不受本次前端调整影响。

### 2.2 构建并启动

```sh
docker compose config --quiet
docker compose up -d --build
```

会启动：

| 服务 | 作用 |
| --- | --- |
| frontend | Vite Preview 静态页面及 `/api/` 代理 |
| backend | Spring Boot API、Flyway 数据库迁移 |
| mysql | 账号与文件元数据 |
| redis | 登录会话等 Redis 数据 |
| tracker | FastDFS 节点调度 |
| storage1、storage2、storage3 | 同一 group 的文件存储副本 |

MySQL 首次初始化会创建 `cendo` 数据库和 `cendo` 用户，不需要手动创建账号。容器后端使用 `application.example.yml` 作为基线，并由环境变量覆盖，不需要复制本机的 `application.yml`。

默认开发密码是 `cendo_dev_password`，root 密码是 `cendo_dev_root_password`。首次启动前可通过 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD` 覆盖，Compose 会将应用密码同时传给 MySQL 和后端。**已有数据库卷不会因为修改环境变量就自动修改账号密码。**

### 2.3 等待依赖就绪并访问

```sh
docker compose ps
docker compose logs --tail=100 backend
docker compose exec redis redis-cli ping
docker compose exec tracker fdfs_monitor /etc/fdfs/client.conf
```

确认：

- Redis 返回 `PONG`。
- 后端日志出现 `Started CendoDriveApplication`，没有数据库连接或迁移错误。
- FastDFS 的三个 storage 均为 `ACTIVE`，并且 `disk available space` 大于待上传文件大小。storage 的 `service_started` 只表示进程已启动，不代表已经注册完成，首次启动要等待它们就绪。

然后打开 **http://localhost**；后端直接访问地址为 `http://localhost:8080`。

当前 Compose 还会绑定宿主机的 `3306`、`6379`、`22122`、`23000`–`23002`、`10001`–`10003`，全部仅绑定回环地址。已有独立容器占用这些端口时，应先确认数据归属，再停止冲突服务；新建 Compose 卷不会自动继承旧容器的数据。

### 2.4 更新、停止与再次启动

后端已挂载命名卷 `backend-storage` 到 `/app/storage`：

| 环境变量 | 容器内路径 | 用途 |
| --- | --- | --- |
| `CENDO_STORAGE_ROOT` | `/app/storage` | 读取、删除历史本地存储文件 |
| `CENDO_UPLOAD_ROOT` | `/app/storage/uploads` | 保存未完成上传的临时分片 |

新文件最终存入 FastDFS，而不是 `CENDO_STORAGE_ROOT`。分片目录放在同一个卷中，重建后端容器不会丢失尚未过期的分片；上传会话元数据则保存在 MySQL 中。后端启动时由 Flyway 自动应用新增数据库迁移，更新前应先备份数据库和文件数据。

**旧部署首次增加这个卷时，先迁移再重建。** 新卷不会自动继承旧容器可写层中的 `/app/storage`。如果有历史本地文件或未完成上传，停止上传并执行：

```sh
docker compose stop backend
# 备份目录应为空；备份可能包含用户文件，请妥善保管。
mkdir -p backend-storage-backup
old_backend=$(docker compose ps -aq backend)
docker cp "$old_backend:/app/storage/." ./backend-storage-backup/
```

若 `docker cp` 报目录不存在，先确认旧配置是否使用其他路径；不要在未确认数据位置时直接重建。如果确认从未产生本地文件或分片，可跳过迁移。

备份成功后，先创建但不启动新后端，将备份复制到它挂载的卷中，再执行下面的更新命令：

```sh
docker compose build backend
docker compose create --no-deps backend
new_backend=$(docker compose ps -aq backend)
docker cp ./backend-storage-backup/. "$new_backend:/app/storage/"
```

以上操作必须使用原部署的项目名、Compose 文件和环境变量，避免意外创建另一套数据卷。纯 Docker 迁移时，将容器名换为 `cendo-backend`：先备份旧容器，再按 3.3 创建挂载 `cendo-backend-storage` 的新容器（暂用 `docker create` 替代 `docker run -d`），复制备份后用 `docker start cendo-backend` 启动。

```sh
# 更新代码后重新构建前后端：
docker compose up -d --build

# 暂停容器，保留容器和数据卷：
docker compose stop

# 恢复已暂停的容器：
docker compose start
```

`docker compose ... down` 会删除容器和网络，但默认保留命名卷。**不要执行 `down -v`**，它会删除数据库、Redis、后端本地文件/分片和 FastDFS 数据卷。

## 三、只使用 Docker，不使用 Compose

本方案创建独立的 `cendo-net` 网络。数据库和 FastDFS 不向宿主机暴露端口，只有前后端分别绑定本机的 `80`、`8080`。

### 3.1 创建网络，启动 MySQL 和 Redis

```sh
docker network create cendo-net

docker run -d --name cendo-mysql --network cendo-net \
  -e MYSQL_DATABASE=cendo \
  -e MYSQL_USER=cendo \
  -e MYSQL_PASSWORD=cendo_dev_password \
  -e MYSQL_ROOT_PASSWORD=cendo_dev_root_password \
  -v cendo-mysql-data:/var/lib/mysql \
  mysql:8.4

docker run -d --name cendo-redis --network cendo-net \
  -v cendo-redis-data:/data \
  redis:7 redis-server --appendonly yes
```

纯 Docker 示例固定 MySQL 8.4、Redis 7；Compose 使用仓库当前的镜像配置。不要将其他 MySQL 大版本的数据卷直接用于这些命令，尤其不要对已有数据库卷直接降级。

检查 MySQL 已完成初始化、数据库可访问，Redis 已就绪：

```sh
docker logs --tail=100 cendo-mysql
docker exec cendo-mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -Nse "SELECT 1" cendo'
docker exec cendo-redis redis-cli ping
```

分别返回 `1` 和 `PONG` 后再启动后端。如果 MySQL 仍在初始化，稍后再检查；不要只根据容器状态 `Up` 判断数据库已就绪。

### 3.2 启动 FastDFS tracker 和三个 storage

```sh
docker run -d --name cendo-tracker --network cendo-net \
  -e FDFS_RESERVED_STORAGE_SPACE=5% \
  -v cendo-tracker-data:/var/fdfs \
  -v "$PWD/infra/fastdfs/tracker-entrypoint.sh:/cendo/tracker-entrypoint.sh:ro" \
  --entrypoint /bin/sh \
  ygqygq2/fastdfs-nginx:latest /cendo/tracker-entrypoint.sh tracker

for i in 1 2 3; do
  docker run -d --name "cendo-storage$i" --network cendo-net \
    -e TRACKER_SERVER=cendo-tracker:22122 \
    -v "cendo-storage$i-data:/var/fdfs" \
    ygqygq2/fastdfs-nginx:latest storage
done
```

关键点：

- tracker 使用仓库的启动脚本，在原镜像入口前设置空间预留比例。
- storage 命令末尾必须明确传入 `storage`；该镜像默认启动 tracker，省略角色会导致没有真正的 storage 节点。
- `TRACKER_SERVER` 使用同一 Docker 网络中的容器名，不是 `localhost`。

等待三个节点注册并且有可上传空间：

```sh
docker exec cendo-tracker fdfs_monitor /etc/fdfs/client.conf
```

### 3.3 构建并启动后端

```sh
docker build -t cendo-backend:local ./backend

docker run -d --name cendo-backend \
  --network cendo-net --network-alias backend \
  -p 127.0.0.1:8080:8080 \
  -e 'DB_URL=jdbc:mysql://cendo-mysql:3306/cendo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -e DB_USER=cendo \
  -e DB_PASSWORD=cendo_dev_password \
  -e REDIS_HOST=cendo-redis \
  -e FASTDFS_TRACKER=cendo-tracker:22122 \
  -e CENDO_STORAGE_ROOT=/app/storage \
  -e CENDO_UPLOAD_ROOT=/app/storage/uploads \
  -v cendo-backend-storage:/app/storage \
  -e 'CORS_ALLOWED_ORIGINS=http://localhost,http://127.0.0.1' \
  cendo-backend:local

docker logs --tail=100 cendo-backend
```

`--network-alias backend` 不能省略：当前前端 Nginx 固定代理到 `http://backend:8080`。等待后端出现 `Started CendoDriveApplication` 后启动前端。

### 3.4 构建并启动前端

```sh
docker build -t cendo-frontend:local ./frontend

docker run -d --name cendo-frontend --network cendo-net \
  -p 127.0.0.1:80:80 \
  --entrypoint /bin/sh \
  cendo-frontend:local -c \
  'sed -i "/listen 80;/a client_max_body_size 100m;" /etc/nginx/conf.d/default.conf; exec nginx -g "daemon off;"'
```

这里将容器中的 Nginx 请求大小限制调整为 `100m`，无需修改源码。后端目前的上传文件和请求限制均为 `100MB`，请求还包含 multipart 开销；不应仅通过调大 Nginx 限制来绕过后端限制。

打开 **http://localhost**。如果改成 `8081` 等其他前端端口，也要相应修改后端的 `CORS_ALLOWED_ORIGINS`，例如允许 `http://localhost:8081`。

### 3.5 停止、启动和更新

```sh
# 停止应用，再停止依赖；不会删除数据卷：
docker stop cendo-frontend cendo-backend
docker stop cendo-storage1 cendo-storage2 cendo-storage3 cendo-tracker cendo-redis cendo-mysql

# 再次运行时不必重新 docker run：
docker start cendo-mysql cendo-redis cendo-tracker
docker start cendo-storage1 cendo-storage2 cendo-storage3
# 按 3.1、3.2 检查依赖就绪后：
docker start cendo-backend
# 后端就绪后：
docker start cendo-frontend
```

如果旧后端没有挂载本地存储卷，先按 2.4 的方法停止并备份 `/app/storage`，再将备份导入 `cendo-backend-storage` 卷；不要直接删除旧容器。

更新前后端代码后，先重新执行对应的 `docker build`，再停止并删除**应用容器** `cendo-backend`、`cendo-frontend`，然后用 3.3、3.4 的完整参数重新 `docker run`。仅 `docker restart` 不会切换到新构建的镜像。不要删除数据库或 FastDFS 卷，也不要把删除应用容器的操作套用到不明来源的已有容器。

## 四、FastDFS 空间策略与常见问题

### 节点 ACTIVE，但上传仍失败

`ACTIVE` 只表示节点在线。镜像原默认预留磁盘的 **20%**；如果实际空闲低于预留阈值，就会报：

```text
错误码：28，错误信息：没有足够的存储空间
```

仓库脚本默认预留 **5%**，`FDFS_RESERVED_STORAGE_SPACE` 仅接受整数 `1%`–`99%`，不允许零预留。磁盘仍必须留出足够的真实空间；不要为继续上传而关闭容量保护。

三个 storage 位于同一 group，是副本关系，不是三个独立容量池。若都位于同一宿主机磁盘，实际空间消耗也共享同一块磁盘，不应把可用容量简单相加。

Compose 修改预留比例后，需要重建 tracker，并让 storage 重新获取策略：

```sh
# 例如临时设置为 10%；后续重建时也需保留同样设置，可写入本地 .env。
FDFS_RESERVED_STORAGE_SPACE=10% docker compose up -d --no-deps tracker
docker compose restart storage1 storage2 storage3
```

纯 Docker 的环境变量在创建容器时设置。调整比例需用新的 `-e FDFS_RESERVED_STORAGE_SPACE=...` 重建 tracker 容器，继续挂载原 `cendo-tracker-data` 卷，再重启三个 storage。`docker restart` 本身不会修改环境变量。

### 错误码 2：找不到节点或文件

如果发生在上传的 `getStoreStorage` 阶段，先检查三个 storage 是否真的运行了 `storage` 角色、是否注册到同一个 tracker/group。tracker 下没有 storage 时也会出现此错误，不能一概认为是某个已上传文件丢失。

### 下载或预览出现鉴权异常

源码需要包含异步下载鉴权修复。更新源码后，容器运行方式必须重新构建并重建后端容器；本机运行方式则需重启后端进程。匿名文件下载仍应返回 `401`。

### 登录返回 403，或上传返回 413

- `403`：检查后端允许来源是否包含浏览器实际的协议、主机和端口。
- `413`：Compose 方式检查后端 multipart 限制；纯 Docker 方式还需检查 Nginx 的 `client_max_body_size`，并采用 3.4 的启动命令。

### 查看运行日志

```sh
# Compose：
docker compose logs --tail=100 backend tracker storage1

# 纯 Docker：
docker logs --tail=100 cendo-backend
docker logs --tail=100 cendo-tracker
docker logs --tail=100 cendo-storage1
```

上线前先做一次真实验收：注册并登录，上传一个 TXT 或 DOCX 文件，打开预览，再下载并核对内容。只检查 `Up` 或 `ACTIVE` 不等于整条链路已可用。

## 五、数据保留与新设备迁移

- 命名卷保存 MySQL、Redis、tracker、三个 storage 和后端本地存储的数据；普通停止容器不会删除它们。
- 后端的 `backend-storage`（纯 Docker 为 `cendo-backend-storage`）保存历史本地文件与未完成上传的分片；迁移时也应备份。它不代替 FastDFS 数据卷。
- 新设备首次启动会创建新数据，旧账号和文件不会随代码出现。
- 搬迁已有数据时，需要迁移数据库与对应的 FastDFS 数据、配置及节点信息，而不只是复制源码或一个文件目录。跨主机/地址变更应结合 [FastDFS 跨主机 Storage 部署设计](FastDFS跨主机Storage部署设计.md) 制定方案。
- Compose 和纯 Docker 示例使用不同的卷名，彼此不会自动复用数据。不要通过随意改卷名来尝试迁移。
- 不要执行 `docker compose down -v`、删除这些命名卷，或未经确认运行卷清理命令。
- 公网生产环境还需另行配置强密码、HTTPS、访问控制、版本固定、备份与容量监控；不要直接暴露本说明中的开发配置。
