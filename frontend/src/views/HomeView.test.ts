// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import HomeView from "./HomeView.vue";
import * as api from "../api/drive";
import { useDrive, type DriveItem } from "../stores/drive";
vi.mock("vue-router", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("../stores/auth", () => ({ useAuth: () => ({ user: { value: { username: "测试用户" } }, logout: vi.fn() }) }));
vi.mock("../api/drive", async importOriginal => ({ ...(await importOriginal<typeof api>()), createFolder: vi.fn(), listFiles: vi.fn(), listTrash: vi.fn(), listFavorites: vi.fn(), listHidden: vi.fn(), trashFiles: vi.fn(), getUsage: vi.fn() }));
const file: DriveItem = { id: "42", name: "说明.txt", kind: "file", size: 1024, parentId: null, updatedAt: "2026-01-01", deletedAt: null };
const wrappers: ReturnType<typeof mount>[] = [];
async function open() {
  const wrapper = mount(HomeView, { global: { stubs: { UploadPanel: true, FileTools: true, FilePreview: true, ShareLinkDialog: true, ShareList: true, MobileMyShares: true } } }); wrappers.push(wrapper); await flushPromises();
  await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "文件")!.trigger("click"); await flushPromises(); return wrapper;
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute("open"); };
  vi.resetAllMocks(); useDrive().reset(); vi.stubGlobal("confirm", vi.fn(() => true)); vi.stubGlobal("alert", vi.fn());
  vi.mocked(api.listFiles).mockResolvedValue([file]); vi.mocked(api.listTrash).mockResolvedValue([]); vi.mocked(api.listFavorites).mockResolvedValue([{ ...file, favorite: true }]); vi.mocked(api.listHidden).mockResolvedValue([{ ...file, hidden: true }]); vi.mocked(api.trashFiles).mockResolvedValue([{ ...file, deletedAt: "today" }]);
  vi.mocked(api.getUsage).mockResolvedValue({ usedBytes: 1024, limitBytes: 2048, availableBytes: 1024, trashBytes: 0, reservedBytes: 0 });
});
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals(); });
describe("mobile file management wiring", () => {
  it("offers folder creation in the mobile file page", async () => {
    const wrapper = await open();
    expect(wrapper.find('.mobile-app button[aria-label="新建文件夹"]').exists()).toBe(true);
    await wrapper.find('.mobile-app button[aria-label="新建文件夹"]').trigger("click");
    expect(wrapper.find('dialog[aria-labelledby="create-folder-title"]').exists()).toBe(true);
  });
  it("creates a trimmed root folder and refreshes the list without prompt", async () => {
    const wrapper = await open(), created = { ...file, id: "99", name: "目标目录", kind: "folder" as const, size: 0 };
    vi.mocked(api.createFolder).mockResolvedValue(created); vi.mocked(api.listFiles).mockResolvedValue([file, created]);
    await wrapper.find('.mobile-app button[aria-label="新建文件夹"]').trigger("click");
    await wrapper.find('input[name="folderName"]').setValue("  目标目录  ");
    await wrapper.find('.create-folder-form').trigger("submit"); await flushPromises();
    expect(api.createFolder).toHaveBeenCalledWith("目标目录", null);
    expect(wrapper.find('.m-file-list').text()).toContain("目标目录");
    expect(wrapper.find('.create-folder-dialog').exists()).toBe(false);
  });
  it("creates within the current folder and preserves errors for retry", async () => {
    const folder = { ...file, id: "7", name: "工作", kind: "folder" as const, size: 0 };
    vi.mocked(api.listFiles).mockResolvedValue([folder]); const wrapper = await open();
    vi.mocked(api.listFiles).mockResolvedValue([]); await wrapper.find('.m-file-row').trigger("click"); await flushPromises();
    await wrapper.find('.mobile-app button[aria-label="新建文件夹"]').trigger("click");
    await wrapper.find('input[name="folderName"]').setValue("../bad"); await wrapper.find('.create-folder-form').trigger("submit");
    expect(api.createFolder).not.toHaveBeenCalled();
    await wrapper.find('input[name="folderName"]').setValue("目标目录");
    vi.mocked(api.createFolder).mockRejectedValueOnce(new Error("同名冲突"));
    await wrapper.find('.create-folder-form').trigger("submit"); await flushPromises();
    expect(wrapper.find('.create-folder-dialog [role="alert"]').text()).toContain("同名冲突");
    vi.mocked(api.createFolder).mockResolvedValue({ ...folder, id: "8", name: "目标目录", parentId: "7" });
    await wrapper.find('.create-folder-form').trigger("submit"); await flushPromises();
    expect(api.createFolder).toHaveBeenLastCalledWith("目标目录", "7");
    expect(wrapper.find('.create-folder-dialog').exists()).toBe(false);
  });
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
