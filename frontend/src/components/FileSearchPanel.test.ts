// @vitest-environment jsdom
import type { DefineComponent } from "vue";
import { mount, flushPromises } from "@vue/test-utils";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import FileSearchPanel from "./FileSearchPanel.vue";
import * as api from "../api/drive";
vi.mock("../api/drive", async original => ({ ...(await original<typeof api>()), searchFiles: vi.fn() }));
const item: api.DriveItemResponse = { id: "42", name: "合同.pdf", kind: "file", size: 1024, parentId: "8", updatedAt: "2026-01-01", deletedAt: null };
const hit: api.FileSearchHit = { file: item, ancestors: [], path: "/工作/资料" };
const response = (items = [hit], total = items.length, page = 0): api.FileSearchResponse => ({ items, total, page, size: 20 });
const wrappers: ReturnType<typeof mount>[] = [];
function open(parentId: string | null = null) {
  const wrapper = mount(FileSearchPanel as DefineComponent<{ query: string; parentId: string | null; initialType: api.SearchType; sort: "time" | "name" | "size"; busy?: boolean }>, { props: { query: "合同", parentId, initialType: "all", sort: "time" } }); wrappers.push(wrapper); return wrapper;
}
async function settle(ms = 250) { await vi.advanceTimersByTimeAsync(ms); await flushPromises(); }
beforeEach(() => { vi.useFakeTimers(); vi.resetAllMocks(); vi.mocked(api.searchFiles).mockResolvedValue(response()); });
afterEach(() => { wrappers.forEach(w => w.unmount()); wrappers.length = 0; vi.useRealTimers(); });

describe("filename search panel", () => {
  it("debounces filename changes and searches unloaded paths with the current scope", async () => {
    const wrapper = open("7");
    await wrapper.setProps({ query: "合" }); await settle(100); await wrapper.setProps({ query: "合同" }); await settle(249);
    expect(api.searchFiles).not.toHaveBeenCalled(); await settle(1);
    expect(api.searchFiles).toHaveBeenCalledTimes(1);
    expect(api.searchFiles).toHaveBeenLastCalledWith({ q: "合同", scope: "folder", parentId: "7", type: "all", sort: "time", page: 0, size: 20 }, expect.any(AbortSignal));
    expect(wrapper.text()).toContain("我的网盘/工作/资料"); expect(wrapper.text()).toContain("不搜索正文或图片文字");
  });
  it("ignores stale success and failure even if transport ignores abort", async () => {
    let resolve!: (result: api.FileSearchResponse) => void;
    let reject!: (error: Error) => void;
    vi.mocked(api.searchFiles).mockReturnValueOnce(new Promise(done => { resolve = done; }))
      .mockReturnValueOnce(new Promise((_done, fail) => { reject = fail; })).mockResolvedValue(response([{ ...hit, file: { ...item, name: "新合同.pdf" } }]));
    const wrapper = open(); await settle(); const oldSignal = vi.mocked(api.searchFiles).mock.calls[0]![1]!;
    await wrapper.setProps({ query: "第二份" }); expect(oldSignal.aborted).toBe(true); await settle();
    await wrapper.setProps({ query: "新合同" }); await settle();
    resolve(response()); reject(new Error("过期错误")); await flushPromises();
    expect(wrapper.findAll('.file-search-open b').map(x => x.text())).toEqual(["新合同.pdf"]);
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  });
  it("requests server pages and resets page when scope type or ordering changes", async () => {
    vi.mocked(api.searchFiles).mockResolvedValue(response([hit], 21));
    const wrapper = open("7"); await settle();
    await wrapper.find('[aria-label="搜索下一页"]').trigger("click"); await settle(0);
    expect(vi.mocked(api.searchFiles).mock.lastCall![0].page).toBe(1);
    await wrapper.find('[aria-label="搜索范围"]').setValue("all"); await settle();
    expect(vi.mocked(api.searchFiles).mock.lastCall![0]).toMatchObject({ scope: "all", parentId: null, page: 0 });
    await wrapper.find('[aria-label="搜索类型"]').setValue("image"); await settle();
    expect(vi.mocked(api.searchFiles).mock.lastCall![0].type).toBe("image");
    await wrapper.setProps({ sort: "size" }); await settle(); expect(vi.mocked(api.searchFiles).mock.lastCall![0].sort).toBe("size");
  });
  it("keeps errors inline and provides an explicit retry and useful empty state", async () => {
    vi.mocked(api.searchFiles).mockRejectedValueOnce(new Error("offline")).mockResolvedValue(response([]));
    const wrapper = open(); await settle(); expect(wrapper.find('[role="alert"]').text()).toContain("搜索失败");
    await wrapper.find('[role="alert"] button').trigger("click"); await settle(0);
    expect(api.searchFiles).toHaveBeenCalledTimes(2); expect(wrapper.text()).toContain("没有匹配的文件名");
  });
  it("emits real open and locate payloads, renders names as inert text and exits search", async () => {
    const unsafe = { ...hit, file: { ...item, name: '<img src=x onerror=alert(1)>.txt' } };
    vi.mocked(api.searchFiles).mockResolvedValue(response([unsafe]));
    const wrapper = open(); await settle(); expect(wrapper.find("img").exists()).toBe(false);
    await wrapper.find('.file-search-open').trigger("click"); await wrapper.find('.file-search-locate').trigger("click");
    expect(wrapper.emitted("open")![0]).toEqual([unsafe]); expect(wrapper.emitted("locate")![0]).toEqual([unsafe]);
    await wrapper.find('[aria-label="退出搜索"]').trigger("click"); expect(wrapper.emitted("clear")).toHaveLength(1);
  });
  it("aborts pending requests on unmount and does not send blank or oversized queries", async () => {
    vi.mocked(api.searchFiles).mockReturnValue(new Promise(() => {}));
    const wrapper = open(); await settle(); const signal = vi.mocked(api.searchFiles).mock.lastCall![1]!;
    wrapper.unmount(); expect(signal.aborted).toBe(true);
    const next = open(); await next.setProps({ query: " " }); await settle(); expect(api.searchFiles).toHaveBeenCalledTimes(1);
    await next.setProps({ query: "x".repeat(101) }); await settle(); expect(api.searchFiles).toHaveBeenCalledTimes(1);
    expect(next.text()).toContain("最多输入 100 个字符");
  });
});
