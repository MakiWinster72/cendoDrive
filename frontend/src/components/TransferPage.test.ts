import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { parse, compileTemplate } from "vue/compiler-sfc";
const source = readFileSync(
  new URL("./TransferPage.vue", import.meta.url),
  "utf8",
);
const template = parse(source).descriptor.template!.content;
describe("reference transfer page", () => {
  it("compiles the template and uses 千度网盘 branding", () => {
    expect(
      compileTemplate({
        source: template,
        filename: "TransferPage.vue",
        id: "transfer",
      }).errors,
    ).toEqual([]);
    expect(template).toContain("千度网盘保障你的传输安全");
    expect(template).not.toContain("百度网盘");
  });
  it.each(["云添加"])("keeps %s a no-op", (label) => {
    const button = template.match(
      new RegExp(`<button[^>]*aria-label="${label}（暂未开放）"[^>]*>`),
    )?.[0];
    expect(button).toBeDefined();
    expect(button).not.toContain("@click");
  });
  it("uses shared tasks and protects running transfers when clearing", () => {
    expect(source).toContain("useTransfers");
    expect(template).toContain("transfers.clearFinished(tab)");
    expect(template).toContain('role="progressbar"');
    expect(source).toContain("transfers.setDownloadLimit(value)");
    expect(template).toContain("tab = 'transfer'");
  });
  it("shows a fixed storage summary and emits the manage action", () => {
    expect(template).toContain('aria-label="网盘剩余空间"');
    expect(template).toContain("formatBytes(storageUsage.availableBytes)");
    expect(template).toContain("formatBytes(storageUsage.limitBytes)");
    expect(template).toContain("@click=\"emit('manageStorage')\"");
    expect(source).toContain("position: fixed;");
    expect(source).toContain("env(safe-area-inset-bottom");
  });
});
