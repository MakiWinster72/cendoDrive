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

  it.each(["扫一扫", "链接任务", "BT任务", "微信文件", "新建文档"])("shows feedback when %s is unavailable", (label) => {
    expect(template).toContain(`showUnavailable('${label}')`);
    expect(template).toContain('role="status"');
  });

  it("keeps all four AI actions as no-op buttons", () => {
    for (const label of ["AI相机", "AI录音速记", "AI视频笔记", "AI照片故事"])
      expect(template).toContain(label);
    const aiButton = template.slice(template.indexOf('<div class="ai-options">'), template.indexOf('<div class="security-caption">'));
    expect(aiButton).toContain("@click=\"showUnavailable(action.label)\"");
  });

  it("retains native file selection, folder creation and task actions", () => {
    for (const binding of ["chooseType('image', 'image/*')", "chooseType('video', 'video/*')", "chooseType('document',", "chooseType('audio', 'audio/*')", "chooseType('other', '*/*')", "emit('createFolder')", 'ref="fileInput"', '@change="handleFileSelection"', '@click="startUpload"', '@click="retryUpload(task)"', '@click="cancelUpload(task)"'])
      expect(template).toContain(binding);
    const home = readFileSync(new URL("../views/HomeView.vue", import.meta.url), "utf8");
    const upload = parse(home).descriptor.template!.ast!.children.find(node => node.type === 1 && node.tag === "UploadPanel");
    if (upload?.type !== 1) throw new Error("HomeView must contain UploadPanel");
    const handler = upload.props.find(prop => prop.type === 7 && prop.name === "on" && prop.arg?.type === 4 && prop.arg.content === "create-folder");
    if (handler?.type !== 7 || handler.exp?.type !== 4) throw new Error("UploadPanel must handle create-folder");
    expect(handler.exp.content).toContain("uploadPanelOpen = false");
    expect(handler.exp.content).toContain("createFolder()");
  });
});
