// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import FileTools from "./FileTools.vue";
import { folderChoices, type ToolAction } from "./fileTools";
import * as api from "../api/drive";
import { useDrive, type DriveItem } from "../stores/drive";
vi.mock("../api/drive", async importOriginal => ({ ...(await importOriginal<typeof api>()), listFolders: vi.fn(), getFileDetails: vi.fn(), moveFiles: vi.fn(), copyFiles: vi.fn(), setFavorite: vi.fn(), setHidden: vi.fn(), organizeFiles: vi.fn(), getUsage: vi.fn() }));
const file: DriveItem = { id: "42", name: "说明.txt", kind: "file", size: 3, parentId: null, updatedAt: "", deletedAt: null };
const folder = (id: string, name: string, parentId: string | null = null): DriveItem => ({ ...file, id, name, parentId, kind: "folder" });
const wrappers: ReturnType<typeof mount>[] = [];
async function open(action: ToolAction, items = [file]) {
  const wrapper = mount(FileTools, { props: { items, initialAction: action }, attachTo: document.body }); wrappers.push(wrapper); await flushPromises(); return wrapper;
}
function button(wrapper: ReturnType<typeof mount>, text: string) { return wrapper.findAll("button").find(entry => entry.text() === text)!; }
beforeEach(() => {
  vi.resetAllMocks(); useDrive().reset(); document.body.style.overflow = "auto";
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute("open"); };
  vi.mocked(api.listFolders).mockResolvedValue([folder("1", "资料"), folder("2", "资料", "1")]);
  for (const fn of [api.moveFiles, api.copyFiles, api.setFavorite, api.setHidden, api.organizeFiles]) vi.mocked(fn).mockResolvedValue([]);
  vi.mocked(api.getUsage).mockResolvedValue({ usedBytes: 3, limitBytes: 10, availableBytes: 7, trashBytes: 0, reservedBytes: 0 });
});
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = ""; });
describe("file tools workflows", () => {
  it("disambiguates same-named folders and disables selected descendants and cycles", () => {
    const choices = folderChoices([folder("1", "资料"), folder("2", "资料", "1"), folder("3", "环", "3")], ["1"]);
    expect(choices.find(choice => choice.id === "2")).toMatchObject({ label: "/资料/资料", disabled: true });
    expect(choices.find(choice => choice.id === "3")?.disabled).toBe(true);
    expect(choices[0]).toMatchObject({ id: null, disabled: false });
  });
  it.each(["move", "copy"] as const)("uses directory IDs for %s and closes after success", async action => {
    const wrapper = await open(action); await wrapper.find("select").setValue("2");
    await button(wrapper, action === "move" ? "确认移动" : "确认复制").trigger("click"); await flushPromises();
    expect(action === "move" ? api.moveFiles : api.copyFiles).toHaveBeenCalledWith(["42"], "2");
    expect(wrapper.emitted("changed")).toHaveLength(1); expect(wrapper.emitted("close")).toHaveLength(1);
    if (action === "copy") expect(api.getUsage).toHaveBeenCalledOnce();
  });
  it.each(["favorite", "hide"] as const)("toggles %s both ways", async action => {
    const fn = action === "favorite" ? api.setFavorite : api.setHidden;
    let wrapper = await open(action); await button(wrapper, action === "favorite" ? "确认收藏" : "确认移入隐藏空间").trigger("click"); await flushPromises();
    expect(fn).toHaveBeenLastCalledWith(["42"], true);
    wrapper = await open(action, [{ ...file, favorite: true, hidden: true }]);
    await button(wrapper, action === "favorite" ? "确认取消收藏" : "确认移出隐藏空间").trigger("click"); await flushPromises(); expect(fn).toHaveBeenLastCalledWith(["42"], false);
  });
  it("shows a conflict and requires an explicit retry", async () => {
    vi.mocked(api.moveFiles).mockRejectedValueOnce(new Error("同名冲突")); const wrapper = await open("move");
    await button(wrapper, "确认移动").trigger("click"); await flushPromises(); expect(wrapper.find('[role="alert"]').text()).toContain("同名冲突"); expect(wrapper.emitted("close")).toBeUndefined();
    await button(wrapper, "重新加载 / 重试").trigger("click"); await flushPromises(); await button(wrapper, "确认移动").trigger("click"); await flushPromises(); expect(api.moveFiles).toHaveBeenCalledTimes(2);
  });
  it("discloses deterministic organization before sending IDs", async () => {
    const wrapper = await open("organize"); expect(wrapper.text()).toContain("按扩展名"); expect(api.organizeFiles).not.toHaveBeenCalled(); await button(wrapper, "确认按类型整理").trigger("click"); await flushPromises(); expect(api.organizeFiles).toHaveBeenCalledWith(["42"]);
  });
  it("renders details inertly and restores scroll on Escape/unmount", async () => {
    vi.mocked(api.getFileDetails).mockResolvedValue({ file, path: '/<img src=x onerror=alert(1)>', contentSize: 3, createdAt: "today", fileCount: 1, folderCount: 0 });
    const wrapper = await open("details"); expect(api.getFileDetails).toHaveBeenCalledWith("42"); expect(wrapper.find("dl").text()).toContain("<img"); expect(wrapper.find("img").exists()).toBe(false);
    expect(document.body.style.overflow).toBe("hidden"); await wrapper.find("dialog").trigger("cancel"); expect(wrapper.emitted("close")).toHaveLength(1); wrapper.unmount(); expect(document.body.style.overflow).toBe("auto");
  });
});
