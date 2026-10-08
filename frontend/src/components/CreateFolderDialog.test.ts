// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import CreateFolderDialog from "./CreateFolderDialog.vue";
import * as api from "../api/drive";
import { useDrive, type DriveItem } from "../stores/drive";
vi.mock("../api/drive", async importOriginal => ({ ...(await importOriginal<typeof api>()), createFolder: vi.fn() }));
const item: DriveItem = { id: "99", name: "新目录", kind: "folder", size: 0, parentId: null, updatedAt: "", deletedAt: null };
const wrappers: ReturnType<typeof mount>[] = [];
function open() { const wrapper = mount(CreateFolderDialog, { props: { parentId: null, parentLabel: "根目录 /" }, attachTo: document.body }); wrappers.push(wrapper); return wrapper; }
beforeEach(() => {
  vi.resetAllMocks(); useDrive().reset(); document.body.style.overflow = "auto";
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute("open"); };
});
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = ""; });
describe("folder creation dialog", () => {
  it("Escape cancels without requesting or changing scroll after unmount", async () => {
    const wrapper = open(); expect(document.body.style.overflow).toBe("hidden");
    await wrapper.find("dialog").trigger("cancel"); expect(wrapper.emitted("close")).toHaveLength(1);
    expect(api.createFolder).not.toHaveBeenCalled(); wrapper.unmount(); expect(document.body.style.overflow).toBe("auto");
  });
  it("prevents duplicate submissions and closing while the request is pending", async () => {
    let resolve!: (value: DriveItem) => void;
    vi.mocked(api.createFolder).mockImplementation(() => new Promise(done => { resolve = done; }));
    const wrapper = open(); await wrapper.find("input").setValue("新目录");
    await wrapper.find("form").trigger("submit"); await wrapper.find("form").trigger("submit");
    await wrapper.find("dialog").trigger("cancel"); expect(wrapper.emitted("close")).toBeUndefined();
    expect(api.createFolder).toHaveBeenCalledOnce(); expect(wrapper.find('button[type="submit"]').attributes("disabled")).toBeDefined();
    resolve(item); await flushPromises(); expect(wrapper.emitted("created")?.[0]).toEqual([item]); expect(wrapper.emitted("close")).toHaveLength(1);
  });
  it("ignores late completion after unmount", async () => {
    let resolve!: (value: DriveItem) => void;
    vi.mocked(api.createFolder).mockImplementation(() => new Promise(done => { resolve = done; }));
    const wrapper = open(); await wrapper.find("input").setValue("新目录"); await wrapper.find("form").trigger("submit");
    wrapper.unmount(); resolve(item); await flushPromises();
    expect(wrapper.emitted("created")).toBeUndefined(); expect(wrapper.emitted("close")).toBeUndefined(); expect(document.body.style.overflow).toBe("auto");
  });
});
