import { describe, expect, it } from "vitest";
import { decodeText, previewFormat, previewLimit } from "./formats";
describe("preview formats", () => {
  it.each([
    ["说明.DOCX", "docx"],
    ["设计.PDF", "pdf"],
    ["相片.JPEG", "image"],
    ["照片.webp", "image"],
    ["动画.svg", "image"],
    ["录像.MP4", "video"],
    ["电影.webm", "video"],
    ["readme.MD", "markdown"],
    ["说明.txt", "text"],
  ])("recognizes %s", (name, kind) =>
    expect(previewFormat(name)?.kind).toBe(kind),
  );
  it.each([
    "archive.zip",
    "word.doc",
    "no-extension",
    "report.pdf.exe",
    "index.html",
  ])("rejects %s", (name) => expect(previewFormat(name)).toBeNull());
  it("sets bounded document and media sizes", () => {
    expect(previewLimit("text")).toBe(5 * 1024 ** 2);
    expect(previewLimit("docx")).toBe(20 * 1024 ** 2);
    expect(previewLimit("pdf")).toBe(100 * 1024 ** 2);
  });
  it("decodes UTF-8, BOM, UTF-16 and Chinese GB18030", () => {
    expect(decodeText(new TextEncoder().encode("中文\nhello").buffer)).toBe(
      "中文\nhello",
    );
    expect(decodeText(new Uint8Array([0xef, 0xbb, 0xbf, 65]).buffer)).toBe("A");
    expect(decodeText(new Uint8Array([0xff, 0xfe, 0x2d, 0x4e]).buffer)).toBe(
      "中",
    );
    expect(decodeText(new Uint8Array([0xfe, 0xff, 0x4e, 0x2d]).buffer)).toBe(
      "中",
    );
    expect(decodeText(new Uint8Array([0xd6, 0xd0, 0xce, 0xc4]).buffer)).toBe(
      "中文",
    );
  });
});
