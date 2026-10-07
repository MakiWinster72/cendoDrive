import axios from "axios";
import http from "./http";

export type FileKind = "folder" | "file";
export interface DriveItemResponse {
  id: string;
  name: string;
  kind: FileKind;
  size: number;
  parentId: string | null;
  updatedAt: string;
  deletedAt: string | null;
}
interface ApiError {
  code: string;
  message: string;
}

export async function listFiles(
  parentId: string | null,
): Promise<DriveItemResponse[]> {
  const { data } = await http.get<DriveItemResponse[]>("/files", {
    params: parentId ? { parentId } : {},
  });
  return data;
}
export async function createFolder(
  name: string,
  parentId: string | null,
): Promise<DriveItemResponse> {
  const { data } = await http.post<DriveItemResponse>("/files/folder", {
    name,
    parentId,
  });
  return data;
}
export async function renameFile(
  id: string,
  name: string,
): Promise<DriveItemResponse> {
  const { data } = await http.put<DriveItemResponse>(`/files/${id}/rename`, {
    name,
  });
  return data;
}
export async function moveFile(
  id: string,
  parentId: string | null,
): Promise<DriveItemResponse> {
  const { data } = await http.put<DriveItemResponse>(`/files/${id}/move`, {
    parentId,
  });
  return data;
}
export async function uploadFile(
  file: File,
  parentId: string | null,
): Promise<DriveItemResponse> {
  const form = new FormData();
  form.append("file", file);
  if (parentId !== null) form.append("parentId", parentId);
  const { data } = await http.post<DriveItemResponse>("/files/upload", form, {
    headers: { "Content-Type": undefined },
    timeout: 120000,
  });
  return data;
}
export async function listTrash(): Promise<DriveItemResponse[]> {
  const { data } = await http.get<DriveItemResponse[]>("/files/trash");
  return data;
}
export async function trashFiles(ids: string[]): Promise<DriveItemResponse[]> {
  const { data } = await http.post<DriveItemResponse[]>("/files/trash", {
    ids,
  });
  return data;
}
export async function restoreFiles(
  ids: string[],
): Promise<DriveItemResponse[]> {
  const { data } = await http.post<DriveItemResponse[]>(
    "/files/trash/restore",
    { ids },
  );
  return data;
}
export async function deleteFilesForever(ids: string[]): Promise<void> {
  await http.post("/files/trash/delete", { ids });
}
export async function emptyTrash(): Promise<void> {
  await http.delete("/files/trash");
}
export async function downloadFile(
  id: string,
  fallbackName: string,
  onProgress?: (value: number) => void,
): Promise<void> {
  const response = await http.get<Blob>(`/files/${id}/download`, {
    responseType: "blob",
    onDownloadProgress: (event) => {
      if (event.total) onProgress?.(event.loaded / event.total * 100);
    },
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
  // Let the browser resolve the download before releasing the blob URL.
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
export function driveErrorMessage(error: unknown, fallback: string): string {
  if (!axios.isAxiosError<ApiError>(error))
    return error instanceof Error ? error.message : fallback;
  if (!error.response) return "网络连接失败，请检查后端服务";
  const messages: Record<string, string> = {
    FILE_NOT_FOUND: "文件不存在或无权访问",
    NAME_CONFLICT: "当前目录已存在同名项目",
    INVALID_NAME: "名称不能包含 / 或 \\",
    INVALID_MOVE: "不能移动到自身或子目录",
    NOT_A_FOLDER: "目标位置不是文件夹",
    CONTENT_NOT_FOUND: "文件内容不存在",
    FOLDER_NOT_DOWNLOADABLE: "暂不支持下载文件夹",
    INVALID_INPUT: "文件为空或不符合要求",
    ALREADY_IN_TRASH: "文件已在回收站中",
    NOT_IN_TRASH: "只能操作回收站中的文件",
    PARENT_IN_TRASH: "请先恢复所在的父文件夹",
    INVALID_SELECTION: "请至少选择一个文件",
  };
  return messages[error.response.data?.code] || fallback;
}
