# CendoDrive

基于 Vue 3、Vue Router 与 Axios 的百度网盘风格页面复刻，包含登录页、文件首页和登录状态持久化。

```bash
npm install
npm run dev
```

未配置后端时，输入任意非空账号和密码即可进入演示首页。接入真实接口时复制 `.env.example` 并设置 `VITE_API_BASE_URL`，登录接口为 `POST /auth/login`。
