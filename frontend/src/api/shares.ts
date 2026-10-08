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
  hasExtractionCode?: boolean;
  // Kept only in the current owner's memory, never returned by the list endpoint.
  extractionCode?: string;
}

export const PERMANENT_SHARE_EXPIRY = "9999-12-31T23:59:59Z";

export function shareExpiryLabel(expiresAt: string): string {
  return new Date(expiresAt).getTime() === new Date(PERMANENT_SHARE_EXPIRY).getTime()
    ? "永久有效"
    : `有效至 ${new Date(expiresAt).toLocaleString("zh-CN", { hour12: false })}`;
}

export function shareUrl(share: ShareRecord, origin: string, autoFill = false): string {
  // Explicit opt-in: fragments are not transmitted in HTTP URLs or referrer headers.
  return `${origin}/share/${encodeURIComponent(share.token)}` +
    (autoFill && share.extractionCode ? `#code=${encodeURIComponent(share.extractionCode)}` : "");
}

export function shareClipboardText(share: ShareRecord, origin: string, autoFill = false): string {
  const link = shareUrl(share, origin, autoFill);
  return share.extractionCode ? `${link}\n提取码：${share.extractionCode}` : link;
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
  extractionCode?: string,
): Promise<ShareRecord> {
  const { data } = await http.post<ShareRecord>("/shares", {
    fileId,
    expiresInSeconds,
    ...(extractionCode ? { extractionCode } : {}),
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

export async function saveSharedFile(
  token: string,
  parentId: string | null = null,
  extractionCode?: string,
): Promise<DriveItemResponse> {
  const { data } = await http.post<DriveItemResponse>(
    `/shares/${encodeURIComponent(token)}/save`,
    { parentId },
    { timeout: 0, ...codeHeaders(extractionCode) },
  );
  return data;
}

export async function getPublicShare(
  token: string,
  signal?: AbortSignal,
  extractionCode?: string,
): Promise<ShareAccessResponse> {
  const { data } = await publicHttp.get<ShareAccessResponse>(
    `/shares/${encodeURIComponent(token)}`,
    { signal, ...codeHeaders(extractionCode) },
  );
  return data;
}

export async function downloadPublicShare(
  token: string,
  fallbackName: string,
  extractionCode?: string,
): Promise<void> {
  const response = await publicHttp.get<Blob>(
    `/shares/${encodeURIComponent(token)}/download`,
    { responseType: "blob", timeout: 0, ...codeHeaders(extractionCode) },
  ).catch(async (error: unknown) => {
    // Axios returns JSON error responses as Blob when responseType is blob.
    if (axios.isAxiosError(error) && error.response?.data instanceof Blob) {
      try {
        const data: unknown = JSON.parse(await error.response.data.text());
        if (typeof data === "object" && data !== null && "code" in data)
          error.response.data = data;
      } catch { /* Keep the original status and fallback message for non-JSON errors. */ }
    }
    throw error;
  });
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

function codeHeaders(code?: string) {
  return code ? { headers: { "X-Share-Code": code } } : {};
}

export function requiresShareCode(error: unknown): boolean {
  return axios.isAxiosError<ShareApiError>(error) &&
    ["SHARE_CODE_REQUIRED", "SHARE_CODE_INVALID"].includes(error.response?.data?.code || "");
}

export function isShareUnavailable(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 404;
}

export function shareErrorMessage(error: unknown, fallback: string): string {
  if (!axios.isAxiosError<ShareApiError>(error))
    return error instanceof Error ? error.message : fallback;
  if (!error.response) return "网络连接失败，请检查后端服务";
  switch (error.response.data?.code) {
    case "SHARE_CODE_REQUIRED":
      return "请输入提取码以访问分享";
    case "SHARE_CODE_INVALID":
      return "提取码不正确，请注意大小写";
    case "RATE_LIMITED":
      return "提取码尝试次数过多，请 15 分钟后重试";
    case "FILE_NOT_FOUND":
      return "文件不存在或无权分享";
    case "SHARE_NOT_FOUND":
      return "分享链接不存在、已过期或已取消";
    case "NAME_CONFLICT":
      return "我的网盘中已有同名文件，请先重命名后再转存";
    case "FOLDER_NOT_SHAREABLE":
      return "目前只支持分享单个文件";
    case "STORAGE_UNAVAILABLE":
    case "SHARE_UNAVAILABLE":
      return "分享或文件存储服务暂不可用，请稍后重试";
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
