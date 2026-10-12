# 推荐广告素材来源

首页推荐使用 30 项本地展示素材：保留已有 `3.jpg` 至 `9.jpg` 的 7 项，新增 23 项 `recommendation-*.jpg`。`popAD.JPG` 和 `popAD1.jpg` 仅供开屏使用，不计入推荐总数。

新增图片通过 Lorem Picsum 公开列表采集，原始作品来自 Unsplash。作者、原始作品页、实际下载 URL 和 SHA-256 记录在 `../../src/api/recommendationAdResources.json`。统一下载为 540×720 JPEG，运行时不请求第三方图片服务。

- 列表：https://picsum.photos/v2/list?page=2&limit=30
- 图片服务：https://picsum.photos/
- 使用条款参考：https://unsplash.com/license
- 重新采集：在 `frontend/` 执行 `node scripts/fetch-recommendation-ads.mjs`（需要网络和 curl），审查清单和图片变化后再提交。

这些图片仅作为推荐广告位的演示素材，不代表真实品牌投放、人物代言或已经取得广告投放授权。上线前需要复核原图权利、商标及肖像等使用条件；已有 7 张素材的来源未在仓库记录，需由素材提供者确认。
