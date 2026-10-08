// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import HomeView from "./HomeView.vue";
import * as api from "../api/drive";
import { getHomeContent, getSplashAd } from "../api/content";
import { useDrive, type DriveItem } from "../stores/drive";
const routerPush = vi.hoisted(() => vi.fn());
const routerReplace = vi.hoisted(() => vi.fn());
vi.mock("vue-router", () => ({ useRouter: () => ({ replace: routerReplace, push: routerPush }) }));
vi.mock("../api/content", () => ({ getHomeContent: vi.fn(), getSplashAd: vi.fn() }));
import { getProfile } from "../api/users";
import { createShare } from "../api/shares";
vi.mock("../api/shares", async original => ({ ...await original<typeof import("../api/shares")>(), createShare: vi.fn() }));
vi.mock("../api/users", async original => ({ ...(await original<typeof import("../api/users")>()), getProfile: vi.fn() }));
const invalidateSession = vi.hoisted(() => vi.fn());
vi.mock("../stores/auth", () => ({ invalidateSession, useAuth: () => ({ user: { value: { username: "测试用户" } }, logout: vi.fn() }) }));
vi.mock("../api/drive", async importOriginal => ({ ...(await importOriginal<typeof api>()), searchFiles: vi.fn(), downloadFile: vi.fn(), renameFile: vi.fn(), createFolder: vi.fn(), listFiles: vi.fn(), listTrash: vi.fn(), listFavorites: vi.fn(), listHidden: vi.fn(), trashFiles: vi.fn(), getUsage: vi.fn() }));
const file: DriveItem = { id: "42", name: "说明.txt", kind: "file", size: 1024, parentId: null, updatedAt: "2026-01-01", deletedAt: null };
const wrappers: ReturnType<typeof mount>[] = [];
async function open() {
  const wrapper = mount(HomeView, { attachTo: document.body, global: { stubs: { UploadPanel: true, FileTools: true, FilePreview: true, ShareLinkDialog: true, ShareList: true, MobileMyShares: true } } }); wrappers.push(wrapper); await flushPromises();
  await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "文件")!.trigger("click"); await flushPromises(); return wrapper;
}
async function startCreate(wrapper: ReturnType<typeof mount>) {
  if (!wrapper.find('.mobile-app button[aria-label="新建文件夹"]').exists())
    await wrapper.find('.m-file-head-actions > button[aria-label="文件更多操作"]').trigger("click");
  await wrapper.find('.mobile-app button[aria-label="新建文件夹"]').trigger("click");
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute("open"); };
  Object.defineProperty(window, "innerWidth", { configurable: true, value: 390, writable: true });
  vi.resetAllMocks(); useDrive().reset(); vi.stubGlobal("confirm", vi.fn(() => true)); vi.stubGlobal("alert", vi.fn());
  vi.mocked(getHomeContent).mockRejectedValue(new Error("内容接口未接入")); vi.mocked(getSplashAd).mockResolvedValue(null);
  vi.mocked(getProfile).mockResolvedValue({ id: "1", username: "tester", nickname: "测试用户", hasAvatar: false });

  vi.mocked(api.searchFiles).mockResolvedValue({ items: [], total: 0, page: 0, size: 20 });
  vi.mocked(api.listFiles).mockResolvedValue([file]); vi.mocked(api.listTrash).mockResolvedValue([]); vi.mocked(api.listFavorites).mockResolvedValue([{ ...file, favorite: true }]); vi.mocked(api.listHidden).mockResolvedValue([{ ...file, hidden: true }]); vi.mocked(api.trashFiles).mockResolvedValue([{ ...file, deletedAt: "today" }]);
  vi.mocked(api.getUsage).mockResolvedValue({ usedBytes: 1024, limitBytes: 2048, availableBytes: 1024, trashBytes: 0, reservedBytes: 0 });
});
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals(); });
describe("share creation integration", () => {
  it("opens settings without creating a link, then keeps the confirmed code only in current state", async () => {
    vi.mocked(createShare).mockResolvedValue({ id: "51", token: "opaque", fileId: file.id, fileName: file.name, kind: "file", size: file.size, createdAt: "2026-10-08", expiresAt: "2030-01-01", status: "ACTIVE", hasExtractionCode: true });
    const wrapper = await open();
    await wrapper.get('.m-file-row input[type="checkbox"]').setValue(true);
    await wrapper.findAll(".m-selection-actions button").find(button => button.text() === "分享")!.trigger("click");
    expect(createShare).not.toHaveBeenCalled(); expect(wrapper.get("#share-settings-title").text()).toBe("分享设置");
    await wrapper.get(".share-settings [type=checkbox]").setValue(true);
    await wrapper.get("#share-extraction-code").setValue("Ab12");
    await wrapper.get(".share-settings form").trigger("submit"); await flushPromises();
    expect(createShare).toHaveBeenCalledWith(file.id, 604800, "Ab12");
    expect(wrapper.find(".share-settings").exists()).toBe(false);
    expect(wrapper.findComponent({ name: "ShareLinkDialog" }).props("share").extractionCode).toBe("Ab12");
    expect(useDrive().state.shares[0]?.extractionCode).toBe("Ab12");
    useDrive().reset(); expect(useDrive().state.shares).toEqual([]);
  });
});

describe("account page integration", () => {
  it.each([390,1280])("opens and returns from account management at width %s", async width => {
    window.innerWidth=width; const wrapper=await open();
    if (width<768) await wrapper.findAll(".mobile-app nav button").find(button=>button.text()==="我的")!.trigger("click");
    await wrapper.find(width<768 ? '.profile-avatar[aria-label="账号管理"]' : '.top-actions button[aria-label="账号管理"]').trigger("click");
    await flushPromises(); expect(wrapper.find(".account-page h1").text()).toBe("账号管理");
    await wrapper.find('.account-page button[aria-label="返回文件"]').trigger("click"); expect(wrapper.find(".account-page").exists()).toBe(false);
    expect(useDrive().state.files).toHaveLength(1);
  });
  it.each(["password","deletion"] as const)("clears private file state and routes to login after %s",async reason=>{
    const wrapper=await open();await wrapper.findAll(".mobile-app nav button").find(button=>button.text()==="我的")!.trigger("click");await wrapper.find('.profile-avatar').trigger("click");await flushPromises();
    wrapper.findComponent({name:"AccountPage"}).vm.$emit("signedOut",reason,"2026-10-08T12:00:00Z");await flushPromises();
    expect(invalidateSession).toHaveBeenCalledTimes(1);expect(useDrive().state.files).toEqual([]);
    expect(routerReplace).toHaveBeenCalledWith({name:"login",query:reason==="password" ? {passwordChanged:"1"} : {accountDeleted:"1",purgeAfter:"2026-10-08T12:00:00Z"}});
  });
});
describe("folder navigation and screenshot layout", () => {
  it("keeps the three reference actions connected to sign-in, transfers, and upload", async () => {
    const wrapper = mount(HomeView, { attachTo: document.body, global: { stubs: { UploadPanel: true, FileTools: true, FilePreview: true, ShareLinkDialog: true, ShareList: true, MobileMyShares: true } } });
    wrappers.push(wrapper);
    await flushPromises();
    expect(wrapper.findAll(".m-head-actions button").map(button => button.attributes("aria-label"))).toEqual(["签到", "传输列表", "上传文件"]);
    await wrapper.get('.m-head-actions button[aria-label="传输列表"]').trigger("click");
    expect(wrapper.find(".transfer-page").exists()).toBe(true);
    await wrapper.get('.transfer-header button[aria-label="返回"]').trigger("click");
    await wrapper.get('.m-head-actions button[aria-label="上传文件"]').trigger("click");
    expect(wrapper.findComponent({ name: "UploadPanel" }).props("open")).toBe(true);
  });
  it("routes the mobile search card and desktop AI entry to one page", async () => {
    const wrapper = mount(HomeView, { attachTo: document.body, global: { stubs: { UploadPanel: true, FileTools: true, FilePreview: true, ShareLinkDialog: true, ShareList: true, MobileMyShares: true } } });
    wrappers.push(wrapper);
    await flushPromises();
    await wrapper.get('.m-profile-search[aria-label="AI 搜索文件内容"]').trigger("click");
    await wrapper.get('.desktop-drive .ai-search-entry[aria-label="AI 搜索文件内容"]').trigger("click");
    expect(routerPush).toHaveBeenNthCalledWith(1, { name: "ai-search" });
    expect(routerPush).toHaveBeenNthCalledWith(2, { name: "ai-search" });
  });
  const parent: DriveItem = { ...file, id: "7", name: "U鱼游戏 S1-S3 三季", kind: "folder", size: 0 };
  const child: DriveItem = { ...parent, id: "8", name: "S01", parentId: "7" };
  async function inside() {
    vi.mocked(api.listFiles).mockImplementation(async id => id === "7" ? [child] : id === "8" ? [{ ...file, parentId: "8" }] : [parent]);
    const wrapper = await open();
    await wrapper.find('.m-file-row').trigger("click"); await flushPromises(); return wrapper;
  }
  it("shows the reference folder header, breadcrumb, blue folder, sorting and white panel instead of root filters", async () => {
    const wrapper = await inside();
    expect(wrapper.find('.m-folder-head button[aria-label="返回上一级"]').exists()).toBe(true);
    expect(wrapper.find('.m-folder-search input').attributes('placeholder')).toBe("按文件名搜索");
    expect(wrapper.find('.m-folder-breadcrumb').text()).toContain("我的网盘/U鱼游戏 S1-S3 三季");
    expect(wrapper.find('.m-folder-breadcrumb [aria-current="page"]').text()).toBe(parent.name);
    expect(wrapper.find('.m-folder-files .folder-sheet-icon').exists()).toBe(true);
    expect(wrapper.find('.m-filter').exists()).toBe(false); expect(wrapper.find('.m-capacity').exists()).toBe(false);
    expect(wrapper.find('.m-folder-toolbar select').exists()).toBe(true);
  });
  it("uses the folder arrows for downloads, preserving folder, keyword and sorting on return", async () => {
    const wrapper = await inside();
    expect(wrapper.find('.m-folder-head select').exists()).toBe(false);
    await wrapper.find('.m-folder-toolbar select').setValue('name');
    await wrapper.find('.m-folder-search input').setValue('S01');
    const loads = vi.mocked(api.listFiles).mock.calls.length;
    await wrapper.find('.m-folder-head .m-transfer-button').trigger('click');
    expect(wrapper.find('.transfer-tabs button.active').text()).toBe('下载');
    await wrapper.find('.transfer-header button[aria-label="返回"]').trigger('click');
    expect(wrapper.find('.m-folder-breadcrumb [aria-current]').text()).toBe(parent.name);
    expect((wrapper.find('.m-folder-toolbar select').element as HTMLSelectElement).value).toBe('name');
    expect((wrapper.find('.m-folder-search input').element as HTMLInputElement).value).toBe('S01');
    expect(api.listFiles).toHaveBeenCalledTimes(loads);
  });
  it("returns one level at a time, not directly from a nested folder to root", async () => {
    const wrapper = await inside(); await wrapper.find('.m-file-row').trigger("click"); await flushPromises();
    expect(wrapper.find('.m-folder-breadcrumb').text()).toContain("S01");
    await wrapper.find('.m-folder-head button[aria-label="返回上一级"]').trigger("click"); await flushPromises();
    expect(api.listFiles).toHaveBeenLastCalledWith("7");
    expect(wrapper.find('.m-folder-breadcrumb [aria-current]').text()).toBe(parent.name);
    await wrapper.find('.m-folder-head button[aria-label="返回上一级"]').trigger("click"); await flushPromises();
    expect(api.listFiles).toHaveBeenLastCalledWith(null); expect(wrapper.find('.m-folder-head').exists()).toBe(false);
    expect(wrapper.find('.m-filter').exists()).toBe(true);
  });
  it("breadcrumb ancestor navigation clears search and selection without saving an unfinished draft", async () => {
    const wrapper = await inside(); await wrapper.find('.m-file-row').trigger("click"); await flushPromises();
    await wrapper.find('.m-folder-head button[aria-label="文件夹更多操作"]').trigger("click");
    await wrapper.find('.m-folder-menu .m-new-folder').trigger("click"); await flushPromises();
    await wrapper.find('.m-file-list .inline-name-input').setValue("未保存目录");
    await wrapper.findAll('.m-folder-breadcrumb button').find(button => button.text() === parent.name)!.trigger("click"); await flushPromises();
    expect(api.createFolder).not.toHaveBeenCalled(); expect(wrapper.find('.inline-name-editor').exists()).toBe(false);
    await wrapper.find('.m-folder-search input').setValue("S01");
    expect(wrapper.find('.file-search-panel').exists()).toBe(true);
    await wrapper.find('.m-folder-breadcrumb button').trigger("click"); await flushPromises();
    expect(wrapper.find('.m-selection-sheet').exists()).toBe(false);
    expect((wrapper.find('.mobile-app .m-search input').element as HTMLInputElement).value).toBe("");
    expect(wrapper.find('.m-file-row b').text()).toBe(parent.name);
  });
  it("keeps the current folder on failed parent loading and allows an explicit retry", async () => {
    const wrapper = await inside(); vi.mocked(api.listFiles).mockRejectedValueOnce(new Error("目录加载失败"));
    await wrapper.find('.m-folder-head button[aria-label="返回上一级"]').trigger("click"); await flushPromises();
    expect(wrapper.find('.m-folder-breadcrumb [aria-current]').text()).toBe(parent.name);
    expect(wrapper.find('.m-file-row b').text()).toBe(child.name); expect(alert).toHaveBeenCalled();
    await wrapper.find('.m-folder-head button[aria-label="返回上一级"]').trigger("click"); await flushPromises();
    expect(wrapper.find('.m-folder-head').exists()).toBe(false);
  });
  it("blocks duplicate or stale navigation while a parent request is pending", async () => {
    const wrapper = await inside(); let finish!: (items: DriveItem[]) => void;
    vi.mocked(api.listFiles).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
    const count = vi.mocked(api.listFiles).mock.calls.length;
    await wrapper.find('.m-folder-head button[aria-label="返回上一级"]').trigger("click");
    expect(wrapper.find('.m-folder-head button[aria-label="返回上一级"]').attributes('disabled')).toBeDefined();
    await wrapper.find('.m-file-row').trigger("click");
    await wrapper.find('.m-folder-breadcrumb button').trigger("click");
    expect(api.listFiles).toHaveBeenCalledTimes(count + 1);
    finish([parent]); await flushPromises(); expect(wrapper.find('.m-folder-head').exists()).toBe(false);
  });
  it("sorts files by name and toggles the actual mobile grid", async () => {
    const wrapper = await inside();
    useDrive().state.files.push({ ...child, id: "9", name: "S03" }, { ...child, id: "10", name: "S02" });
    await wrapper.find('.m-folder-toolbar select').setValue("name");
    expect(wrapper.findAll('.m-folder-files b').map(row => row.text())).toEqual(["S01", "S02", "S03"]);
    await wrapper.find('.m-folder-toolbar button').trigger("click");
    expect(wrapper.find('.m-folder-files').classes()).toContain("is-grid");
    expect(wrapper.find('.m-folder-toolbar button').attributes('aria-label')).toBe("切换列表视图");
  });
  it("also offers desktop parent and ancestor navigation", async () => {
    window.innerWidth = 1280; const wrapper = await inside();
    await wrapper.find('.desktop-drive .file-row').trigger("dblclick"); await flushPromises();
    expect(wrapper.find('.desktop-drive .breadcrumb [aria-current]').text()).toBe(child.name);
    await wrapper.find('.desktop-drive .breadcrumb button[aria-label="返回上一级"]').trigger("click"); await flushPromises();
    expect(api.listFiles).toHaveBeenLastCalledWith("7");
    await wrapper.findAll('.desktop-drive .breadcrumb button').find(button => button.text() === '全部文件')!.trigger("click"); await flushPromises();
    expect(api.listFiles).toHaveBeenLastCalledWith(null); expect(wrapper.find('.breadcrumb').exists()).toBe(false);
  });
});
describe("mobile file management wiring", () => {
  it("opens the real download page from the root arrows and returns without changing files", async () => {
    const wrapper = await open();
    expect(wrapper.findAll('.m-file-head-actions > button').map(button => button.attributes('aria-label'))).toEqual(['传输列表', '文件更多操作']);
    await wrapper.find('.m-file-head-actions button[aria-label="文件更多操作"]').trigger('click');
    expect(wrapper.find('.m-folder-menu').exists()).toBe(true);
    await wrapper.find('.m-transfer-button').trigger('click');
    expect(wrapper.find('.m-folder-menu').exists()).toBe(false);
    expect(wrapper.find('.transfer-page h1').text()).toBe('传输列表');
    expect(wrapper.find('.transfer-tabs button.active').text()).toBe('下载');
    await wrapper.find('.transfer-header button[aria-label="返回"]').trigger('click');
    expect(wrapper.find('.transfer-page').exists()).toBe(false);
    expect(wrapper.find('.m-file-row b').text()).toBe(file.name);
  });
  it("lists real download completions rather than sample tasks", async () => {
    const wrapper = await open();
    vi.mocked(api.downloadFile).mockImplementationOnce(async (_id, _name, progress) => { progress?.(37); });
    await useDrive().download(file.id);
    expect(api.downloadFile).toHaveBeenCalledWith(file.id, file.name, expect.any(Function));
    await wrapper.find('.m-transfer-button').trigger('click');
    expect(wrapper.find('.transfer-task').text()).toContain(file.name);
    expect(wrapper.find('.transfer-task').text()).toContain('已下载至：浏览器下载目录');
  });
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
    expect(wrapper.find('.mobile-app button[aria-label="新建文件夹"]').exists()).toBe(false);
    expect(wrapper.findAll('.m-file-head-actions > button')).toHaveLength(2);
    await startCreate(wrapper);
    expect(wrapper.find(".m-file-list .inline-name-editor").exists()).toBe(true);
    expect(wrapper.find("dialog").exists()).toBe(false);
  });
  it("creates a trimmed root folder and refreshes the list without prompt", async () => {
    const wrapper = await open(), created = { ...file, id: "99", name: "目标目录", kind: "folder" as const, size: 0 };
    vi.mocked(api.createFolder).mockResolvedValue(created); vi.mocked(api.listFiles).mockResolvedValue([file, created]);
    await startCreate(wrapper);
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
    await wrapper.find('.m-folder-head button[aria-label="文件夹更多操作"]').trigger("click");
    await startCreate(wrapper);
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
    await startCreate(wrapper); await flushPromises();
    expect(wrapper.find('.m-file-list .inline-name-editor').exists()).toBe(true); expect(wrapper.find('.m-filter button.active').text()).toBe("全部");
  });
  it("preserves the draft and mounts only the visible editor when resizing across 768px", async () => {
    const wrapper = await open(); await startCreate(wrapper);
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
    expect(routerPush).toHaveBeenCalledWith(selector === ".profile-promo > button" ? "/membership" : { name: routeName });
  });

  it("opens the game center from the free download voucher", async () => {
    const wrapper = await open();
    await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "我的")!.trigger("click");
    await wrapper.find(".profile-game button").trigger("click");
    expect(routerPush).toHaveBeenCalledWith({ name: "game-center" });
  });

  it("marks unavailable profile services and reward actions explicitly", async () => {
    const wrapper = await open();
    await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "我的")!.trigger("click");
    await wrapper.findAll(".profile-services button").find(button => button.text().includes("借钱"))!.trigger("click");
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain("借钱服务暂未开放");
    await wrapper.find(".profile-missions button").trigger("click");
    expect(document.body.querySelector('[role="alertdialog"]')?.textContent).toContain("任务奖励功能暂未开放");
  });

  it("routes free membership to the membership page", async () => {
    const wrapper = await open();
    await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "我的")!.trigger("click");
    await wrapper.findAll(".profile-services button").find(button => button.text().includes("免费领会员"))!.trigger("click");
    expect(routerPush).toHaveBeenCalledWith({ name: "membership" });
  });
});

describe("mobile home media shortcuts", () => {
  it("renders home promotion fields returned by the content endpoint", async () => {
    vi.mocked(getHomeContent).mockResolvedValueOnce({
      membershipEntry: { title: "服务端会员入口", subtitle: "后端返回副标题", targetUrl: "/membership" },
      profileCampaign: { id: "campaign-live", title: "服务端活动", subtitle: "活动内容由接口返回", buttonText: "查看活动", targetUrl: "/membership", imageUrl: null },
    });
    const wrapper = await open();
    await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "首页")!.trigger("click");
    expect(wrapper.find(".m-vip").text()).toContain("服务端会员入口");
    await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "我的")!.trigger("click");
    expect(wrapper.find(".profile-promo").text()).toContain("服务端活动");
    expect(wrapper.find(".profile-promo").text()).toContain("查看活动");
  });

  it("shows the novel shortcut and routes video and novel entries to their hubs", async () => {
    const wrapper = await open();
    await wrapper.findAll(".mobile-app nav button").find(button => button.text() === "首页")!.trigger("click");
    expect(getHomeContent).toHaveBeenCalledOnce();
    expect(wrapper.find(".m-vip").text()).toContain("会员免费领");
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
