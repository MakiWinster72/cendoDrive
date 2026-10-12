// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import ClawPanel from "./ClawPanel.vue";
import { askClaw, getClawStatus, reindexClaw } from "../api/claw";
vi.mock("../api/claw", () => ({ askClaw: vi.fn(), getClawStatus: vi.fn(), reindexClaw: vi.fn() }));
let wrapper: ReturnType<typeof mount>;
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getClawStatus).mockResolvedValue({ configured: true, embeddingModel: "Qwen/Qwen3-VL-Embedding-8B", ready: 1, pending: 0, failed: 0, unsupported: 0, needsReindex: 0, files: [] });
  vi.mocked(askClaw).mockResolvedValue({ content: "预算为100万[1]", matched: true, sources: [{ citation: 1, fileId: "12", name: "预算.txt", path: "/预算.txt", excerpt: "项目预算100万" }] });
});
afterEach(() => wrapper?.unmount());
it("shows evidence and passes previous question for follow-ups", async () => {
  wrapper = mount(ClawPanel); await flushPromises();
  await wrapper.get("textarea").setValue("预算多少？"); await wrapper.get("form").trigger("submit"); await flushPromises();
  expect(askClaw).toHaveBeenCalledWith("预算多少？", undefined, expect.any(AbortSignal));
  expect(wrapper.text()).toContain("项目预算100万");
  await wrapper.get("textarea").setValue("来源？"); await wrapper.get("form").trigger("submit"); await flushPromises();
  expect(askClaw).toHaveBeenLastCalledWith("来源？", "预算多少？", expect.any(AbortSignal));
});
it("retains failed questions, hides diagnostics and submits confirmed reindex", async () => {
  vi.mocked(askClaw).mockRejectedValue(new Error("private-key"));
  wrapper = mount(ClawPanel); await flushPromises();
  await wrapper.get("textarea").setValue("预算"); await wrapper.get("form").trigger("submit"); await flushPromises();
  expect(wrapper.get("textarea").element.value).toBe("预算"); expect(wrapper.text()).not.toContain("private-key");
  await wrapper.findAll("button").find(b => b.text() === "确认重建已有文档索引")!.trigger("click"); await flushPromises();
  expect(reindexClaw).toHaveBeenCalledWith(undefined, expect.any(AbortSignal));
});
it("aborts pending inference when the panel unmounts", async () => {
  vi.mocked(askClaw).mockImplementation(() => new Promise(() => {}));
  wrapper = mount(ClawPanel); await flushPromises();
  await wrapper.get("textarea").setValue("预算"); await wrapper.get("form").trigger("submit"); await flushPromises();
  const signal = vi.mocked(askClaw).mock.calls[0]![2]; wrapper.unmount(); expect(signal.aborted).toBe(true);
});
