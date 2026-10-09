import { describe, expect, it } from "vitest";
import { shareUrl, shareClipboardText, shareExpiryLabel, PERMANENT_SHARE_EXPIRY, type ShareRecord } from "./shares";
const share: ShareRecord = { id: "1", token: "token/a", fileId: "2", fileName: "文件.zip", kind: "file", size: 12, createdAt: "2026-01-01", expiresAt: PERMANENT_SHARE_EXPIRY, status: "ACTIVE", extractionCode: "Ab12" };
describe("share links", () => {
  it("keeps codes out of links unless explicitly opting in", () => {
    expect(shareUrl(share, "https://drive.example")).toBe("https://drive.example/share/token%2Fa");
    expect(shareUrl(share, "https://drive.example", true)).toBe("https://drive.example/share/token%2Fa#code=Ab12");
    expect(shareUrl({ ...share, extractionCode: undefined }, "https://drive.example", true)).not.toContain("#");
    expect(shareClipboardText(share, "https://drive.example")).toBe("https://drive.example/share/token%2Fa\n提取码：Ab12");
  });
  it("displays permanent and timed expiry consistently", () => {
    expect(shareExpiryLabel(PERMANENT_SHARE_EXPIRY)).toBe("永久有效");
    expect(shareExpiryLabel("9999-12-31T23:59:59.000Z")).toBe("永久有效");
    expect(shareExpiryLabel("2030-01-01T00:00:00Z")).toMatch(/^有效至 /);
  });
});
