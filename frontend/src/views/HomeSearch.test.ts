// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import HomeView from "./HomeView.vue";
import * as api from "../api/drive";
import { useDrive, type DriveItem } from "../stores/drive";
vi.mock("vue-router", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("../stores/auth", () => ({
  useAuth: () => ({
    user: { value: { username: "搜索用户" } },
    logout: vi.fn(),
  }),
}));
vi.mock("../api/drive", async (original) => ({
  ...(await original<typeof api>()),
  searchFiles: vi.fn(),
  listFiles: vi.fn(),
  getUsage: vi.fn(),
}));
const root: DriveItem = {
  id: "1",
  name: "根目录说明.txt",
  kind: "file",
  size: 10,
  parentId: null,
  updatedAt: "2026-01-01",
  deletedAt: null,
};
const work: DriveItem = {
  ...root,
  id: "7",
  name: "工作",
  kind: "folder",
  size: 0,
};
const data: DriveItem = { ...work, id: "8", name: "资料", parentId: "7" };
const document: DriveItem = {
  ...root,
  id: "42",
  name: "扫描合同.pdf",
  parentId: "8",
};
const hit: api.FileSearchHit = {
  file: document,
  ancestors: [work, data],
  path: "/工作/资料",
};
const wrappers: ReturnType<typeof mount>[] = [];
async function open(width = 390) {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    value: width,
    writable: true,
  });
  const wrapper = mount(HomeView, {
    global: {
      stubs: {
        UploadPanel: true,
        FilePreview: true,
        FileTools: true,
        ShareLinkDialog: true,
        ShareList: true,
        MobileMyShares: true,
      },
    },
  });
  wrappers.push(wrapper);
  await flushPromises();
  if (width < 768) {
    await wrapper
      .findAll(".mobile-app nav button")
      .find((x) => x.text() === "文件")!
      .trigger("click");
    await flushPromises();
  }
  return wrapper;
}
async function search(wrapper: ReturnType<typeof mount>, width = 390) {
  await wrapper
    .find(width < 768 ? ".m-search input" : ".topbar .search input")
    .setValue("合同");
  await vi.advanceTimersByTimeAsync(250);
  await flushPromises();
}
beforeEach(() => {
  vi.useFakeTimers();
  vi.resetAllMocks();
  useDrive().reset();
  vi.stubGlobal("alert", vi.fn());
  vi.mocked(api.listFiles).mockImplementation(async (id) =>
    id === "8" ? [document] : id === "7" ? [data] : [root],
  );
  vi.mocked(api.getUsage).mockResolvedValue({
    usedBytes: 10,
    limitBytes: 100,
    availableBytes: 90,
    trashBytes: 0,
    reservedBytes: 0,
  });
  vi.mocked(api.searchFiles).mockResolvedValue({
    items: [hit],
    total: 1,
    page: 0,
    size: 20,
  });
});
afterEach(() => {
  wrappers.forEach((x) => x.unmount());
  wrappers.length = 0;
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("homepage server filename search", () => {
  it.each([390, 1280])(
    "uses one request at viewport %i, preserves directory cache and exits to normal listing",
    async (width) => {
      const wrapper = await open(width);
      await search(wrapper, width);
      expect(api.searchFiles).toHaveBeenCalledTimes(1);
      expect(wrapper.findAll(".file-search-panel")).toHaveLength(1);
      expect(wrapper.find(".file-search-open b").text()).toBe(document.name);
      expect(useDrive().state.files.map((x) => x.id)).toEqual(["1"]);
      await wrapper.find('[aria-label="退出搜索"]').trigger("click");
      await flushPromises();
      expect(wrapper.find(".file-search-panel").exists()).toBe(false);
      expect(
        wrapper.find(width < 768 ? ".m-file-row b" : ".file-row b").text(),
      ).toBe(root.name);
    },
  );
  it("previews a search hit without loading its parent or contaminating the cache", async () => {
    const wrapper = await open();
    await search(wrapper);
    await wrapper.find(".file-search-open").trigger("click");
    expect(
      wrapper.findComponent({ name: "FilePreview" }).props("file"),
    ).toEqual(document);
    expect(
      vi.mocked(api.listFiles).mock.calls.every((call) => call[0] === null),
    ).toBe(true);
    expect(useDrive().get("42")).toBeUndefined();
  });
  it("locates an unloaded nested parent, hydrates breadcrumb context and returns level by level", async () => {
    const wrapper = await open();
    await search(wrapper);
    await wrapper.find(".file-search-locate").trigger("click");
    await flushPromises();
    expect(api.listFiles).toHaveBeenLastCalledWith("8");
    expect(wrapper.find(".file-search-panel").exists()).toBe(false);
    expect(wrapper.find(".m-folder-breadcrumb").text()).toBe(
      "我的网盘/工作/资料",
    );
    await wrapper
      .find('.m-folder-head button[aria-label="返回上一级"]')
      .trigger("click");
    await flushPromises();
    expect(api.listFiles).toHaveBeenLastCalledWith("7");
    expect(wrapper.find(".m-folder-breadcrumb [aria-current]").text()).toBe(
      "工作",
    );
    await wrapper
      .find('.m-folder-head button[aria-label="返回上一级"]')
      .trigger("click");
    await flushPromises();
    expect(api.listFiles).toHaveBeenLastCalledWith(null);
  });
  it("opens an unloaded folder hit and retains results when locating fails", async () => {
    vi.mocked(api.searchFiles).mockResolvedValue({
      items: [{ file: data, ancestors: [work], path: "/工作" }],
      total: 1,
      page: 0,
      size: 20,
    });
    const wrapper = await open();
    await search(wrapper);
    vi.mocked(api.listFiles).mockRejectedValueOnce(new Error("失败"));
    await wrapper.find(".file-search-locate").trigger("click");
    await flushPromises();
    expect(wrapper.find(".file-search-panel").exists()).toBe(true);
    expect(alert).toHaveBeenCalled();
    await wrapper.find(".file-search-open").trigger("click");
    await flushPromises();
    expect(api.listFiles).toHaveBeenLastCalledWith("8");
    expect(wrapper.find(".m-folder-breadcrumb").text()).toBe(
      "我的网盘/工作/资料",
    );
  });
  it("passes current folder recursively and lets the user expand to the whole drive", async () => {
    vi.mocked(api.listFiles).mockImplementation(async (id) =>
      id === "7" ? [data] : id === "8" ? [document] : [work],
    );
    const wrapper = await open();
    await wrapper.find(".m-file-row").trigger("click");
    await flushPromises();
    await wrapper.find(".m-folder-search input").setValue("合同");
    await vi.advanceTimersByTimeAsync(250);
    await flushPromises();
    expect(vi.mocked(api.searchFiles).mock.lastCall![0]).toMatchObject({
      scope: "folder",
      parentId: "7",
    });
    await wrapper.find('[aria-label="搜索范围"]').setValue("all");
    await vi.advanceTimersByTimeAsync(250);
    await flushPromises();
    expect(vi.mocked(api.searchFiles).mock.lastCall![0]).toMatchObject({
      scope: "all",
      parentId: null,
    });
  });
});
