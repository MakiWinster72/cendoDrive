# CendoDrive 前端

Vue 3 + Vue Router + Axios。账号认证、文件列表、文件夹管理、重命名、移动、回收站和下载使用真实 Spring Boot 接口；文件预览复用已认证的下载接口。分片上传和分享页面仍需要对应的后端接口支持，容量等统计仍为演示数据。

## 本地运行

建议使用 Node.js 24 LTS（PDF.js 要求 Node.js `>=22.13.0 || >=24`）。先按 `backend/README.md` 启动 MySQL、Redis、FastDFS 和后端（默认 `http://localhost:8080`），然后在 `frontend/` 运行：

```bash
npm ci
npm run dev
```

打开 `http://localhost:5173`。Vite 开发服务器把 `/api` 请求原样代理到后端 8080，不必为本地开发开启宽泛 CORS。后端停止时登录会报网络错误，不存在演示 Token 回退。

如需连接其他部署环境，可复制 `.env.example` 为本机 `.env`，设置完整的 `VITE_API_BASE_URL`；生产环境应由部署方提供同源 `/api` 反向代理，或明确配置安全的 CORS 允许源。不要将任何凭据写入 Vite 环境变量，它们会暴露在浏览器中。

```bash
npm run build
```

## 文件预览

在桌面文件列表点击文件名或双击文件行、点击网格文件，或在移动端点击文件行 / 最近文件，即可打开预览；文件夹仍进入下一级，回收站不打开预览。关闭按钮、Escape 和点击弹窗外部可关闭预览，移动端使用全屏布局。

| 类型 | 支持范围 | 预览大小上限 |
| --- | --- | --- |
| DOCX | 正文、表格、内嵌图片 | 20 MiB |
| PDF | 本地画布渲染、上一页 / 下一页 | 100 MiB |
| 图片 | JPG/JPEG、PNG、GIF、WebP、BMP、AVIF、SVG | 100 MiB |
| 视频 | MP4、M4V、MOV、WebM、OGV；带播放控件，不自动播放 | 100 MiB |
| 文本 | TXT；UTF-8、带 BOM 的 UTF-16、GB18030 回退 | 5 MiB |
| Markdown | MD / Markdown；阅读与源码切换 | 5 MiB |

- 通过 `GET /api/files/{id}/download` 携带现有 Bearer Token 获取文件，后端继续校验所有者和文件状态；不生成公开链接、不将 Token 放进 URL，不使用第三方在线预览服务。
- 当前先完整获取文件 Blob，再进行本地解析；视频不是流式播放，PDF 不是 Range 按需加载。超限或不支持的文件会显示下载入口。视频容器、编码和图片格式能否播放仍取决于浏览器支持。
- DOCX 使用 Mammoth，侧重内容而非 Word 原始分页和复杂排版；旧版 `.doc` 不支持。PDF 为画布预览，不支持文字选择、搜索和密码输入；加密、损坏文件提示下载后查看。
- Markdown / DOCX 输出经 DOMPurify 白名单清理，屏蔽脚本、交互表单、外链和远程图片，仅允许支持的内嵌栅格图片。TXT / Markdown 源码作为纯文本显示。
- 关闭或切换文件会取消请求并释放对象 URL、PDF 渲染任务，同时恢复页面滚动。错误可重试，也可下载原文件。
- `npm run dev` / `npm run build` 自动复制 PDF.js 字体、CMap 和 WASM 到 `public/pdfjs/`（生成目录不提交）；部署时需保留构建产物中的 `pdfjs/` 和 worker 文件。

```bash
npm test
npm run build
```

测试覆盖格式判定、大小限制、鉴权请求、文本解码、HTML 清理、真实 DOCX 内容，以及弹窗加载、重试、切换、关闭和资源释放。

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
- 找回密码、扫码和第三方登录尚未实现；容量等统计展示仍为演示数据。
