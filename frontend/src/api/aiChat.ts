import http from "./http";

export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}
export interface ChatStatus {
  configured: boolean;
  model: string | null;
}
export async function getChatStatus(signal?: AbortSignal): Promise<ChatStatus> {
  return (await http.get<ChatStatus>("/ai/chat/status", { signal })).data;
}
export async function sendChat(
  messages: ChatMessage[],
  signal: AbortSignal,
): Promise<string> {
  const { data } = await http.post<{ content: string }>(
    "/ai/chat",
    { messages },
    { signal, timeout: 75000 },
  );
  if (typeof data.content !== "string" || !data.content.trim())
    throw new Error("Empty AI response");
  return data.content;
}

// Keep complete turns, dropping the oldest pair instead of splitting the conversation.
export function chatContext(
  history: ChatMessage[],
  question: string,
): ChatMessage[] {
  const messages = [
    ...history
      .slice(-40)
      .map((m) => ({ ...m, content: m.content.slice(0, 8000) })),
    { role: "user" as const, content: question },
  ];
  while (
    messages.length > 1 &&
    messages.reduce((sum, m) => sum + m.content.length, 0) > 64000
  )
    messages.splice(0, 2);
  return messages;
}
