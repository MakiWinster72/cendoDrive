import axios from "axios";
import http from "./http";
import type { DriveItem } from "../stores/drive";

export interface UploadFileOptions {
  file: File;
  parentId: string | null;
  signal: AbortSignal;
  onProgress: (progress: number) => void;
}

export async function uploadFile({
  file,
  parentId,
  signal,
  onProgress,
}: UploadFileOptions): Promise<DriveItem> {
  const form = new FormData();
  form.append("file", file);
  if (parentId !== null) form.append("parentId", parentId);

  const { data } = await http.post<DriveItem>("/files/upload", form, {
    headers: { "Content-Type": "multipart/form-data" },
    signal,
    timeout: 0,
    onUploadProgress: (event) => {
      if (event.total)
        onProgress(
          Math.min(99, Math.round((event.loaded / event.total) * 100)),
        );
    },
  });
  return data;
}

interface UploadApiError {
  code?: string;
  message?: string;
  fields?: Record<string, string>;
}

export function uploadErrorMessage(error: unknown): string {
  if (axios.isCancel(error)) return "已取消上传";
  if (!axios.isAxiosError<UploadApiError>(error)) return "上传失败，请重试";
  if (!error.response) return "网络连接失败，请检查后端服务";

  const { code, fields } = error.response.data ?? {};
  if (error.response.status === 400 && code === "INVALID_INPUT") {
    if (
      fields &&
      Object.keys(fields).some((field) =>
        field.toLowerCase().includes("parent"),
      )
    ) {
      return "目标文件夹信息无效，请重新选择上传位置";
    }
    if (
      fields &&
      Object.keys(fields).some((field) => field.toLowerCase().includes("file"))
    ) {
      return "文件信息无效，请重新选择文件";
    }
    return "文件或目标文件夹信息无效";
  }

  if (error.response.status === 401 && code === "UNAUTHORIZED")
    return "登录状态已失效，请重新登录";
  if (error.response.status === 404 && code === "NOT_FOUND")
    return "上传接口或请求资源不存在";
  if (error.response.status === 405 && code === "METHOD_NOT_ALLOWED")
    return "上传接口不支持此请求方式";
  if (error.response.status === 415 && code === "UNSUPPORTED_MEDIA_TYPE")
    return "不支持此文件类型";
  if (code === "FILE_TOO_LARGE" || code === "MAX_UPLOAD_SIZE_EXCEEDED")
    return "文件超过大小限制";
  if (code === "UPLOAD_NOT_FOUND") return "上传任务已失效，请重新选择文件";
  if (code === "INCOMPLETE_UPLOAD") return "还有分片未上传，请重试";
  if (code === "HASH_MISMATCH") return "文件校验失败，请重新选择文件";
  if (code === "STORAGE_UNAVAILABLE") return "存储服务暂不可用，请稍后重试";
  if (error.response.status >= 500 || code === "INTERNAL_ERROR")
    return "服务器处理失败，请稍后重试";
  return "上传失败，请重试";
}

export function isUploadCancelled(
  error: unknown,
  signal: AbortSignal,
): boolean {
  return signal.aborted || axios.isCancel(error);
}
