// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AiView from "./AiView.vue";
import { getChatStatus, sendChat } from "../api/aiChat";

const routing = vi.hoisted(() => ({ back: vi.fn() }));
vi.mock("vue-router", () => ({ useRouter: () => routing }));
vi.mock("../api/aiChat", async original => ({ ...await original<typeof import("../api/aiChat")>(), getChatStatus: vi.fn(), sendChat: vi.fn() }));
const wrappers: ReturnType<typeof mount>[] = [];
async function open() { const wrapper = mount(AiView); wrappers.push(wrapper); await flushPromises(); return wrapper; }
async function ask(wrapper: ReturnType<typeof mount>, value = "你好") { await wrapper.get("textarea").setValue(value); await wrapper.get("form").trigger("submit"); await flushPromises(); }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getChatStatus).mockResolvedValue({ configured: true, model: "test-model" });
  vi.mocked(sendChat).mockResolvedValue("你好，我是库库AI");
});
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()));

describe("扣扣AI reference interface", () => {
  it("opens on the Hello welcome page, fills suggestions and returns to the previous page", async () => {
    const wrapper = await open();
    expect(wrapper.text()).toContain("Hello"); expect(wrapper.text()).toContain("欢迎使用扣扣AI");
    expect(wrapper.findAll(".quick-tools button")).toHaveLength(11);
    await wrapper.findAll(".suggestion-list button")[1]!.trigger("click");
    expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe("周末两天去哪儿玩");
    await wrapper.get('[aria-label="返回上一页"]').trigger("click");
    expect(routing.back).toHaveBeenCalledOnce();
  });

  it("switches to standard mode and keeps unopened entries honest", async () => {
    const wrapper = await open();
    await wrapper.get('[aria-label="切换AI模式"]').trigger("click");
    await wrapper.findAll(".mode-popover>button")[1]!.trigger("click");
    for (const title of ["视频课件", "音频会议纪要", "小说生成视频", "拍图写作", "AI写真", "播客解析", "PPT逐字稿", "视频精转文稿"]) expect(wrapper.text()).toContain(title);
    await wrapper.findAll(".tool-card")[0]!.trigger("click");
    expect(document.body.textContent).toContain("该功能尚未开放");
    await wrapper.get(".network-toggle").trigger("click");
    expect(wrapper.get(".network-toggle").attributes("aria-pressed")).toBe("true");
    expect(sendChat).not.toHaveBeenCalled();
  });

  it("uses the real chat client and forwards multi-turn context", async () => {
    const wrapper = await open();
    await ask(wrapper);
    expect(sendChat).toHaveBeenCalledWith([{ role: "user", content: "你好" }], expect.any(AbortSignal));
    await ask(wrapper, "继续");
    expect(vi.mocked(sendChat).mock.calls[1]![0]).toEqual([{ role: "user", content: "你好" }, { role: "assistant", content: "你好，我是库库AI" }, { role: "user", content: "继续" }]);
    expect(wrapper.findAll(".user-message")).toHaveLength(2);
    expect(wrapper.findAll(".assistant-message")).toHaveLength(2);
  });

  it("shows Kuku thinking and completed-answer controls", async () => {
    const reply = deferred<string>(); vi.mocked(sendChat).mockReturnValueOnce(reply.promise);
    const wrapper = await open(); await ask(wrapper);
    expect(wrapper.text()).toContain("思考中…");
    expect(wrapper.text()).toContain("正在理解您的问题");
    expect(wrapper.text()).toContain("任务处理中…");
    expect(wrapper.find(".thinking-orb").exists()).toBe(true);
    reply.resolve("你好呀！我是扣扣AI"); await flushPromises();
    expect(wrapper.text()).toContain("你好呀！我是扣扣AI");
    expect(wrapper.find('[aria-label="重新回答"]').exists()).toBe(true);
    await wrapper.get('[aria-label="朗读回答"]').trigger("click");
    expect(document.body.textContent).toContain("该功能尚未开放");
  });

  it("supports Enter send, Shift+Enter and IME guards", async () => {
    const wrapper = await open(); const textarea = wrapper.get("textarea");
    await textarea.setValue("回车发送"); await textarea.trigger("keydown", { key: "Enter", shiftKey: true }); expect(sendChat).not.toHaveBeenCalled();
    await textarea.trigger("compositionstart"); await textarea.trigger("keydown", { key: "Enter" }); await textarea.trigger("compositionend"); expect(sendChat).not.toHaveBeenCalled();
    await textarea.trigger("keydown", { key: "Enter" }); await flushPromises(); expect(sendChat).toHaveBeenCalledOnce();
  });

  it("stops an in-flight request and clears the current conversation", async () => {
    const pending = deferred<string>(); vi.mocked(sendChat).mockReturnValueOnce(pending.promise);
    const wrapper = await open(); await ask(wrapper);
    const signal = vi.mocked(sendChat).mock.calls[0]![1];
    await wrapper.get('[aria-label="停止回答"]').trigger("click");
    expect(signal.aborted).toBe(true);
    await wrapper.get('[aria-label="新建对话"]').trigger("click");
    pending.resolve("迟到的回答"); await flushPromises();
    expect(wrapper.find(".conversation").exists()).toBe(false);
  });

  it("keeps failed questions available and handles unconfigured status", async () => {
    vi.mocked(sendChat).mockRejectedValueOnce(new Error("secret"));
    const wrapper = await open(); await ask(wrapper, "请重试");
    expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe("请重试");
    expect(wrapper.text()).toContain("回答失败或超时"); expect(wrapper.text()).not.toContain("secret");
    wrapper.unmount(); wrappers.pop();
    vi.mocked(getChatStatus).mockResolvedValueOnce({ configured: false, model: null });
    const disabled = await open(); await disabled.get("textarea").setValue("无法发送");
    expect(disabled.get('[aria-label="发送问题"]').attributes("disabled")).toBeDefined();
  });
});
