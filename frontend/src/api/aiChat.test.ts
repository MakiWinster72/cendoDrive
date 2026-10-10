import { beforeEach, describe, expect, it, vi } from "vitest";
import http from "./http";
import {
  chatContext,
  getChatStatus,
  sendChat,
  type ChatMessage,
} from "./aiChat";
vi.mock("./http", () => ({ default: { get: vi.fn(), post: vi.fn() } }));
beforeEach(() => vi.resetAllMocks());
describe("AI chat client", () => {
  it("uses authenticated backend paths, cancellable requests and a model-sized timeout", async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: { configured: true, model: "chat" },
    });
    vi.mocked(http.post).mockResolvedValue({ data: { content: "answer" } });
    const controller = new AbortController();
    const messages: ChatMessage[] = [{ role: "user", content: "hi" }];
    expect(await getChatStatus(controller.signal)).toEqual({
      configured: true,
      model: "chat",
    });
    expect(await sendChat(messages, controller.signal)).toBe("answer");
    expect(http.get).toHaveBeenCalledWith("/ai/chat/status", {
      signal: controller.signal,
    });
    expect(http.post).toHaveBeenCalledWith(
      "/ai/chat",
      { messages },
      { signal: controller.signal, timeout: 75000 },
    );
  });
  it("rejects empty provider content", async () => {
    vi.mocked(http.post).mockResolvedValue({ data: { content: " " } });
    await expect(sendChat([], new AbortController().signal)).rejects.toThrow(
      "Empty AI response",
    );
  });
  it("bounds context by complete turns, character limits and per-message limits", () => {
    const history: ChatMessage[] = Array.from({ length: 60 }, (_, i) => ({
      role: i % 2 ? "assistant" : "user",
      content: "x".repeat(9000),
    }));
    const messages = chatContext(history, "now");
    expect(messages.length).toBeLessThanOrEqual(41);
    expect(messages.length % 2).toBe(1);
    expect(messages[0]!.role).toBe("user");
    expect(messages.at(-1)).toEqual({ role: "user", content: "now" });
    expect(
      messages.reduce((sum, m) => sum + m.content.length, 0),
    ).toBeLessThanOrEqual(64000);
    expect(messages.every((m) => m.content.length <= 8000)).toBe(true);
    expect(history).toHaveLength(60);
  });
});
