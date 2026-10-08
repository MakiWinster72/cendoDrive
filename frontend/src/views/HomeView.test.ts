// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import HomeView from "./HomeView.vue";
import * as api from "../api/drive";
import { useDrive, type DriveItem } from "../stores/drive";
const routerPush = vi.hoisted(() => vi.fn());
vi.mock("vue-router", () => ({ useRouter: () => ({ replace: vi.fn(), push: routerPush }) }));
vi.mock("../stores/auth", () => ({ useAuth: () => ({ user: { value: { username: "测试用户" } }, logout: vi.fn() }) }));
vi.mock("../api/drive", async importOriginal => ({ ...(await importOriginal<typeof api>()), renameFile: vi.fn(), createFolder: vi.fn(), listFiles: vi.fn(), listTrash: vi.fn(), listFavorites: vi.fn(), listHidden: vi.fn(), trashFiles: vi.fn(), getUsage: vi.fn() }));
const file: DriveItem = { id: "42", name: "说明.txt", kind: "file", size: 1024, parentId: null, updatedAt: "2026-01-01", deletedAt: null };
const wrappers: ReturnType<typeof mount>[] = [];
async function open() {
  const wrapper = mount(HomeView, { attachTo: document.body, global: { stubs: { UploadPanel: true, FileTools: true, FilePreview: true, ShareLinkDialog: true, ShareList: true, MobileMyShares: true, TransferPage: true } } }); wrappers.push(wrapper); await flushPromises();
  await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "文件")!.trigger("click"); await flushPromises(); return wrapper;
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute("open"); };
  Object.defineProperty(window, "innerWidth", { configurable: true, value: 390, writable: true });
  vi.resetAllMocks(); useDrive().reset(); vi.stubGlobal("confirm", vi.fn(() => true)); vi.stubGlobal("alert", vi.fn());
  vi.mocked(api.listFiles).mockResolvedValue([file]); vi.mocked(api.listTrash).mockResolvedValue([]); vi.mocked(api.listFavorites).mockResolvedValue([{ ...file, favorite: true }]); vi.mocked(api.listHidden).mockResolvedValue([{ ...file, hidden: true }]); vi.mocked(api.trashFiles).mockResolvedValue([{ ...file, deletedAt: "today" }]);
  vi.mocked(api.getUsage).mockResolvedValue({ usedBytes: 1024, limitBytes: 2048, availableBytes: 1024, trashBytes: 0, reservedBytes: 0 });
});
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals(); });
describe("mobile file management wiring", () => {
  it("closes the upload panel and creates a folder inline through its event", async () => {
    const wrapper = await open();
    await wrapper.find(".m-fab").trigger("click");
    const panel = wrapper.findComponent({ name: "UploadPanel" });
    expect(panel.props("open")).toBe(true);
    panel.vm.$emit("createFolder"); await flushPromises();
    expect(panel.props("open")).toBe(false);
    expect(wrapper.find(".m-file-list .inline-name-input").exists()).toBe(true);
    expect(api.createFolder).not.toHaveBeenCalled();
    const created = { ...file, id: "99", name: "上传菜单目录", kind: "folder" as const, size: 0 };
    vi.mocked(api.createFolder).mockResolvedValue(created);
    vi.mocked(api.listFiles).mockResolvedValue([file, created]);
    await wrapper.find(".m-file-list .inline-name-input").setValue(created.name);
    await wrapper.find(".m-file-list .inline-name-editor").trigger("submit"); await flushPromises();
    expect(api.createFolder).toHaveBeenCalledWith(created.name, null);
    expect(wrapper.find(".m-file-list .inline-name-editor").exists()).toBe(false);
    expect(wrapper.find(".m-file-list").text()).toContain(created.name);
  });

  it("offers folder creation in the mobile file page", async () => {
    const wrapper = await open();
    expect(wrapper.find('.mobile-app button[aria-label="新建文件夹"]').exists()).toBe(true);
    await wrapper.find('.mobile-app button[aria-label="新建文件夹"]').trigger("click");
    expect(wrapper.find(".m-file-list .inline-name-editor").exists()).toBe(true);
    expect(wrapper.find("dialog").exists()).toBe(false);
  });
  it("creates a trimmed root folder and refreshes the list without prompt", async () => {
    const wrapper = await open(), created = { ...file, id: "99", name: "目标目录", kind: "folder" as const, size: 0 };
    vi.mocked(api.createFolder).mockResolvedValue(created); vi.mocked(api.listFiles).mockResolvedValue([file, created]);
    await wrapper.find('.mobile-app button[aria-label="新建文件夹"]').trigger("click");
    await wrapper.find('.m-file-list .inline-name-input').setValue("  目标目录  ");
    await wrapper.find('.m-file-list .inline-name-editor').trigger("submit"); await flushPromises();
    expect(api.createFolder).toHaveBeenCalledWith("目标目录", null);
    expect(wrapper.find('.m-file-list').text()).toContain("目标目录");
    expect(wrapper.find('.m-file-list .inline-name-editor').exists()).toBe(false);
  });
  it("creates within the current folder and preserves errors for retry", async () => {
    const folder = { ...file, id: "7", name: "工作", kind: "folder" as const, size: 0 };
    vi.mocked(api.listFiles).mockResolvedValue([folder]); const wrapper = await open();
    vi.mocked(api.listFiles).mockResolvedValue([]); await wrapper.find('.m-file-row').trigger("click"); await flushPromises();
    await wrapper.find('.mobile-app button[aria-label="新建文件夹"]').trigger("click");
    await wrapper.find('.m-file-list .inline-name-input').setValue("../bad"); await wrapper.find('.m-file-list .inline-name-editor').trigger("submit");
    expect(api.createFolder).not.toHaveBeenCalled();
    await wrapper.find('.m-file-list .inline-name-input').setValue("目标目录");
    vi.mocked(api.createFolder).mockRejectedValueOnce(new Error("同名冲突"));
    await wrapper.find('.m-file-list .inline-name-editor').trigger("submit"); await flushPromises();
    expect(wrapper.find('.m-file-list .inline-name-editor [role="alert"]').text()).toContain("同名冲突");
    vi.mocked(api.createFolder).mockResolvedValue({ ...folder, id: "8", name: "目标目录", parentId: "7" });
    await wrapper.find('.m-file-list .inline-name-editor').trigger("submit"); await flushPromises();
    expect(api.createFolder).toHaveBeenLastCalledWith("目标目录", "7");
    expect(wrapper.find('.m-file-list .inline-name-editor').exists()).toBe(false);
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
  it("renames the mobile row without any dialog and selects only the stem", async () => {
    const wrapper = await open(); vi.mocked(api.renameFile).mockResolvedValue({ ...file, name: "新说明.txt" });
    await wrapper.find('.m-file-row input[type="checkbox"]').setValue(true);
    await wrapper.findAll('.m-selection-actions button').find(button => button.text() === "重命名")!.trigger("click"); await flushPromises();
    const input = wrapper.find('.m-file-list .inline-name-input').element as HTMLInputElement;
    expect(document.activeElement).toBe(input); expect(input.selectionStart).toBe(0); expect(input.selectionEnd).toBe(2);
    expect(wrapper.find("dialog").exists()).toBe(false); expect(wrapper.findAll(".inline-name-editor")).toHaveLength(1);
    await wrapper.find('.m-file-list .inline-name-input').setValue("新说明.txt"); await wrapper.find('.m-file-list .inline-name-editor').trigger("submit"); await flushPromises();
    expect(api.renameFile).toHaveBeenCalledWith("42", "新说明.txt"); expect(wrapper.find('.m-file-list').text()).toContain("新说明.txt");
    expect(wrapper.find('.inline-name-editor').exists()).toBe(false);
  });
  it("cancels mobile rename without changing the item or opening its preview", async () => {
    const wrapper = await open(); await wrapper.find('.m-file-row input[type="checkbox"]').setValue(true);
    await wrapper.findAll('.m-selection-actions button').find(button => button.text() === "重命名")!.trigger("click");
    await wrapper.find('.m-file-list .inline-name-input').setValue("未保存.txt"); await wrapper.find('.m-file-list .inline-name-input').trigger("keydown", { key: "Escape" });
    expect(api.renameFile).not.toHaveBeenCalled(); expect(wrapper.find('.m-file-list').text()).toContain("说明.txt"); expect(wrapper.find('.inline-name-editor').exists()).toBe(false);
    expect(wrapper.findComponent({ name: "FilePreview" }).exists()).toBe(false);
  });
  it.each(["list", "grid"])("starts desktop %s rename with F2 and retries a server conflict in place", async view => {
    window.innerWidth = 1280; const wrapper = await open();
    if (view === "grid") await wrapper.findAll('.view-switch button')[1]!.trigger("click");
    await wrapper.find(view === "grid" ? ".file-grid article" : ".file-row").trigger("keydown", { key: "F2" }); await flushPromises();
    expect(wrapper.findAll('.inline-name-editor')).toHaveLength(1); expect(wrapper.find('.mobile-app .inline-name-editor').exists()).toBe(false);
    expect(wrapper.find('dialog').exists()).toBe(false);
    const input = wrapper.find('.desktop-drive .inline-name-input'); await input.setValue("冲突.txt");
    vi.mocked(api.renameFile).mockRejectedValueOnce(new Error("同名冲突")); await wrapper.find('.desktop-drive .inline-name-editor').trigger("submit"); await flushPromises();
    expect(wrapper.find('.desktop-drive [role="alert"]').text()).toContain("同名冲突"); expect(useDrive().get("42")?.name).toBe("说明.txt");
    expect(document.activeElement).toBe(input.element);
    vi.mocked(api.renameFile).mockResolvedValue({ ...file, name: "重试.txt" }); await input.setValue("重试.txt"); await wrapper.find('.desktop-drive .inline-name-editor').trigger("submit"); await flushPromises();
    expect(api.renameFile).toHaveBeenCalledTimes(2); expect(wrapper.find('.desktop-drive').text()).toContain("重试.txt");
  });
  it("creates in desktop grid and cancels an unsaved draft on navigation", async () => {
    window.innerWidth = 1280; const wrapper = await open(); await wrapper.findAll('.view-switch button')[1]!.trigger("click");
    await wrapper.findAll('.tool-left button').find(button => button.text() === "新建文件夹")!.trigger("click"); await flushPromises();
    expect(wrapper.find('.file-grid .draft-folder-row .inline-name-editor').exists()).toBe(true);
    expect((wrapper.find('.file-grid input').element as HTMLInputElement).value).toBe("新建文件夹");
    await wrapper.findAll('.m-filter button').find(button => button.text() === "我的收藏")!.trigger("click"); await flushPromises();
    expect(wrapper.find('.inline-name-editor').exists()).toBe(false); expect(api.createFolder).not.toHaveBeenCalled();
  });
  it("can create after switching from favorites back to all files", async () => {
    const wrapper = await open(); await wrapper.findAll('.m-filter button').find(button => button.text() === "我的收藏")!.trigger("click"); await flushPromises();
    await wrapper.find('.mobile-app button[aria-label="新建文件夹"]').trigger("click"); await flushPromises();
    expect(wrapper.find('.m-file-list .inline-name-editor').exists()).toBe(true); expect(wrapper.find('.m-filter button.active').text()).toBe("全部");
  });
  it("preserves the draft and mounts only the visible editor when resizing across 768px", async () => {
    const wrapper = await open(); await wrapper.find('.mobile-app button[aria-label="新建文件夹"]').trigger("click");
    await wrapper.find('.m-file-list .inline-name-input').setValue("跨屏草稿"); window.innerWidth = 768; window.dispatchEvent(new Event("resize")); await flushPromises();
    expect(wrapper.findAll('.inline-name-editor')).toHaveLength(1); expect(wrapper.find('.mobile-app .inline-name-editor').exists()).toBe(false);
    expect((wrapper.find('.desktop-drive .inline-name-input').element as HTMLInputElement).value).toBe("跨屏草稿");
    window.innerWidth = 767; window.dispatchEvent(new Event("resize")); await flushPromises(); expect(wrapper.findAll('.inline-name-editor')).toHaveLength(1);
    expect((wrapper.find('.m-file-list .inline-name-input').element as HTMLInputElement).value).toBe("跨屏草稿"); expect(api.createFolder).not.toHaveBeenCalled();
  });
  it("does not leak cached descendants of hidden or trashed folders", async () => {
    const wrapper = await open(), drive = useDrive();
    drive.state.files.push({ ...file, id: "1", kind: "folder", hidden: true }, { ...file, id: "2", parentId: "1", name: "秘密.txt" }, { ...file, id: "3", kind: "folder", deletedAt: "today" }, { ...file, id: "4", parentId: "3", name: "已删.txt" }); await flushPromises();
    expect(wrapper.find(".m-file-list").text()).not.toContain("秘密.txt"); expect(wrapper.find(".m-file-list").text()).not.toContain("已删.txt");
  });
});

describe("mobile profile unavailable destinations", () => {
  it.each([
    [".membership-cta", "membership"],
    ['.membership-links button:first-child', "ai-points"],
    ['.membership-links button:last-child', "my-assets"],
    [".profile-promo > button", "membership"],
  ])("navigates from %s to its mobile page", async (selector, routeName) => {
    const wrapper = await open();
    await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "我的")!.trigger("click");
    await wrapper.find(selector).trigger("click");
    expect(routerPush).toHaveBeenCalledWith({ name: routeName });
  });

  it("opens the game center from the free download voucher", async () => {
    const wrapper = await open();
    await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "我的")!.trigger("click");
    await wrapper.find(".profile-game button").trigger("click");
    expect(routerPush).toHaveBeenCalledWith({ name: "game-center" });
  });
});

describe("mobile home media shortcuts", () => {
  it("shows the novel shortcut and routes video and novel entries to their hubs", async () => {
    const wrapper = await open();
    await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "首页")!.trigger("click");
    const shortcuts = wrapper.findAll(".m-tools button");
    const video = shortcuts.find(button => button.text() === "视频");
    const novel = shortcuts.find(button => button.text() === "小说");
    expect(video).toBeDefined();
    expect(novel).toBeDefined();
    await video!.trigger("click");
    expect(routerPush).toHaveBeenCalledWith({ name: "videos" });
    await novel!.trigger("click");
    expect(routerPush).toHaveBeenLastCalledWith({ name: "novels" });
  });
});
