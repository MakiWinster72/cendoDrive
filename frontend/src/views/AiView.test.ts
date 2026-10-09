// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { reactive } from "vue";
import AiView from "./AiView.vue";
import { getChatStatus, sendChat } from "../api/aiChat";
const routing = vi.hoisted(() => ({ route: { query: {} as Record<string, string> }, replace: vi.fn(), push: vi.fn() }));
vi.mock("vue-router", () => ({ useRoute: () => routing.route, useRouter: () => ({ replace: routing.replace, push: routing.push }) }));
vi.mock("../api/aiChat", async original => ({ ...await original<typeof import("../api/aiChat")>(), getChatStatus: vi.fn(), sendChat: vi.fn() }));
const wrappers: ReturnType<typeof mount>[] = [];
async function open() { const w = mount(AiView); wrappers.push(w); await flushPromises(); return w; }
async function ask(w: ReturnType<typeof mount>, question = "你好") { await w.get("textarea").setValue(question); await w.get("form").trigger("submit"); await flushPromises(); }
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (value: unknown) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
beforeEach(() => {
  vi.resetAllMocks(); routing.route = reactive({ query: {} });
  routing.replace.mockImplementation(async value => { routing.route.query = value.query; });
  vi.mocked(getChatStatus).mockResolvedValue({ configured: true, model: "test-model" });
  vi.mocked(sendChat).mockResolvedValue("你好，我是扣扣AI");
});
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()));
describe("扣扣AI", () => {
  it("shows the reference welcome, forwards multi-turn conversation and sends with Enter", async () => {
    const w = await open(); expect(w.text()).toContain("Hello!"); expect(w.text()).toContain("我是扣扣AI");
    await ask(w); expect(sendChat).toHaveBeenCalledWith([{ role: "user", content: "你好" }], expect.any(AbortSignal));
    await w.get("textarea").setValue("继续"); await w.get("textarea").trigger("keydown", { key: "Enter" }); await flushPromises();
    expect(vi.mocked(sendChat).mock.calls[1]![0]).toEqual([{ role: "user", content: "你好" }, { role: "assistant", content: "你好，我是扣扣AI" }, { role: "user", content: "继续" }]);
    expect(w.findAll(".message")).toHaveLength(4);
  });
  it("provides a real new chat and session-only history", async () => {
    const w = await open(); await ask(w);
    await w.get('[aria-label="新建对话"]').trigger("click"); expect(w.text()).toContain("Hello!");
    await w.get('[aria-label="对话历史"]').trigger("click"); await w.get(".history-item").trigger("click");
    expect(w.findAll(".message")).toHaveLength(2); expect(w.find("#ai-history").exists()).toBe(false);
    await w.get('[aria-label="返回网盘"]').trigger("click"); expect(routing.push).toHaveBeenCalledWith({ name: "home" });
  });
  it("keeps search as an honest placeholder and preserves completed chat across switching", async () => {
    const w = await open(); await ask(w);
    await w.get('[aria-label="切换AI模式"]').trigger("click"); await w.findAll(".mode-menu button")[1]!.trigger("click"); await flushPromises();
    expect(w.text()).toContain("千度AI"); expect(w.text()).toContain("当前仅提供模式入口"); expect(w.find("textarea").exists()).toBe(false);
    expect(sendChat).toHaveBeenCalledTimes(1);
    await w.get(".outline-button").trigger("click"); await flushPromises(); expect(w.findAll(".message")).toHaveLength(2);
  });
  it("supports direct search links without presenting a working search", async () => {
    routing.route.query = { mode: "search" }; const w = await open();
    expect(w.text()).toContain("向量数据库"); expect(sendChat).not.toHaveBeenCalled();
  });
  it("disables unconfigured chat and retries status connection failure", async () => {
    vi.mocked(getChatStatus).mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce({ configured: false, model: null });
    const w = await open(); expect(w.text()).toContain("暂时无法连接");
    await w.get(".service-notice button").trigger("click"); await flushPromises();
    await ask(w); expect(w.text()).toContain("尚未配置"); expect(sendChat).not.toHaveBeenCalled();
    expect(w.get('[aria-label="发送问题"]').attributes("disabled")).toBeDefined();
  });
  it("restores a failed question, retries without duplicated history and sanitizes the error", async () => {
    vi.mocked(sendChat).mockRejectedValueOnce(new Error("secret-key")).mockResolvedValueOnce("成功");
    const w = await open(); await ask(w, "再试试"); expect(w.get("textarea").element.value).toBe("再试试");
    expect(w.findAll(".message")).toHaveLength(0); expect(w.text()).not.toContain("secret-key");
    await w.get(".chat-error button").trigger("click"); await flushPromises(); expect(w.findAll(".message")).toHaveLength(2);
    expect(vi.mocked(sendChat).mock.calls[1]![0]).toEqual([{ role: "user", content: "再试试" }]);
  });
  it("cancels requests and ignores late answers in a new conversation", async () => {
    const d = deferred<string>(); vi.mocked(sendChat).mockReturnValueOnce(d.promise);
    const w = await open(); await ask(w); const signal = vi.mocked(sendChat).mock.calls[0]![1];
    expect(w.text()).toContain("正在思考"); await w.get('[aria-label="新建对话"]').trigger("click");
    expect(signal.aborted).toBe(true); d.resolve("迟来的答案"); await flushPromises();
    expect(w.text()).toContain("Hello!"); expect(w.text()).not.toContain("迟来的答案"); expect(w.get("textarea").element.value).toBe("");
  });
  it("stops with the draft intact and aborts on unmount", async () => {
    const d = deferred<string>(); vi.mocked(sendChat).mockReturnValue(d.promise);
    const w = await open(); await ask(w); await w.get('[aria-label="停止回答"]').trigger("click");
    expect(w.get("textarea").element.value).toBe("你好"); expect(vi.mocked(sendChat).mock.calls[0]![1].aborted).toBe(true);
    await ask(w); w.unmount(); expect(vi.mocked(sendChat).mock.calls[1]![1].aborted).toBe(true); wrappers.pop();
  });
  it("guards IME and Shift+Enter, fills suggestions without sending", async () => {
    const w = await open(); await w.findAll(".suggestions button")[0]!.trigger("click");
    const textarea = w.get("textarea"); expect(textarea.element.value).toContain("工作周报");
    await textarea.trigger("keydown", { key: "Enter", shiftKey: true });
    await textarea.trigger("compositionstart"); await textarea.trigger("keydown", { key: "Enter" }); await textarea.trigger("compositionend");
    await textarea.trigger("keydown", { key: "Enter", isComposing: true }); expect(sendChat).not.toHaveBeenCalled();
  });
});
