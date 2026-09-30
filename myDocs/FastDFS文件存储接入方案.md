# CendoDrive 接入 FastDFS：文件存储与管理实施方案

> 实施状态（2026-09-30）：已在 `feat/fastDFS` 分支实现普通文件上传、鉴权下载与本地旧文件兼容；文件永久删除、回收站、集群迁移、断点续传尚未实现。本文后续章节是原始规划，不代表全部功能已完成。

## 1. 现状与目标

- 后端 Spring Boot 3.3.5 / Java 21；`drive_files` 新增 `storage_backend`，上传文件记录 `fastdfs`，旧记录默认 `local`。已实现普通文件上传、下载；**尚无永久删除 API**。
- 前端上传已调用 `POST /api/files/upload`，成功后更新列表；失败提示错误信息。
- 当前 Docker 有一个 tracker（`127.0.0.1:22122`），三个 storage 分别映射 `127.0.0.1:23000/23001/23002`，HTTP 映射 `127.0.0.1:8080/8081/8082`。tracker 仍报告 Docker 桥接地址 `172.24.0.3/.4/.5:23000`；宿主机后端经 Docker bridge 路由可达时已完成一次上传与下载 SHA-256 一致的实测，不能据此推断远程后端也可达。
- **目标**：FastDFS 存实际字节；MySQL 管所属用户、目录、文件名、大小和 FastDFS file ID；业务接口统一鉴权，浏览器不直接访问 storage。

## 本机运行（已实现的最小闭环）

```sh
# 后端使用 Java 21，避免本机默认 Java 25 下旧版 Mockito 测试运行失败
cd backend
JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH PORT=8083 FASTDFS_TRACKER=127.0.0.1:22122 mvn spring-boot:run
# 新终端
cd frontend
npm install
npm run dev  # 默认代理 http://localhost:8083；可用 VITE_API_PROXY_TARGET 覆盖
```

使用已有 DB/Redis 环境配置；确认后端能连接 `127.0.0.1:22122` 以及 tracker 返回的每个 storage 地址。登录后上传文件，下载核对字节；调试时可用 `docker exec fastdfs-tracker fdfs_monitor /etc/fdfs/client.conf` 查看 storage 状态。当前后端不支持直接指定单台 storage 的不同宿主机映射端口，tracker 返回的是容器内部地址和 `23000`。

## 2. 先解决网络和端口（必须）

FastDFS 客户端不是只访问 tracker：它向 tracker 查询 storage 后还要直连返回的 storage IP:`23000`。**宿主机只开放 tracker 的 22122 并不足以让宿主机上的 Java 后端上传/下载。**目前 storage 返回的容器 IP 在 Docker 网络内；不要假设它对其他机器稳定或可达。

推荐开发方案：**把后端也作为容器加入 `fastdfs_fastdfs`**，同时加入能访问 MySQL/Redis 的网络（或让其通过可达的宿主机地址连接）。后端 tracker 配 `tracker:22122`，核实 tracker 返回的 storage 地址在该网络内可达。若后端继续在宿主机运行，需要另行设计每个 storage 的可路由地址、端口映射与 FastDFS 向 tracker 注册/对客户端报告的 IP；不能仅改 Java tracker 地址解决。

本机 storage 已占宿主机 `8080`、`8081`、`8082`；后端 `application.yml` 默认也用 `8080`。本机运行后端可用 `PORT=8083`（前端 Vite 代理默认指向 `8080`，需改代理目标或设置 `VITE_API_BASE_URL=http://localhost:8083/api` 并允许该前端来源）；不要同时绑定同一个宿主机端口。

检查部署状态（只读）：

```sh
docker ps
docker network inspect fastdfs_fastdfs
docker exec fastdfs-tracker fdfs_monitor /etc/fdfs/client.conf
# 留意 active storage、group、IP、23000 端口及容量；如命令报错先核实镜像内配置路径
```

检查数据持久化：当前 tracker 与三个 storage 的 `/var/fdfs` 使用不同宿主机 bind mount；保留这些目录并备份。不要以 `docker compose down -v`、重建容器或清理数据目录作为调试手段。

## 3. 后端建议改动（先完成最小闭环）

### 3.1 引入客户端并封装存储层

选一个**明确支持当前 FastDFS 服务端和 Java 21 的客户端实现**（可以评估 Java SDK 或 FastDFS 官方客户端）；锁定并验证依赖版本，再加入 `backend/pom.xml`。不要把下面的抽象接口误认为某 SDK 的现成 API：

```java
public interface FileStorage {
    String upload(InputStream content, long size, String extension) throws IOException;
    InputStream open(String fileId) throws IOException;
    void delete(String fileId) throws IOException;
}
```

在 `backend/src/main/java/com/cendodrive/storage/` 实现 `FastDfsFileStorage`：读取 tracker 地址和连接/读超时；上传返回 FastDFS **完整 file ID**（如 `group1/M00/...`），下载按 file ID 找 storage 并打开流，删除仅由后端发起。实际 SDK 可能要求拆分 group 与 remote filename、返回字节数组或使用回调；实现时按所选 SDK 适配，不要把示意签名当成已验证的 SDK 调用。对连接池、资源关闭、超时、失败重试做明确处理；上传重试避免产生无法跟踪的重复文件。

建议配置示意（增加到 `backend/src/main/resources/application.yml`，环境变量由部署注入）：

```yaml
cendo:
  storage:
    type: ${CENDO_STORAGE_TYPE:local} # 过渡期兼容旧数据；切换前明确策略
    root: ${CENDO_STORAGE_ROOT:./storage}
    fastdfs:
      tracker: ${FASTDFS_TRACKER:tracker:22122}
      connect-timeout-ms: 3000
      read-timeout-ms: 30000
spring:
  servlet:
    multipart:
      max-file-size: 100MB
      max-request-size: 100MB
```

`type` 仅是提议，新代码必须实际解析并实现选择；本机非容器后端不能直接使用 `tracker` 这个 Docker DNS 名称。不要把 storage HTTP 8080 当作 FastDFS 客户端上传端口。

### 3.2 元数据与兼容性

- `drive_files.storage_key` 存完整 FastDFS file ID，`kind=file`、`size_bytes=实际字节数`；文件夹保持 `storage_key=NULL`、大小 0。现有 `VARCHAR(512)` 先检查最大 file ID 长度，超出才用新增 Flyway 迁移，不要改历史 `V2__create_drive_files.sql`。
- **旧记录注意**：当前下载把 `storage_key` 当本地相对路径；不能上线后直接把所有旧 key 当 FastDFS ID。可新增 `storage_backend` 字段（Flyway `V3__...sql`，现有记录填 `local`、新记录填 `fastdfs`），或迁移旧内容并逐条核对后再切换。不得通过字符串前缀猜测来源。
- `DriveFile` 添加文件工厂方法和必要访问器；沿用现有 `FileResponse` 返回格式。重命名、移动只改数据库，不重新上传到 FastDFS。
- 文件名冲突继续走 `DriveService` 所有权校验和同目录校验；数据库根目录的 `(owner_id,parent_id,name)` 唯一索引在 MySQL 中 **不能约束 `parent_id=NULL` 的重复项**，并发上传需加可靠唯一性方案（例如独立规范化父目录键 + 唯一索引）并处理唯一约束异常。当前业务层忽略大小写的检查也不能替代并发数据库约束。

### 3.3 上传、下载、删除接口

1. `POST /api/files/upload`：`multipart/form-data`，字段 `file`、可选 `parentId`（整数）。先校验 Bearer 用户、非空文件、文件名、大小上限、父目录属主和同级重名；取得安全扩展名（不信任 Content-Type 或原始路径）；上传 FastDFS，成功后保存 `DriveFile` 元数据并返回 `201 FileResponse`。数据库保存失败则尽力删除新 FastDFS 对象并记录补偿失败待处理。不要在长时间网络上传期间持有数据库事务锁；保存时再次处理并发冲突。
2. `GET /api/files/{id}/download`：先按 `id+ownerId` 查记录且必须是文件，再通过该记录的 backend/key 读取；保留现有 `Content-Disposition: attachment; filename*=UTF-8''...` 与 `application/octet-stream`。不要用 tracker/storage 原始 URL 直接让浏览器访问（会绕开项目 Bearer 权限）；流式传输并正确关闭 FastDFS 连接/流，避免整文件装入内存。后端若能可靠获得内容长度可设置响应长度；网络异常要监测下载中断。
3. `DELETE /api/files/{id}` 可作为下一阶段：先定义目录非空策略及回收站语义；物理删除应先标记待删除/用可靠异步任务清理存储，再确认状态，避免数据库已删但远端删除失败后无法重试。当前表没有删除状态，切勿把回收站写成已实现。

错误码可沿用 `INVALID_NAME`、`NOT_A_FOLDER`、`NAME_CONFLICT`、`FILE_NOT_FOUND`、`CONTENT_NOT_FOUND`；新增上传大小超限 `413`、存储不可用 `503` 等并纳入 `docs/文件管理接口文档.md`。不要把内部 FastDFS 地址或异常栈透给客户端。

### 3.4 前端联调

在 `frontend/src/api/drive.ts` 增加 `FormData` 上传请求；`frontend/src/stores/drive.ts` 用真实异步 `upload(files,parentId)` 替代抛错，并在成功后刷新当前目录。`frontend/src/views/HomeView.vue` 的 `uploadFiles()` 要 `await`、分别反馈成功/失败，不能在请求发出前提示“已上传”。核对 `frontend/src/api/http.ts` 的 `/api` 基础路径和前端代理；后端若改 `PORT=8081` 同步更改前端目标。不要手动给 FormData 写固定 multipart boundary。

## 4. 推荐实施顺序与验收

1. 在**后端实际运行的网络环境**测 tracker 与三个 storage 的 `23000` 连通；通过 `fdfs_monitor` 确认实际 group/active 状态，不以 `docker ps` 的 Up 代替可用性验证。
2. 选 SDK 并写最小连接测试：上传小文件 → 取得完整 file ID → 下载并校验 SHA-256 → 删除测试文件；再接业务代码。
3. 实现存储抽象、来源字段/迁移、上传与下载改造；保留既有本地文件下载测试。
4. 实现前端上传，更新 Swagger/文件接口文档和环境配置示例。
5. 集成测试：未认证 401、他人文件 404、目录/重名校验、空文件/超大文件、中文名下载、二进制 hash 一致、多文件上传、远端故障与 DB 保存失败补偿、三个 storage 的文件读取、旧本地文件兼容。
6. 上线前备份 MySQL 与全部 FastDFS 存储目录；确认容量监控、孤儿文件清理任务、失败重试与告警。大文件分片/断点续传、回收站、分享链接、直传和去重是后续独立阶段，不包含在最小闭环中。

## 5. 相关现有文件

- 后端：`backend/src/main/java/com/cendodrive/drive/{DriveController,DriveService,DriveFile,DriveFileRepository}.java`
- 配置与迁移：`backend/src/main/resources/application.yml`、`backend/src/main/resources/db/migration/V2__create_drive_files.sql`
- 前端：`frontend/src/api/drive.ts`、`frontend/src/stores/drive.ts`、`frontend/src/views/HomeView.vue`
- 现行 API 说明：`docs/文件管理接口文档.md`
