// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import HomeView from "./HomeView.vue";
import * as api from "../api/drive";
import { useDrive, type DriveItem } from "../stores/drive";
vi.mock("vue-router", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("../stores/auth", () => ({ useAuth: () => ({ user: { value: { username: "测试用户" } }, logout: vi.fn() }) }));
vi.mock("../api/drive", async importOriginal => ({ ...(await importOriginal<typeof api>()), listFiles: vi.fn(), listTrash: vi.fn(), listFavorites: vi.fn(), listHidden: vi.fn(), trashFiles: vi.fn(), getUsage: vi.fn() }));
const file: DriveItem = { id: "42", name: "说明.txt", kind: "file", size: 1024, parentId: null, updatedAt: "2026-01-01", deletedAt: null };
const wrappers: ReturnType<typeof mount>[] = [];
async function open() {
  const wrapper = mount(HomeView, { global: { stubs: { UploadPanel: true, FileTools: true, FilePreview: true, ShareLinkDialog: true, ShareList: true, MobileMyShares: true } } }); wrappers.push(wrapper); await flushPromises();
  await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "文件")!.trigger("click"); await flushPromises(); return wrapper;
}
beforeEach(() => {
  vi.resetAllMocks(); useDrive().reset(); vi.stubGlobal("confirm", vi.fn(() => true)); vi.stubGlobal("alert", vi.fn());
  vi.mocked(api.listFiles).mockResolvedValue([file]); vi.mocked(api.listTrash).mockResolvedValue([]); vi.mocked(api.listFavorites).mockResolvedValue([{ ...file, favorite: true }]); vi.mocked(api.listHidden).mockResolvedValue([{ ...file, hidden: true }]); vi.mocked(api.trashFiles).mockResolvedValue([{ ...file, deletedAt: "today" }]);
  vi.mocked(api.getUsage).mockResolvedValue({ usedBytes: 1024, limitBytes: 2048, availableBytes: 1024, trashBytes: 0, reservedBytes: 0 });
});
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals(); });
describe("mobile file management wiring", () => {
  it("loads real quota and favorite/hidden views", async () => {
    const wrapper = await open(); expect(wrapper.find(".m-capacity").text()).toContain("1.0 KiB / 2.0 KiB"); expect(wrapper.find(".storage").text()).not.toContain("Mock");
    await wrapper.findAll(".m-filter button").find(button => button.text() === "我的收藏")!.trigger("click"); await flushPromises(); expect(api.listFavorites).toHaveBeenCalledOnce();
    await wrapper.findAll(".m-filter button").find(button => button.text() === "隐藏空间")!.trigger("click"); await flushPromises(); expect(api.listHidden).toHaveBeenCalledOnce();
  });
  it("mobile delete reaches the real trash API and refreshes quota", async () => {
    const wrapper = await open(); await wrapper.find('.m-file-row input[type="checkbox"]').setValue(true);
    await wrapper.findAll(".m-selection-actions button").find(button => button.text() === "删除")!.trigger("click"); await flushPromises(); expect(api.trashFiles).toHaveBeenCalledWith(["42"]); expect(api.getUsage).toHaveBeenCalledTimes(2); expect(wrapper.findAll(".m-file-row")).toHaveLength(0);
  });
  it.each([["移动", "move"], ["复制", "copy"], ["收藏", "favorite"], ["智能整理", "organize"], ["文件详情", "details"], ["移入隐藏空间", "hide"]])("routes mobile %s to the working tool", async (label, action) => {
    const wrapper = await open(); await wrapper.find('.m-file-row input[type="checkbox"]').setValue(true); await wrapper.findAll(".m-selection-actions button").find(button => button.text() === label)!.trigger("click");
    expect(wrapper.findComponent({ name: "FileTools" }).props("initialAction")).toBe(action);
  });
  it("does not leak cached descendants of hidden or trashed folders", async () => {
    const wrapper = await open(), drive = useDrive();
    drive.state.files.push({ ...file, id: "1", kind: "folder", hidden: true }, { ...file, id: "2", parentId: "1", name: "秘密.txt" }, { ...file, id: "3", kind: "folder", deletedAt: "today" }, { ...file, id: "4", parentId: "3", name: "已删.txt" }); await flushPromises();
    expect(wrapper.find(".m-file-list").text()).not.toContain("秘密.txt"); expect(wrapper.find(".m-file-list").text()).not.toContain("已删.txt");
  });
});
