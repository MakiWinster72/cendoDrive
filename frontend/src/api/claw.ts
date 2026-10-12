import http from "./http";
export interface ClawSource { citation: number; fileId: string; name: string; path: string; excerpt: string }
export interface ClawAnswer { content: string; sources: ClawSource[]; matched: boolean }
export interface ClawFile { fileId: string; name: string; path: string; status: string; errorCode: string | null; chunks: number }
export interface ClawStatus { configured: boolean; embeddingModel: string | null; ready: number; pending: number; failed: number; unsupported: number; needsReindex: number; files: ClawFile[] }
export async function getClawStatus(signal: AbortSignal): Promise<ClawStatus> {
  return (await http.get<ClawStatus>("/ai/claw/status", { signal })).data;
}
export async function askClaw(question: string, previousQuestion: string | undefined, signal: AbortSignal): Promise<ClawAnswer> {
  return (await http.post<ClawAnswer>("/ai/claw/ask", { question, previousQuestion }, { signal, timeout: 120000 })).data;
}
export async function reindexClaw(fileId: string | undefined, signal: AbortSignal): Promise<void> {
  await http.post(fileId ? `/ai/claw/files/${encodeURIComponent(fileId)}/reindex` : "/ai/claw/reindex", { confirmed: true }, { signal });
}
