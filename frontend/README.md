# CendoDrive 前端

Vue 3 + Vue Router + Axios。账号注册、登录、会话验证和退出使用真实 Spring Boot 接口；首页文件列表目前仍是静态演示数据，上传、分享等功能尚未接入后端。

## 本地运行

先按 `backend/README.md` 启动 MySQL、Redis 和后端（默认 `http://localhost:8080`），然后在 `frontend/` 运行：

```bash
npm ci
npm run dev
```

打开 `http://localhost:5173`。Vite 开发服务器把 `/api` 请求原样代理到后端 8080，不必为本地开发开启宽泛 CORS。后端停止时登录会报网络错误，不存在演示 Token 回退。

如需连接其他部署环境，可复制 `.env.example` 为本机 `.env`，设置完整的 `VITE_API_BASE_URL`；生产环境应由部署方提供同源 `/api` 反向代理，或明确配置安全的 CORS 允许源。不要将任何凭据写入 Vite 环境变量，它们会暴露在浏览器中。

```bash
npm run build
```

## 样式与响应式约定

- `src/styles/main.css`：全局基础、桌面/移动布局切换、文件页基础和共用导航。
- `src/styles/home.css`：移动首页专属规则；个人页、分享页等继续使用各自的样式文件。
- 页面专属规则不要在 `main.css` 重复定义；将基础属性和页面调整合并到所属文件，避免靠导入顺序覆盖。

| 屏幕宽度 | 名称 |
| --- | --- |
| `< 640px` | Mobile |
| `640px ≤ width < 768px` | Small |
| `768px ≤ width < 1024px` | Tablet |
| `1024px ≤ width < 1280px` | Laptop |
| `1280px ≤ width < 1536px` | Desktop |
| `≥ 1536px` | Large Desktop |

首页在 `< 768px`（Mobile / Small）使用移动布局，`≥ 768px` 使用桌面布局。Tablet 使用紧凑侧栏和文件列；更宽屏幕沿用弹性桌面布局，不为每个档位添加空媒体查询。使用 CSS 范围语法（如 `@media (width < 768px)`），确保恰好 768px 时不会同时命中移动端规则。360px / 380px 等窄屏微调不属于布局切换断点。

## 认证行为

- 注册使用用户名（3–64 位字母、数字、下划线）、密码（8–128 位）和可选昵称；成功后跳转登录，不自动登录。
- 登录只接受用户名和密码。勾选“下次自动登录”将会话存于 localStorage；不勾选时存于 sessionStorage。两者都不会延长后端 Token 的 24 小时有效期。
- 刷新或打开私有页面时通过 `/api/user/me` 验证保存的 Token，验证前不会展示文件页面。退出调用 `/api/auth/logout` 撤销当前 Token，然后清理本地会话；若网络故障，会提示服务端撤销未确认。
- 浏览器存储中的 Bearer Token 可被同源恶意脚本读取；正式上线需评估 XSS/CSP、HTTPS、依赖安全和更适合生产的会话方案。
- 找回密码、扫码和第三方登录尚未实现；首页文件、容量等展示目前仍是静态数据。
