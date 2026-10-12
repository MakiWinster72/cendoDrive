// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { streamChat } from "./aiChatStream";
import { getToken, invalidateSession } from "../stores/auth";
const replace = vi.hoisted(() => vi.fn());
vi.mock("./http", () => ({ default: { defaults: { baseURL: "/api/" } } }));
vi.mock("../stores/auth", () => ({ getToken: vi.fn(), invalidateSession: vi.fn() }));
vi.mock("../router", () => ({ default: { replace } }));
const fetchMock = vi.fn<typeof fetch>();
const encoder = new TextEncoder();
const question = [{ role: "user" as const, content: "你好" }];
const delta = (content: string) => `event:delta\ndata:${JSON.stringify({ content })}\n\n`;
const done = "event:done\ndata:{}\n\n";
function response(text: string, splitBytes = 0) {
  const bytes = encoder.encode(text);
  return new Response(new ReadableStream<Uint8Array>({ start(controller) {
    if (splitBytes) for (let i = 0; i < bytes.length; i += splitBytes) controller.enqueue(bytes.slice(i, i + splitBytes));
    else controller.enqueue(bytes);
    controller.close();
  } }), { headers: { "Content-Type": "text/event-stream; charset=utf-8" } });
}
function live() {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const cancel = vi.fn();
  const body = new ReadableStream<Uint8Array>({ start(value) { controller = value; }, cancel });
  return { response: new Response(body, { headers: { "Content-Type": "text/event-stream" } }), cancel,
    emit: (text: string) => controller.enqueue(encoder.encode(text)) };
}
beforeEach(() => { vi.resetAllMocks(); vi.stubGlobal("fetch", fetchMock); vi.mocked(getToken).mockReturnValue("qa-token"); });
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });
const send = (callback = vi.fn(), signal = new AbortController().signal) => streamChat(question, signal, callback);
describe("incremental chat transport", () => {
  it("uses authenticated POST and decodes one-byte UTF-8 and CRLF boundaries", async () => {
    fetchMock.mockResolvedValue(response((delta("你好🙂\n") + delta("继续") + done).replaceAll("\n", "\r\n"), 1));
    const progress = vi.fn(); expect(await send(progress)).toBe("你好🙂\n继续");
    expect(progress.mock.calls.map(call => call[0])).toEqual(["你好🙂\n", "你好🙂\n继续"]);
    expect(fetchMock).toHaveBeenCalledWith("/api/ai/chat/stream", expect.objectContaining({ method: "POST", signal: expect.any(AbortSignal),
      headers: { "Content-Type": "application/json", Accept: "text/event-stream", Authorization: "Bearer qa-token" }, body: JSON.stringify({ messages: question }) }));
  });
  it("displays content while the response remains open, then completes and closes the reader", async () => {
    const stream = live(); fetchMock.mockResolvedValue(stream.response);
    let first!: () => void; const received = new Promise<void>(resolve => { first = resolve; });
    const progress = vi.fn(() => first()); let complete = false;
    const result = send(progress).then(value => { complete = true; return value; });
    stream.emit(delta("第一段")); await received;
    expect(progress).toHaveBeenCalledWith("第一段"); expect(complete).toBe(false);
    stream.emit(delta("第二段") + done); expect(await result).toBe("第一段第二段"); expect(stream.cancel).toHaveBeenCalled();
  });
  it("supports CR-only frames, comments, multiple data lines and an EOF-terminated done event", async () => {
    fetchMock.mockResolvedValue(response(': keep-alive\r\revent:delta\rdata:{\rdata:"content":"answer"}\r\revent:done\rdata:{}', 2));
    expect(await send()).toBe("answer");
  });
  it("requires a nonempty answer and a completion event instead of treating EOF as success", async () => {
    for (const [text, code] of [[delta("partial"), "AI_INCOMPLETE_RESPONSE"], [done, "AI_INVALID_RESPONSE"], [delta(" ") + done, "AI_INVALID_RESPONSE"]]) {
      fetchMock.mockResolvedValueOnce(response(text!)); await expect(send()).rejects.toMatchObject({ code });
    }
  });
  it("bounds response/event size and rejects malformed or nontext data", async () => {
    for (const text of ['event:delta\ndata:invalid\n\n', 'event:delta\ndata:{"content":[]}\n\n',
      delta("x".repeat(64001)), 'event:delta\ndata:' + "x".repeat(131073) + '\n\n',
      'event:delta\n' + ('data:' + "x".repeat(70000) + '\n').repeat(2) + '\n\n']) {
      fetchMock.mockResolvedValueOnce(response(text)); await expect(send()).rejects.toMatchObject({ code: "AI_INVALID_RESPONSE" });
    }
    fetchMock.mockResolvedValueOnce(new Response(new Uint8Array([255]), { headers: { "Content-Type": "text/event-stream" } }));
    await expect(send()).rejects.toMatchObject({ code: "AI_INVALID_RESPONSE" });
    fetchMock.mockResolvedValueOnce(new Response('{}', { headers: { "Content-Type": "application/json" } }));
    await expect(send()).rejects.toMatchObject({ code: "AI_INVALID_RESPONSE" });
  });
  it("uses safe stream and HTTP errors without leaking provider text or invalidating login", async () => {
    for (const code of ["AI_RATE_LIMITED", "AI_UPSTREAM_ERROR", "AI_INCOMPLETE_RESPONSE", "private-key"]) {
      fetchMock.mockResolvedValueOnce(response(`event:error\ndata:${JSON.stringify({ code, message: "private-key diagnostics" })}\n\n`));
      await expect(send()).rejects.toMatchObject({ code: code === "private-key" ? "AI_UNAVAILABLE" : code });
    }
    fetchMock.mockResolvedValueOnce(new Response('{"code":"AI_NOT_CONFIGURED","message":"private-key"}', { status: 503 }));
    await expect(send()).rejects.toMatchObject({ code: "AI_NOT_CONFIGURED", message: expect.not.stringContaining("private-key") });
    expect(invalidateSession).not.toHaveBeenCalled();
  });
  it("invalidates application 401 and redirects without confusing provider errors with logout", async () => {
    window.history.replaceState({}, "", "/ai");
    vi.mocked(invalidateSession).mockImplementation(() => vi.mocked(getToken).mockReturnValue(""));
    fetchMock.mockResolvedValue(new Response('{}', { status: 401 }));
    await expect(send()).rejects.toMatchObject({ code: "UNAUTHORIZED" });
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith({ name: "login", query: { redirect: "/ai" } }));
    expect(invalidateSession).toHaveBeenCalledTimes(1);
    window.history.replaceState({}, "", "/");
  });
  it("aborts immediately for an already stopped signal and unwinds an active fetch", async () => {
    const controller = new AbortController(); controller.abort();
    await expect(send(vi.fn(), controller.signal)).rejects.toMatchObject({ name: "AbortError" }); expect(fetchMock).not.toHaveBeenCalled();
    fetchMock.mockImplementation((_url, options) => new Promise((_resolve, reject) => {
      options!.signal!.addEventListener("abort", () => reject(new DOMException("Stopped", "AbortError")));
    }));
    const active = new AbortController(); const result = send(vi.fn(), active.signal);
    const rejected = expect(result).rejects.toMatchObject({ name: "AbortError" }); active.abort(); await rejected;
  });
  it("times out after 75 seconds and maps network failure to a safe retryable error", async () => {
    vi.useFakeTimers();
    fetchMock.mockImplementation((_url, options) => new Promise((_resolve, reject) => {
      options!.signal!.addEventListener("abort", () => reject(new DOMException("Timed out", "AbortError")));
    }));
    const result = send(); const rejected = expect(result).rejects.toMatchObject({ code: "AI_UNAVAILABLE" });
    await vi.advanceTimersByTimeAsync(75000); await rejected;
    vi.useRealTimers(); fetchMock.mockRejectedValueOnce(new Error("private-key connection error"));
    await expect(send()).rejects.toMatchObject({ code: "AI_UNAVAILABLE", message: expect.not.stringContaining("private-key") });
  });
});
