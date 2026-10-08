import { renderShareQr } from "./shareQr";
import { shareExpiryLabel, shareUrl, type ShareRecord } from "../api/shares";
import { formatBytes } from "../stores/drive";

async function svgImage(svg: string): Promise<HTMLImageElement> {
  const image = new Image();
  image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
  await image.decode();
  return image;
}

/** A local, high-resolution PNG: no file data or link is sent to third-party QR services. */
export async function buildSharePoster(share: ShareRecord, origin: string, autoFill: boolean, fileSvg: string): Promise<Blob> {
  const canvas = document.createElement("canvas");
  canvas.width = 660; canvas.height = share.extractionCode && !autoFill ? 750 : 708;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("当前浏览器无法生成二维码图片");
  ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = "#f5f6f8"; ctx.beginPath(); ctx.roundRect(0, 0, 660, 306, 9); ctx.fill();
  ctx.drawImage(await svgImage(fileSvg), 270, 62, 120, 120);
  ctx.fillStyle = "#939393"; ctx.textAlign = "center";
  ctx.font = '24px system-ui, sans-serif'; ctx.fillText(formatBytes(share.size), 330, 226);
  ctx.font = '22px system-ui, sans-serif';
  let name = share.fileName;
  while (ctx.measureText(name).width > 560 && name.length > 1) name = name.slice(0, -1);
  ctx.fillText(name === share.fileName ? name : `${name}…`, 330, 271);
  // Branding stays outside the QR quiet zone.
  ctx.fillStyle = "#dce1e9"; ctx.font = 'bold 24px system-ui, sans-serif'; ctx.textAlign = "right"; ctx.fillText("CENDO", 638, 292);
  const qr = document.createElement("canvas");
  renderShareQr(qr, shareUrl(share, origin, autoFill));
  ctx.imageSmoothingEnabled = false; ctx.drawImage(qr, 218, 348, 224, 224);
  ctx.textAlign = "center"; ctx.fillStyle = "#8b91a0"; ctx.font = '26px system-ui, sans-serif';
  ctx.fillText("微信长按识别二维码获取文件", 330, 630);
  ctx.fillStyle = "#111827"; ctx.font = 'bold 26px system-ui, sans-serif';
  const expiry = shareExpiryLabel(share.expiresAt);
  ctx.fillText(expiry === "永久有效" ? "该分享永久有效" : expiry, 330, 678);
  if (share.extractionCode && !autoFill) { ctx.font = '24px system-ui, sans-serif'; ctx.fillText(`提取码：${share.extractionCode}`, 330, 723); }
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error("二维码图片生成失败")), "image/png"));
}

export function launchShareApp(app: "wechat" | "qq"): void {
  // These schemes launch the app; arbitrary web pages cannot directly send WeChat/QQ messages.
  const link = document.createElement("a");
  link.href = app === "wechat" ? "weixin://" : "mqq://";
  document.body.appendChild(link); link.click(); link.remove();
}

export async function copyShareText(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) { await navigator.clipboard.writeText(text); return; }
  const input = document.createElement("textarea");
  input.value = text; input.style.cssText = "position:fixed;opacity:0;pointer-events:none";
  (document.querySelector("dialog[open]") ?? document.body).appendChild(input);
  const focused = document.activeElement as HTMLElement | null;
  try { input.select(); if (!document.execCommand("copy")) throw new Error("无法复制"); }
  finally { input.remove(); focused?.focus(); }
}

export function downloadSharePoster(blob: Blob): void {
  const url = URL.createObjectURL(blob), link = document.createElement("a");
  link.href = url; link.download = "CendoDrive-分享二维码.png";
  document.body.appendChild(link); link.click(); link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
