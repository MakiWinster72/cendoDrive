// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { reactive } from "vue";
import AiView from "./AiView.vue";
import { getChatStatus, streamChat } from "../api/aiChat";
const routing = vi.hoisted(() => ({ route: { query: {} as Record<string, string> }, replace: vi.fn(), push: vi.fn() }));
vi.mock("vue-router", () => ({ useRoute: () => routing.route, useRouter: () => ({ replace: routing.replace, push: routing.push }) }));
vi.mock("../api/aiChat", async original => ({ ...await original<typeof import("../api/aiChat")>(), getChatStatus: vi.fn(), streamChat: vi.fn() }));
vi.mock("../components/ClawPanel.vue", () => ({ default: { template: '<section>根据网盘文件内容搜索文件 上传文档后自动索引<textarea /></section>' } }));
const wrappers: ReturnType<typeof mount>[] = [];
async function open() { const w = mount(AiView); wrappers.push(w); await flushPromises(); return w; }
async function ask(w: ReturnType<typeof mount>, question = "你好") { await w.get("textarea").setValue(question); await w.get("form").trigger("submit"); await flushPromises(); }
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (value: unknown) => void; const promise = new Promise<T>((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }
beforeEach(() => {
  vi.resetAllMocks(); routing.route = reactive({ query: {} });
  routing.replace.mockImplementation(async value => { routing.route.query = value.query; });
  vi.mocked(getChatStatus).mockResolvedValue({ configured: true, model: "test-model" });
  vi.mocked(streamChat).mockResolvedValue("你好，我是扣扣AI");
});
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()));
describe("扣扣AI", () => {
  it("keeps the chat label without displaying the configured model", async () => {
    const w = await open();
    expect(w.get(".composer-tools").text()).toContain("智能对话");
    expect(w.find(".model-name").exists()).toBe(false);
    expect(w.text()).not.toContain("test-model");
  });
  it("shows the reference welcome, forwards multi-turn conversation and sends with Enter", async () => {
    const w = await open(); expect(w.text()).toContain("Hello!"); expect(w.text()).toContain("我是扣扣AI");
    await ask(w); expect(streamChat).toHaveBeenCalledWith([{ role: "user", content: "你好" }], expect.any(AbortSignal), expect.any(Function));
    await w.get("textarea").setValue("继续"); await w.get("textarea").trigger("keydown", { key: "Enter" }); await flushPromises();
    expect(vi.mocked(streamChat).mock.calls[1]![0]).toEqual([{ role: "user", content: "你好" }, { role: "assistant", content: "你好，我是扣扣AI" }, { role: "user", content: "继续" }]);
    expect(w.findAll(".message")).toHaveLength(4);
  });
  it("provides a real new chat and a placeholder history button", async () => {
    const w = await open(); await ask(w);
    await w.get('[aria-label="新建对话"]').trigger("click"); expect(w.text()).toContain("Hello!");
    await w.get('[aria-label="对话历史"]').trigger("click");
    expect(w.findAll(".message")).toHaveLength(0); expect(w.find("#ai-history").exists()).toBe(false);
    await w.get('[aria-label="切换AI模式"]').trigger("click"); await w.findAll(".mode-menu button")[1]!.trigger("click");
    await w.get('[aria-label="对话历史"]').trigger("click"); expect(w.get('[aria-label="对话历史"]').attributes("aria-expanded")).toBeUndefined();
    expect(w.find("#claw-history").exists()).toBe(false);
    await w.get('[aria-label="返回网盘"]').trigger("click"); expect(routing.push).toHaveBeenCalledWith({ name: "home" });
  });
  it("preserves completed chat across switching to Claw", async () => {
    const w = await open(); await ask(w);
    await w.get('[aria-label="切换AI模式"]').trigger("click"); await w.findAll(".mode-menu button")[1]!.trigger("click"); await flushPromises();
    expect(w.text()).toContain("扣扣Claw"); expect(w.text()).toContain("根据网盘文件内容搜索文件"); expect(w.find("textarea").exists()).toBe(true);
    expect(streamChat).toHaveBeenCalledTimes(1);
    await w.get('[aria-label="切换AI模式"]').trigger("click"); await w.findAll(".mode-menu button")[0]!.trigger("click"); await flushPromises(); expect(w.findAll(".message")).toHaveLength(2);
  });
  it("supports direct links to Claw", async () => {
    routing.route.query = { mode: "search" }; const w = await open();
    expect(w.text()).toContain("上传文档后自动索引"); expect(streamChat).not.toHaveBeenCalled();
  });
  it("disables unconfigured chat and retries status connection failure", async () => {
    vi.mocked(getChatStatus).mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce({ configured: false, model: null });
    const w = await open(); expect(w.text()).toContain("暂时无法连接");
    await w.get(".service-notice button").trigger("click"); await flushPromises();
    await ask(w); expect(w.text()).toContain("尚未配置"); expect(streamChat).not.toHaveBeenCalled();
    expect(w.get('[aria-label="发送问题"]').attributes("disabled")).toBeDefined();
  });
  it("restores a failed question, retries without duplicated history and sanitizes the error", async () => {
    vi.mocked(streamChat).mockRejectedValueOnce(new Error("secret-key")).mockResolvedValueOnce("成功");
    const w = await open(); await ask(w, "再试试"); expect(w.get("textarea").element.value).toBe("再试试");
    expect(w.findAll(".message")).toHaveLength(0); expect(w.text()).not.toContain("secret-key");
    await w.get(".chat-error button").trigger("click"); await flushPromises(); expect(w.findAll(".message")).toHaveLength(2);
    expect(vi.mocked(streamChat).mock.calls[1]![0]).toEqual([{ role: "user", content: "再试试" }]);
  });
  it("shows the sent question while waiting for the first AI token", async () => {
    const d = deferred<string>(); vi.mocked(streamChat).mockReturnValueOnce(d.promise);
    const w = await open(); await ask(w, "等待回复时也要显示的问题");
    expect(w.get(".message.user .user-content").text()).toBe("等待回复时也要显示的问题");
    expect(w.find(".message.assistant").exists()).toBe(false);
    expect(w.text()).toContain("正在思考");
    d.resolve("回答"); await flushPromises();
    expect(w.get(".message.user .user-content").text()).toBe("等待回复时也要显示的问题");
  });
  it("cancels requests and ignores late answers in a new conversation", async () => {
    const d = deferred<string>(); vi.mocked(streamChat).mockReturnValueOnce(d.promise);
    const w = await open(); await ask(w); const signal = vi.mocked(streamChat).mock.calls[0]![1];
    expect(w.text()).toContain("正在思考"); await w.get('[aria-label="新建对话"]').trigger("click");
    expect(signal.aborted).toBe(true); d.resolve("迟来的答案"); await flushPromises();
    expect(w.text()).toContain("Hello!"); expect(w.text()).not.toContain("迟来的答案"); expect(w.get("textarea").element.value).toBe("");
  });
  it("stops with the draft intact and aborts on unmount", async () => {
    const d = deferred<string>(); vi.mocked(streamChat).mockReturnValue(d.promise);
    const w = await open(); await ask(w); await w.get('[aria-label="停止回答"]').trigger("click");
    expect(w.get("textarea").element.value).toBe("你好"); expect(vi.mocked(streamChat).mock.calls[0]![1].aborted).toBe(true);
    await ask(w); w.unmount(); expect(vi.mocked(streamChat).mock.calls[1]![1].aborted).toBe(true); wrappers.pop();
  });
  it("renders incremental Markdown before completion and preserves explicitly stopped output", async () => {
    const d = deferred<string>(); vi.mocked(streamChat).mockReturnValueOnce(d.promise);
    const w = await open(); await ask(w);
    const [, signal, progress] = vi.mocked(streamChat).mock.calls[0]!;
    progress("**第一段**"); await flushPromises();
    expect(w.get(".message.assistant strong").text()).toBe("第一段"); expect(w.text()).toContain("正在回答");
    expect(w.get("textarea").attributes("readonly")).toBeDefined();
    progress("**第一段**，第二段"); await flushPromises(); expect(w.get(".message.assistant").text()).toContain("第二段");
    await w.get('[aria-label="停止回答"]').trigger("click"); expect(signal.aborted).toBe(true);
    expect(w.text()).toContain("回答已停止，内容未完成"); expect(w.findAll(".message")).toHaveLength(2);
    expect(w.get("textarea").element.value).toBe("");
    progress("迟到的增量"); d.resolve("迟到的完整答案"); await flushPromises(); expect(w.text()).not.toContain("迟到");
    await w.get('[aria-label="对话历史"]').trigger("click");
    expect(w.find("#ai-history").exists()).toBe(false);
    expect(w.text()).toContain("第二段");
    await ask(w, "继续");
    expect(vi.mocked(streamChat).mock.calls[1]![0]).toEqual([{ role: "user", content: "你好" }, { role: "assistant", content: "**第一段**，第二段" }, { role: "user", content: "继续" }]);
  });
  it("removes incomplete failed output and retries the exact question without duplicates", async () => {
    const d = deferred<string>(); vi.mocked(streamChat).mockReturnValueOnce(d.promise);
    const w = await open(); await ask(w, "需要完整回复");
    vi.mocked(streamChat).mock.calls[0]![2]("只有一部分"); await flushPromises(); expect(w.text()).toContain("只有一部分");
    d.reject({ code: "AI_INCOMPLETE_RESPONSE", message: "private-key" }); await flushPromises();
    expect(w.findAll(".message")).toHaveLength(0); expect(w.get("textarea").element.value).toBe("需要完整回复");
    expect(w.text()).not.toContain("private-key");
    await w.get(".chat-error button").trigger("click"); await flushPromises(); expect(w.findAll(".message")).toHaveLength(2);
    expect(vi.mocked(streamChat).mock.calls[1]![0]).toEqual([{ role: "user", content: "需要完整回复" }]);
  });
  it("aborts partial output on mode switching and ignores late incremental callbacks", async () => {
    const d = deferred<string>(); vi.mocked(streamChat).mockReturnValueOnce(d.promise);
    const w = await open(); await ask(w);
    const [, signal, progress] = vi.mocked(streamChat).mock.calls[0]!;
    progress("部分回答"); await flushPromises();
    await w.get('[aria-label="切换AI模式"]').trigger("click"); await w.findAll(".mode-menu button")[1]!.trigger("click"); await flushPromises();
    expect(signal.aborted).toBe(true); progress("错误的迟到内容"); d.resolve("迟到结果"); await flushPromises();
    await w.get('[aria-label="切换AI模式"]').trigger("click"); await w.findAll(".mode-menu button")[0]!.trigger("click"); await flushPromises();
    expect(w.text()).toContain("Hello!"); expect(w.text()).not.toContain("迟到"); expect(w.get("textarea").element.value).toBe("你好");
  });
  it("guards IME and Shift+Enter, fills suggestions without sending", async () => {
    const w = await open(); await w.findAll(".suggestions button")[0]!.trigger("click");
    const textarea = w.get("textarea"); expect(textarea.element.value).toContain("工作周报");
    await textarea.trigger("keydown", { key: "Enter", shiftKey: true });
    await textarea.trigger("compositionstart"); await textarea.trigger("keydown", { key: "Enter" }); await textarea.trigger("compositionend");
    await textarea.trigger("keydown", { key: "Enter", isComposing: true }); expect(streamChat).not.toHaveBeenCalled();
  });
});
