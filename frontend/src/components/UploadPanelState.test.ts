// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import UploadPanel from "./UploadPanel.vue";
import { uploadFile } from "../api/files";
import { useTransfers } from "../stores/transfers";

vi.mock("../api/files", async importOriginal => ({
  ...(await importOriginal<typeof import("../api/files")>()),
  uploadFile: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  useTransfers().reset();
});

describe("upload button state", () => {
  it("shows the ready button before upload and the completion button after success", async () => {
    vi.mocked(uploadFile).mockResolvedValue({ id: "42", name: "笔记.md", kind: "file", size: 4, parentId: null, updatedAt: "", deletedAt: null });
    const wrapper = mount(UploadPanel, { props: { open: true } });
    await flushPromises();
    const input = wrapper.get<HTMLInputElement>(".file-input");
    Object.defineProperty(input.element, "files", { configurable: true, value: [new File(["test"], "笔记.md", { type: "text/markdown" })] });
    await input.trigger("change");
    expect(wrapper.get(".start-upload-button").text()).toBe("上传 1 个");
    expect(wrapper.find(".upload-complete-button").exists()).toBe(false);
    await wrapper.get(".start-upload-button").trigger("click");
    await flushPromises();
    expect(uploadFile).toHaveBeenCalledOnce();
    expect(useTransfers().tasks.find(task => task.name === "笔记.md")?.fileId).toBe("42");
    expect(wrapper.find(".start-upload-button").exists()).toBe(false);
    expect(wrapper.get(".upload-complete-button").text()).toBe("上传完成");
    wrapper.unmount();
  });
});
