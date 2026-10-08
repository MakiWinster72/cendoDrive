import { describe, expect, it } from 'vitest';
import { shareQrMatrix } from './shareQr';
import jsQR from 'jsqr';
import { shareUrl, type ShareRecord } from '../api/shares';
const share: ShareRecord = { id: '1', token: 'a'.repeat(32), fileId: '42', fileName: '文件.zip', kind: 'file', size: 123, status: 'ACTIVE', createdAt: '2026-01-01', expiresAt: '9999-12-31T23:59:59Z', extractionCode: 'Ab12' };
describe('real QR encoding', () => {
  it.each([false,true])('decodes the exact public URL (autofill=%s)', autoFill => {
    const url = shareUrl(share, 'https://drive.example', autoFill);
    const matrix = shareQrMatrix(url);
    const scale = 4, width = (matrix.getModuleCount() + 8) * scale;
    const rgba = new Uint8ClampedArray(width * width * 4);
    for (let y = 0; y < width; y++) for (let x = 0; x < width; x++) {
      const mx = Math.floor(x / scale) - 4, my = Math.floor(y / scale) - 4;
      const black = mx >= 0 && my >= 0 && mx < matrix.getModuleCount() && my < matrix.getModuleCount() && matrix.isDark(my,mx);
      const i = (y * width + x) * 4;
      rgba[i] = rgba[i+1] = rgba[i+2] = black ? 0 : 255; rgba[i+3] = 255;
    }
    expect(jsQR(rgba, width, width)?.data).toBe(url);
  });
});
