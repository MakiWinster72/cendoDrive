import qrcode from "qrcode-generator";

export function shareQrMatrix(url: string) {
  const qr = qrcode(0, "M");
  qr.addData(url, "Byte");
  qr.make();
  return qr;
}

/** Integer-sized modules and a four-module quiet zone keep the exported QR sharp. */
export function renderShareQr(canvas: HTMLCanvasElement, url: string, width = 224): void {
  const qr = shareQrMatrix(url), count = qr.getModuleCount();
  const scale = Math.max(1, Math.floor(width / (count + 8)));
  canvas.width = canvas.height = (count + 8) * scale;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("当前浏览器无法生成二维码");
  ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = "#000";
  for (let row = 0; row < count; row++) for (let column = 0; column < count; column++) {
    if (qr.isDark(row, column)) ctx.fillRect((column + 4) * scale, (row + 4) * scale, scale, scale);
  }
}
