import http from "./http";
import { getToken, invalidateSession } from "../stores/auth";
import type { ChatMessage } from "./aiChat";

const messages: Record<string, string> = {
  AI_NOT_CONFIGURED: "智能对话尚未配置，请联系管理员。",
  AI_RATE_LIMITED: "模型服务繁忙，请稍后重试。",
  AI_UPSTREAM_ERROR: "模型服务暂不可用，请稍后重试。",
  AI_INVALID_RESPONSE: "模型未返回有效回答，请重试。",
  AI_INCOMPLETE_RESPONSE: "模型回答中断，请重试。",
  AI_UNAVAILABLE: "模型连接失败或超时，请重试。",
  UNAUTHORIZED: "登录已失效，请重新登录。",
};
export class ChatStreamError extends Error {
  readonly code: string;
  constructor(code: unknown) {
    const safeCode = typeof code === "string" && Object.hasOwn(messages, code) ? code : "AI_UNAVAILABLE";
    super(messages[safeCode]); this.name = "ChatStreamError"; this.code = safeCode;
  }
}

/** Parse normalized backend SSE incrementally, including split UTF-8 and CRLF boundaries. */
export async function streamChat(history: ChatMessage[], signal: AbortSignal, onContent: (content: string) => void): Promise<string> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal.addEventListener("abort", abort, { once: true });
  if (signal.aborted) abort();
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, 75000);
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  const checkAbort = () => { if (controller.signal.aborted) throw new DOMException("Aborted", "AbortError"); };
  try {
    checkAbort();
    const token = getToken();
    const response = await fetch(`${(http.defaults.baseURL || "/api").replace(/\/$/, "")}/ai/chat/stream`, {
      method: "POST", signal: controller.signal,
      headers: { "Content-Type": "application/json", Accept: "text/event-stream", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify({ messages: history }),
    });
    checkAbort();
    if (!response.ok) {
      if (response.status === 401 && token && getToken() === token) {
        invalidateSession();
        if (location.pathname !== "/login" && location.pathname !== "/register")
          void import("../router").then(({ default: router }) => {
            if (!getToken()) void router.replace({ name: "login", query: { redirect: location.pathname } });
          });
      }
      const body: unknown = await response.json().catch(() => null);
      const code = body && typeof body === "object" && "code" in body ? body.code : undefined;
      throw new ChatStreamError(response.status === 401 ? "UNAUTHORIZED" : code);
    }
    reader = response.body?.getReader();
    if (!reader || !response.headers.get("Content-Type")?.toLowerCase().startsWith("text/event-stream"))
      throw new ChatStreamError("AI_INVALID_RESPONSE");
    const decoder = new TextDecoder("utf-8", { fatal: true });
    let content = "", line = "", event = "", size = 0, pendingCR = false, complete = false;
    let data: string[] = [];
    const dispatch = () => {
      const name = event; const value = data.join("\n");
      event = ""; data = []; size = 0;
      if (!value || !["delta", "done", "error"].includes(name)) return;
      let payload: unknown;
      try { payload = JSON.parse(value); } catch { throw new ChatStreamError("AI_INVALID_RESPONSE"); }
      if (!payload || typeof payload !== "object") throw new ChatStreamError("AI_INVALID_RESPONSE");
      if (name === "error") throw new ChatStreamError("code" in payload ? payload.code : undefined);
      if (name === "done") {
        if (!content.trim()) throw new ChatStreamError("AI_INVALID_RESPONSE");
        complete = true; return;
      }
      if (!("content" in payload) || typeof payload.content !== "string" || content.length + payload.content.length > 64000)
        throw new ChatStreamError("AI_INVALID_RESPONSE");
      content += payload.content;
      if (payload.content) onContent(content);
    };
    const readLine = () => {
      if (!line) dispatch();
      else if (!line.startsWith(":")) {
        const colon = line.indexOf(":");
        const field = colon < 0 ? line : line.slice(0, colon);
        let value = colon < 0 ? "" : line.slice(colon + 1);
        if (value.startsWith(" ")) value = value.slice(1);
        if (field === "event") event = value;
        if (field === "data") {
          size += value.length + 1;
          if (size > 131072) throw new ChatStreamError("AI_INVALID_RESPONSE");
          data.push(value);
        }
      }
      line = "";
    };
    const consume = (text: string) => {
      for (const char of text) {
        if (pendingCR) { pendingCR = false; if (char === "\n") continue; }
        if (char === "\r" || char === "\n") { readLine(); pendingCR = char === "\r"; }
        else { line += char; if (line.length > 131072) throw new ChatStreamError("AI_INVALID_RESPONSE"); }
        if (complete) break;
      }
    };
    while (!complete) {
      checkAbort();
      const chunk = await reader.read();
      checkAbort();
      try { consume(decoder.decode(chunk.value, { stream: !chunk.done })); }
      catch (error) { if (error instanceof ChatStreamError) throw error; throw new ChatStreamError("AI_INVALID_RESPONSE"); }
      if (chunk.done && !complete) {
        if (line) readLine();
        if (data.length) dispatch();
        if (!complete) throw new ChatStreamError("AI_INCOMPLETE_RESPONSE");
      }
    }
    return content;
  } catch (error) {
    if (signal.aborted) throw new DOMException("Aborted", "AbortError");
    if (timedOut) throw new ChatStreamError("AI_UNAVAILABLE");
    if (error instanceof ChatStreamError) throw error;
    throw new ChatStreamError("AI_UNAVAILABLE");
  } finally {
    clearTimeout(timer); signal.removeEventListener("abort", abort);
    if (reader) { await reader.cancel().catch(() => {}); reader.releaseLock(); }
  }
}
