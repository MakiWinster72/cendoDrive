import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { parse, compileTemplate } from "vue/compiler-sfc";

const source = readFileSync(new URL("./UploadPanel.vue", import.meta.url), "utf8");
const { descriptor } = parse(source);
const template = descriptor.template!.content;

describe("reference upload panel", () => {
  it("uses the 千度网盘 brand", () => {
    expect(template).toContain("千度网盘保障你的数据安全");
    expect(template).not.toContain("百度网盘");
  });

  it("keeps the sheet mounted for enter and leave transitions", () => {
    expect(template).toContain('<Transition name="upload-sheet" appear>');
    expect(template).toContain('v-if="open"');
    expect(source).toContain(".upload-sheet-enter-from .upload-panel");
    expect(source).toContain(".upload-sheet-leave-to .upload-panel");
    expect(source).toContain("transform: translateY(100%)");
    expect(source).toContain("@media (prefers-reduced-motion: reduce)");
  });
  it("compiles the reference layout without template errors", () => {
    expect(compileTemplate({ source: template, filename: "UploadPanel.vue", id: "upload" }).errors).toEqual([]);
    expect(source).toContain("grid-template-columns: repeat(4, minmax(0, 1fr))");
  });

  it.each(["微信文件", "新建笔记"])("keeps %s as a no-op button", (label) => {
    const button = template.match(new RegExp(`<button[^>]*aria-label="${label}（暂未开放）"[^>]*>`))?.[0];
    expect(button).toBeDefined();
    expect(button).not.toContain("@click");
  });

  it("keeps all four AI actions as no-op buttons", () => {
    for (const label of ["AI相机", "AI录音速记", "AI视频笔记", "AI照片故事"])
      expect(template).toContain(label);
    const aiButton = template.slice(template.indexOf('<div class="ai-options">'), template.indexOf('<div class="security-caption">'));
    expect(aiButton).not.toContain("@click");
  });

  it("retains native file selection, folder creation and task actions", () => {
    for (const binding of ["chooseType('image', 'image/*')", "chooseType('video', 'video/*')", "chooseType('other', '*/*')", "emit('createFolder')", 'ref="fileInput"', '@change="handleFileSelection"', '@click="startUpload"', '@click="retryUpload(task)"', '@click="cancelUpload(task)"'])
      expect(template).toContain(binding);
    const home = readFileSync(new URL("../views/HomeView.vue", import.meta.url), "utf8");
    expect(home).toContain('@create-folder="uploadPanelOpen = false; createFolder()"');
  });
});
