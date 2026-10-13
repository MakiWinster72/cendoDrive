// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import UploadPanel from "./UploadPanel.vue";
import { uploadFileInChunks } from "../api/chunkedUpload";
import { useTransfers } from "../stores/transfers";

vi.mock("../api/chunkedUpload", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/chunkedUpload")>()),
  uploadFileInChunks: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  useTransfers().reset();
});

describe("upload button state", () => {
  it("显示同名冲突建议并同步到传输列表", async () => {
    vi.mocked(uploadFileInChunks).mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { code: "NAME_CONFLICT" } } });
    const wrapper = mount(UploadPanel, { props: { open: true } });
    const input = wrapper.get<HTMLInputElement>(".file-input");
    Object.defineProperty(input.element, "files", { value: [new File(["x"], "same.txt")] });
    await input.trigger("change");
    await wrapper.get(".start-upload-button").trigger("click");
    await flushPromises();
    expect(wrapper.get(".task-error").text()).toContain("重命名文件或选择其他文件夹");
    expect(useTransfers().tasks.find(t => t.name === "same.txt")?.error).toContain("同名项目");
    wrapper.unmount();
  });
  it("上传时显示采样速度，结束后清零", async () => {
    let time = 0;
    const clock = vi.spyOn(performance, "now").mockImplementation(() => time);
    let finish!: (value: Awaited<ReturnType<typeof uploadFileInChunks>>) => void;
    vi.mocked(uploadFileInChunks).mockImplementation(({ onProgress, onPhase }) => {
      onPhase("uploading");
      onProgress(0, 0);
      time = 1000;
      onProgress(50, 1024);
      return new Promise(resolve => { finish = resolve; });
    });
    const wrapper = mount(UploadPanel, { props: { open: true } });
    const input = wrapper.get<HTMLInputElement>(".file-input");
    Object.defineProperty(input.element, "files", { value: [new File([new Uint8Array(2048)], "speed.txt")] });
    await input.trigger("change");
    await wrapper.get(".start-upload-button").trigger("click");
    expect(wrapper.text()).toContain("1.0 KB/s");
    finish({ id: "speed", name: "speed.txt", kind: "file", size: 2048, parentId: null, updatedAt: "", deletedAt: null });
    await flushPromises();
    expect(wrapper.text()).not.toContain("KB/s");
    expect(useTransfers().tasks.find(t => t.name === "speed.txt")?.speed).toBe(0);
    wrapper.unmount();
    clock.mockRestore();
  });
  it("can dismiss the dialog with an explicit close button", async () => {
    const wrapper = mount(UploadPanel, { props: { open: true } });
    await wrapper.get('button[aria-label="关闭上传弹窗"]').trigger("click");
    expect(wrapper.emitted("close")).toHaveLength(1);
    await wrapper.setProps({ open: false });
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("supports Escape and backdrop dismissal without dismissing content clicks", async () => {
    const wrapper = mount(UploadPanel, { props: { open: true } });
    await wrapper.get('[role="dialog"]').trigger("click");
    expect(wrapper.emitted("close")).toBeUndefined();
    await wrapper.get('[role="dialog"]').trigger("keydown", { key: "Escape" });
    await wrapper.get(".upload-overlay").trigger("click");
    expect(wrapper.emitted("close")).toHaveLength(2);
    wrapper.unmount();
  });
  it("closing during upload does not abort the upload", async () => {
    vi.mocked(uploadFileInChunks).mockImplementation(() => new Promise(() => {}));
    const wrapper = mount(UploadPanel, { props: { open: true } });
    const input = wrapper.get<HTMLInputElement>(".file-input");
    Object.defineProperty(input.element, "files", {
      configurable: true,
      value: [new File(["test"], "笔记.md")],
    });
    await input.trigger("change");
    await wrapper.get(".start-upload-button").trigger("click");
    const signal = vi.mocked(uploadFileInChunks).mock.calls[0]![0].signal;
    await wrapper.get('button[aria-label="关闭上传弹窗"]').trigger("click");
    await wrapper.setProps({ open: false });
    expect(wrapper.emitted("close")).toHaveLength(1);
    expect(signal?.aborted).toBe(false);
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it("shows the ready button before upload and the completion button after success", async () => {
    vi.mocked(uploadFileInChunks).mockResolvedValue({
      id: "42",
      name: "笔记.md",
      kind: "file",
      size: 4,
      parentId: null,
      updatedAt: "",
      deletedAt: null,
    });
    const wrapper = mount(UploadPanel, { props: { open: true } });
    await flushPromises();
    const input = wrapper.get<HTMLInputElement>(".file-input");
    Object.defineProperty(input.element, "files", {
      configurable: true,
      value: [new File(["test"], "笔记.md", { type: "text/markdown" })],
    });
    await input.trigger("change");
    expect(wrapper.get(".start-upload-button").text()).toBe("上传 1 个");
    expect(wrapper.find(".upload-complete-button").exists()).toBe(false);
    await wrapper.get(".start-upload-button").trigger("click");
    await flushPromises();
    expect(uploadFileInChunks).toHaveBeenCalledOnce();
    expect(
      useTransfers().tasks.find((task) => task.name === "笔记.md")?.fileId,
    ).toBe("42");
    expect(wrapper.find(".start-upload-button").exists()).toBe(false);
    expect(wrapper.get(".upload-complete-button").text()).toBe("上传完成");
    wrapper.unmount();
  });
});
