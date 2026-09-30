# FastDFS storage 分布在不同电脑的部署设计

> 设计文档，不会自动搬迁现有文件或修改当前 Docker 容器。当前项目分支 `feat/fastDFS` 的后端默认监听 `8080`，tracker 当前通过 `127.0.0.1:22122` 暴露，三个 storage 的 **HTTP** 端口已改为 `127.0.0.1:10001–10003`，FastDFS 协议端口仍为容器内 `23000`（宿主机分别映射 23000/23001/23002）。

## 结论

可以让 tracker、业务后端和三个 storage 分别运行在不同电脑上。**最推荐每台 storage 独占一台主机，在专用内网或 VPN 中均监听 `23000`，而非在同一主机上通过 `23000/23001/23002` 做端口转换。** FastDFS 客户端向 tracker 请求后，会直连 tracker 报告的 storage 地址与端口；发布 Nginx HTTP `10001–10003` 并不能替代这个连接。后端仅配置 tracker 地址（可多个），通常不在业务请求中自行指定单台 storage 的 IP。

## 拓扑与端口示例（地址仅示意）

| 节点 | 示例内网 IP | 服务 | 需要连通的端口 |
| --- | --- | --- | --- |
| tracker-1 | `10.20.0.10` | FastDFS tracker | 客户端和 storage 访问 TCP `22122` |
| storage-1 | `10.20.0.11` | FastDFS storage | 后端、tracker、同组 storage 访问 TCP `23000`；如启用 nginx，HTTP `8080` 仅对受控代理开放 |
| storage-2 | `10.20.0.12` | FastDFS storage | 同上 |
| storage-3 | `10.20.0.13` | FastDFS storage | 同上 |
| CendoDrive | `10.20.0.20` | Spring Boot | 对 tracker `22122` 和所有 storage `23000` 可达；向浏览器提供 HTTP `8080` |

实际通信方向请以所用镜像的 FastDFS 配置及网络抓包/防火墙日志核对；不要把这些私有端口直接暴露公网。Nginx HTTP 端口不是业务后端上传所必需的，本项目下载走鉴权后端，不要把原始存储 URL 公开给浏览器，否则绕过所属用户校验。

## 部署步骤

1. **固定地址与 DNS**：每台主机指定稳定的内网 IP（或可信 DNS），避免把 Docker bridge 的 `172.x.x.x` 地址发布给其他电脑。后端与所有 storage 都应能路由到 tracker 报告的地址。容器在独立机器上可使用 host 网络（评估安全隔离与端口冲突），或使容器有可路由地址；普通 Docker `-p` 做 NAT 不能保证 tracker 报告的地址/端口等于宿主机映射。
2. **统一 tracker**：每台 storage 的 `TRACKER_SERVER` 指向 `10.20.0.10:22122`；后端设置 `FASTDFS_TRACKER=10.20.0.10:22122`。如果需要多个 tracker，先扩展/核实当前 Java 客户端的 `fdfs.tracker-list` 多地址配置，并确保所有 tracker 都属于同一个集群。切换 tracker 的前提是它能索引原有 storage；切到全新集群**不会**自动迁移旧文件。
3. **配置 storage**：每个实例持久化独立的 `/var/fdfs`，设置一致的 tracker 列表、不同实例的唯一标识；检查 `storage.conf` 的 `group_name`、`port`、`http.server_port` 与向 tracker 注册的 IP 是否符合网络设计。镜像的环境变量/配置项以实际镜像版本和启动脚本为准，不能假设仅配置 Docker 端口映射就可覆盖注册地址。
4. **选择 group 策略**：三个节点同属一个 group 时，FastDFS 按组内副本同步；要求核实副本同步状态与故障容量。不同 group 可用于扩容/隔离，但**不同 group 不会自动互为副本**。不要把“3 台机器”直接等同于“3 份可靠副本”，必须按实际组与同步状态验收。
5. **网络验证**：从后端机器连接 tracker `22122`，并对 `fdfs_monitor` 显示的**每一个** storage IP:`23000` 做连通测试；从 storage 之间核实所需端口可达。上传小文件，记录 FastDFS 完整 file ID（如 `group1/M00/...`），下载核对 SHA-256；再停掉任意一台 storage，验证业务行为并观察恢复同步。不要在已有生产数据上用不安全的停止/清卷方式测试。
6. **安全和运维**：通过防火墙仅允许受信任主机访问私网端口，跨不可信网络使用 WireGuard 等 VPN/专线；定期备份 MySQL、tracker 元数据和每台 storage 的持久卷，监控容量、同步延迟、tracker 可用性和上传/下载失败。新增节点后等数据同步稳定再接受流量。

## 与本项目代码的边界

- `backend/src/main/resources/application.yml` 的 `fdfs.tracker-list` 由 `FASTDFS_TRACKER` 填写一个 tracker 地址；当前 `backend/src/main/java/com/cendodrive/storage/FastDfsFileStorage.java` 上传交由 SDK + tracker 选择 storage，不实现每个文件手工指定 storage IP。这是正常的集群模式。
- `backend/src/main/resources/db/migration/V3__add_storage_backend.sql` 只区分 `local` 与 `fastdfs`，**不区分不同 FastDFS 集群**。若将来支持多个彼此独立的集群，需再设计 `cluster_id`（不可只存 IP）；下载/删除根据记录上的 `cluster_id` 路由到原集群，新上传使用当前默认集群。迁移流程：读取旧集群文件 → 上传新集群 → 校验哈希与大小 → 原子更新记录及审计 → 旧集群文件延迟清理。切换默认 tracker 并不会自动迁移旧 file ID。
- 若要求用户在页面上选择“存储到哪台电脑”，这与 FastDFS 的组/副本模型不同；应先明确是按 group 进行隔离、按机房/数据归属路由，还是使用另一种后端。不要在现有代码上通过硬编码 storage IP 绕过 tracker。

## 当前本机切回 8080

`backend/src/main/resources/application.yml` 的默认 `PORT` 已为 `8080`；前端开发代理默认也应指向 `http://localhost:8080`。`10001–10003` 是 storage 的宿主机 HTTP 映射，与后端 8080 不冲突。启动前检查 8080 未被占用，并确保 `VITE_API_PROXY_TARGET`、`VITE_API_BASE_URL` 或 `.env` 中没有残留的 `8083/8088` 覆盖值。需要更新 `myDocs/FastDFS文件存储接入方案.md` 中的旧端口说明时，以本文当前拓扑为准。
