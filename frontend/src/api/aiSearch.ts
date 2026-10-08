import http from "./http";
import type { AiSearchHit } from "./aiSearchTypes";

// 与 Maki 的搜索接口契约尚待确认；请求路径和响应结构集中在这里调整。
interface AiSearchResponse {
  results: AiSearchHit[];
}

export async function searchAiFiles(query: string, signal: AbortSignal): Promise<AiSearchHit[]> {
  const { data } = await http.post<AiSearchResponse>("/ai/search", { query }, { signal, timeout: 30000 });
  if (!Array.isArray(data.results)) throw new Error("AI 搜索响应格式不正确");
  return data.results;
}
