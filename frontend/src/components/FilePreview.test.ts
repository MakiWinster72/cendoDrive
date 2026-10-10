// @vitest-environment jsdom
import { Blob as NodeBlob } from "node:buffer";
import { mount, flushPromises } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import FilePreview from "./FilePreview.vue";
import { fetchPreviewFile } from "../api/preview";
import { downloadFile } from "../api/drive";
vi.mock("../api/preview", () => ({ fetchPreviewFile: vi.fn() }));
vi.mock("../api/drive", () => ({
  downloadFile: vi.fn(),
  driveErrorMessage: (error: Error) => error.message,
}));
const file = {
  id: "42",
  name: "说明.txt",
  size: 10,
  kind: "file" as const,
  parentId: null,
  updatedAt: "",
  deletedAt: null,
};
const createUrl = vi.fn(() => "blob:preview");
const revokeUrl = vi.fn();
const wrappers: ReturnType<typeof mount>[] = [];
function open(name = file.name) {
  const wrapper = mount(FilePreview, {
    props: { file: { ...file, name } },
    attachTo: document.body,
  });
  wrappers.push(wrapper);
  return wrapper;
}
function bytes(value: string) {
  return new NodeBlob([value]) as unknown as Blob;
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.stubGlobal("Blob", NodeBlob);
  vi.stubGlobal("URL", {
    createObjectURL: createUrl,
    revokeObjectURL: revokeUrl,
  });
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
  createUrl.mockReturnValue("blob:preview");
  document.body.style.overflow = "auto";
});
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  document.body.innerHTML = "";
  vi.unstubAllGlobals();
});
describe("file preview lifecycle", () => {
  it("shows modal, plaintext inertly and restores scrolling on close", async () => {
    vi.mocked(fetchPreviewFile).mockResolvedValue(
      bytes("<script>alert(1)</script>\n中文"),
    );
    const wrapper = open();
    await flushPromises();
    expect(document.querySelector("dialog")?.hasAttribute("open")).toBe(true);
    expect(document.body.style.overflow).toBe("hidden");
    expect(document.querySelector("pre")?.textContent).toContain("<script>");
    expect(document.querySelector("script")).toBeNull();
    (
      document.querySelector('[aria-label="关闭文件预览"]') as HTMLButtonElement
    ).click();
    expect(wrapper.emitted("close")).toHaveLength(1);
    wrapper.unmount();
    wrappers.splice(wrappers.indexOf(wrapper), 1);
    expect(document.body.style.overflow).toBe("auto");
  });
  it("renders markdown and toggles source", async () => {
    vi.mocked(fetchPreviewFile).mockResolvedValue(bytes("# 标题\n\n**正文**"));
    open("README.md");
    await flushPromises();
    // Dynamic imports can finish after flushPromises; poll with a bounded assertion.
    await vi.waitFor(() =>
      expect(document.querySelector("article h1")?.textContent).toBe("标题"),
    );
    const source = [...document.querySelectorAll("button")].find(
      (button) => button.textContent === "源码",
    )!;
    source.click();
    await flushPromises();
    expect(document.querySelector("pre")?.textContent).toContain("# 标题");
    expect(source.getAttribute("aria-pressed")).toBe("true");
  });
  it("displays failures and retries", async () => {
    vi.mocked(fetchPreviewFile)
      .mockRejectedValueOnce(new Error("文件不存在或无权访问"))
      .mockResolvedValue(bytes("retry success"));
    open();
    await flushPromises();
    expect(document.querySelector('[role="alert"]')?.textContent).toContain(
      "无权访问",
    );
    [...document.querySelectorAll("button")]
      .find((button) => button.textContent?.includes("重新加载"))!
      .click();
    await flushPromises();
    expect(document.querySelector("pre")?.textContent).toBe("retry success");
  });
  it("cancels loading on unmount and never publishes a late result", async () => {
    let resolve!: (blob: Blob) => void;
    vi.mocked(fetchPreviewFile).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const wrapper = open();
    await flushPromises();
    const signal = vi.mocked(fetchPreviewFile).mock.calls[0]![1];
    wrapper.unmount();
    wrappers.splice(wrappers.indexOf(wrapper), 1);
    expect(signal.aborted).toBe(true);
    resolve(bytes("late"));
    await flushPromises();
    expect(document.querySelector("dialog")).toBeNull();
    expect(createUrl).not.toHaveBeenCalled();
  });
  it("switches files without stale content", async () => {
    let resolve!: (blob: Blob) => void;
    vi.mocked(fetchPreviewFile)
      .mockReturnValueOnce(
        new Promise((done) => {
          resolve = done;
        }),
      )
      .mockResolvedValue(bytes("new file"));
    const wrapper = open();
    const oldSignal = vi.mocked(fetchPreviewFile).mock.calls[0]![1];
    await wrapper.setProps({ file: { ...file, id: "43" } });
    await flushPromises();
    resolve(bytes("old file"));
    await flushPromises();
    expect(oldSignal.aborted).toBe(true);
    expect(document.querySelector("pre")?.textContent).toBe("new file");
  });
  it.each([
    ["photo.png", "img"],
    ["movie.mp4", "video"],
  ])("releases blob URLs for %s", async (name, tag) => {
    vi.mocked(fetchPreviewFile).mockResolvedValue(bytes("media"));
    const wrapper = open(name);
    await flushPromises();
    expect(document.querySelector(tag)?.getAttribute("src")).toBe(
      "blob:preview",
    );
    expect(document.querySelector("video")?.hasAttribute("autoplay")).not.toBe(
      true,
    );
    wrapper.unmount();
    wrappers.splice(wrappers.indexOf(wrapper), 1);
    expect(revokeUrl).toHaveBeenCalledWith("blob:preview");
  });
  it("offers authenticated download and Escape close", async () => {
    vi.mocked(fetchPreviewFile).mockResolvedValue(bytes("content"));
    vi.mocked(downloadFile).mockResolvedValue();
    const wrapper = open();
    await flushPromises();
    expect(
      document.querySelector(".preview-download")?.getAttribute("aria-label"),
    ).toBe("下载原文件");
    (document.querySelector(".preview-download") as HTMLButtonElement).click();
    await flushPromises();
    expect(downloadFile).toHaveBeenCalledWith("42", "说明.txt");
    document
      .querySelector("dialog")!
      .dispatchEvent(new Event("cancel", { cancelable: true }));
    expect(wrapper.emitted("close")).toHaveLength(1);
  });
});
