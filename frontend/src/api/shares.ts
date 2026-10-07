import axios from "axios";
import http from "./http";
import type { DriveItemResponse, FileKind } from "./drive";

export type ShareStatus = "ACTIVE" | "CANCELLED";

export interface ShareRecord {
  id: string;
  token: string;
  fileId: string;
  fileName: string;
  kind: FileKind;
  size: number;
  createdAt: string;
  expiresAt: string;
  status: ShareStatus;
}

export interface ShareAccessResponse {
  file: DriveItemResponse;
  expiresAt: string;
}

interface ShareApiError {
  code?: string;
  message?: string;
  fields?: Record<string, string>;
}

// Public share endpoints deliberately use an Axios client without the user's Bearer token.
const publicHttp = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: 30000,
});

export async function createShare(
  fileId: string,
  expiresInSeconds: number,
): Promise<ShareRecord> {
  const { data } = await http.post<ShareRecord>("/shares", {
    fileId,
    expiresInSeconds,
  });
  return data;
}

export async function listShares(): Promise<ShareRecord[]> {
  const { data } = await http.get<ShareRecord[]>("/shares");
  return data;
}

export async function cancelShare(shareId: string): Promise<void> {
  await http.delete(`/shares/${encodeURIComponent(shareId)}`);
}

export async function getPublicShare(
  token: string,
): Promise<ShareAccessResponse> {
  const { data } = await publicHttp.get<ShareAccessResponse>(
    `/shares/${encodeURIComponent(token)}`,
  );
  return data;
}

export async function downloadPublicShare(
  token: string,
  fallbackName: string,
): Promise<void> {
  const response = await publicHttp.get<Blob>(
    `/shares/${encodeURIComponent(token)}/download`,
    {
      responseType: "blob",
      timeout: 0,
    },
  );
  const disposition = response.headers["content-disposition"] as
    string | undefined;
  const encodedName = disposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const name = encodedName ? decodeURIComponent(encodedName) : fallbackName;
  const url = URL.createObjectURL(response.data);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

export function shareErrorMessage(error: unknown, fallback: string): string {
  if (!axios.isAxiosError<ShareApiError>(error))
    return error instanceof Error ? error.message : fallback;
  if (!error.response) return "网络连接失败，请检查后端服务";
  switch (error.response.data?.code) {
    case "FILE_NOT_FOUND":
      return "文件不存在或无权分享";
    case "SHARE_NOT_FOUND":
      return "分享链接不存在、已过期或已取消";
    case "INVALID_INPUT":
      return "分享设置无效，请重试";
    case "UNAUTHORIZED":
      return "登录状态已失效，请重新登录";
    case "INTERNAL_ERROR":
      return "服务器处理失败，请稍后重试";
    default:
      return error.response.status >= 500
        ? "服务器处理失败，请稍后重试"
        : fallback;
  }
}
