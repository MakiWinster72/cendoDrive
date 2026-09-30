# 千度网盘（CendoDrive / CD）

CendoDrive 谐音千度网盘，
一个正在开发的网盘项目：使用 Vue 3 构建前端、Spring Boot 提供认证和文件管理 API。

## 快速开始

准备 Java 21、Maven、Node.js/npm、MySQL 和 Redis。需要使用上传/下载功能时，还须部署 FastDFS tracker 与 storage，并保证后端能访问两者（不只是 tracker）。

1. 启动后端：

   ```sh
   cd backend
   # 修改application.yaml
   mvn spring-boot:run
   ```

2. **解决上述合并冲突后**，另开终端启动前端：

   ```sh
   cd frontend
   npm ci
   npm run dev
   ```

前端默认地址为 `http://localhost:5173`，后端为 `http://localhost:8080`。
