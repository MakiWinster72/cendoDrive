// @vitest-environment jsdom
import { Blob as NodeBlob } from "node:buffer";
import { mount, flushPromises } from "@vue/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import FileThumbnail from "./FileThumbnail.vue";
import { fetchPreviewFile } from "../api/preview";
vi.mock("../api/preview", () => ({ fetchPreviewFile: vi.fn() }));
const file = {
  id: "1",
  name: "photo.png",
  kind: "file" as const,
  size: 10,
  parentId: null,
  updatedAt: "1",
  deletedAt: null,
};
let intersect: () => void;
const wrappers: ReturnType<typeof mount>[] = [];
const revoke = vi.fn();
beforeEach(() => {
  vi.resetAllMocks();
  vi.stubGlobal("URL", {
    createObjectURL: () => "blob:thumbnail",
    revokeObjectURL: revoke,
  });
  vi.stubGlobal(
    "IntersectionObserver",
    class {
      constructor(callback: (entries: { isIntersecting: boolean }[]) => void) {
        intersect = () => callback([{ isIntersecting: true }]);
      }
      observe() {}
      disconnect() {}
    },
  );
});
afterEach(() => {
  wrappers.forEach((w) => w.unmount());
  wrappers.length = 0;
  vi.unstubAllGlobals();
});
function open(value = file) {
  const wrapper = mount(FileThumbnail, { props: { file: value } });
  wrappers.push(wrapper);
  return wrapper;
}
it("loads only when visible and releases image URLs", async () => {
  vi.mocked(fetchPreviewFile).mockResolvedValue(
    new NodeBlob(["image"]) as unknown as Blob,
  );
  const wrapper = open();
  expect(fetchPreviewFile).not.toHaveBeenCalled();
  intersect();
  await flushPromises();
  expect(wrapper.get("img").attributes("src")).toBe("blob:thumbnail");
  await wrapper.get("img").trigger("load");
  expect(wrapper.find("svg").exists()).toBe(false);
  wrapper.unmount();
  expect(revoke).toHaveBeenCalledWith("blob:thumbnail");
});
it("keeps icons for deleted, oversized and unsupported files", async () => {
  for (const value of [
    { ...file, deletedAt: "today" },
    { ...file, size: 200 * 1024 * 1024 },
    { ...file, name: "video.mp4" },
  ]) {
    const wrapper = open(value as typeof file);
    intersect();
    await flushPromises();
    expect(wrapper.find("svg").exists()).toBe(true);
  }
  expect(fetchPreviewFile).not.toHaveBeenCalled();
});
it("aborts downloads when removed and ignores stale results", async () => {
  let resolve!: (blob: Blob) => void;
  vi.mocked(fetchPreviewFile).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const wrapper = open();
  intersect();
  await flushPromises();
  const signal = vi.mocked(fetchPreviewFile).mock.calls[0]![1];
  await wrapper.setProps({ file: { ...file, name: "other.txt" } });
  expect(signal.aborted).toBe(true);
  resolve(new NodeBlob(["old"]) as unknown as Blob);
  await flushPromises();
  expect(wrapper.find("img").exists()).toBe(false);
});
it("retains the file icon on request failure", async () => {
  vi.mocked(fetchPreviewFile).mockRejectedValue(new Error("forbidden"));
  const wrapper = open();
  intersect();
  await flushPromises();
  expect(wrapper.find("svg").exists()).toBe(true);
});
it("renders sanitized Markdown content as a document thumbnail", async () => {
  vi.mocked(fetchPreviewFile).mockResolvedValue(
    new NodeBlob([
      "# 文档标题\n\n正文<script>alert(1)</script>\n\n![remote](https://example.com/a.png)",
    ]) as unknown as Blob,
  );
  const wrapper = open({ ...file, name: "README.markdown" });
  intersect();
  await vi.waitFor(() =>
    expect(wrapper.find(".file-thumbnail-document h1").text()).toBe("文档标题"),
  );
  expect(wrapper.find("script").exists()).toBe(false);
  expect(wrapper.find("img").exists()).toBe(false);
});
