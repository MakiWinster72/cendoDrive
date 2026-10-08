// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { renderDocx, renderMarkdown, sanitizeDocument } from "./render";
// Vitest executes dependencies in Node (whose Mammoth entry accepts Buffer, not ArrayBuffer).
// Use the official browser bundle here to exercise the same input contract as the Vite app.
vi.mock("mammoth", async () => {
  const { createRequire } = await import("node:module");
  const require = createRequire(resolve("package.json"));
  return require("mammoth/mammoth.browser.js");
});
describe("safe local document rendering", () => {
  it("keeps paragraphs, tables, headings and embedded raster images", () => {
    const html = sanitizeDocument('<h1>标题</h1><table><tr><td colspan="2">单元格</td></tr></table><img alt="内嵌" src="data:image/png;base64,aGVsbG8=">');
    expect(html).toContain("<h1>标题</h1>");
    expect(html).toContain('colspan="2"');
    expect(html).toContain("data:image/png;base64,");
  });
  it("removes XSS, links, styles, forms and all remote images", () => {
    const html = sanitizeDocument('<script>alert(1)</script><img src="https://evil.test/tracker" onerror="alert(1)"><img src="//evil.test/a"><img src="data:image/svg+xml;base64,abc"><a href="javascript:alert(1)">阅读</a><iframe src="https://evil.test"></iframe><form><input></form><p style="color:red" onclick="alert(1)">正文</p>');
    expect(html).not.toMatch(/script|onerror|onclick|href|iframe|form|input|style|evil|svg/i);
    expect(html).toContain("正文");
    expect(html).toContain("阅读");
  });
  it("renders markdown as inert local-only HTML", async () => {
    const html = await renderMarkdown('# Hello\n\n**world**\n\n- one\n- two\n\n![tracking](https://evil.test/a)\n\n<script>alert(1)</script>');
    expect(html).toContain("<h1>Hello</h1>");
    expect(html).toContain("<strong>world</strong>");
    expect(html).toContain("<li>one</li>");
    expect(html).not.toContain("evil.test");
    expect(html).not.toContain("<script>");
  });
  it("renders a real DOCX including Chinese, a table and an embedded image", async () => {
    const path = resolve("src/preview/__fixtures__/sample.docx");
    const data = new Uint8Array(readFileSync(path));
    const html = await renderDocx(data.buffer);
    expect(html).toContain("DOCX 中文预览");
    expect(html).toContain("<table>");
    expect(html).toContain("表格 A");
    expect(html).toContain("data:image/png;base64,");
  });
  it("rejects invalid DOCX", async () => {
    await expect(renderDocx(new Uint8Array([0, 1, 2]).buffer)).rejects.toThrow();
  });
});
