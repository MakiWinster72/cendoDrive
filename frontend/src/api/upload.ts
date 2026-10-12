import http from "./http";
import type { DriveItem } from "../stores/drive";

/** 超过该大小的文件走分片上传，小文件继续使用单请求上传。 */
export const CHUNK_THRESHOLD = 20 * 1024 * 1024;
const CHUNK_CONCURRENCY = 3;
const COMPLETED = "COMPLETED";

export interface ChunkedUploadOptions {
  file: File;
  parentId: string | null;
  signal: AbortSignal;
  onProgress: (progress: number) => void;
}

interface InitResponse {
  uploadId: string;
  chunkSize: number;
  totalChunks: number;
  uploadedIndexes: number[];
  status: string;
}

interface ChunkResponse {
  uploadId: string;
  receivedIndexes: number[];
  received: number;
  totalChunks: number;
}

async function sha256Hex(data: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", data);
  let hex = "";
  for (const byte of new Uint8Array(digest))
    hex += byte.toString(16).padStart(2, "0");
  return hex;
}

function throwIfAborted(signal: AbortSignal): void {
  if (signal.aborted) throw new DOMException("Upload cancelled", "AbortError");
}

async function initUpload(
  file: File,
  parentId: string | null,
  fileHash: string,
  signal: AbortSignal,
): Promise<InitResponse> {
  const { data } = await http.post<InitResponse>(
    "/upload/init",
    { name: file.name, parentId, totalSize: file.size, fileHash },
    { signal, timeout: 0 },
  );
  return data;
}

async function sendChunk(
  uploadId: string,
  index: number,
  chunk: ArrayBuffer,
  chunkHash: string,
  signal: AbortSignal,
  onLoaded: (loaded: number) => void,
): Promise<ChunkResponse> {
  const form = new FormData();
  form.append("file", new Blob([chunk]), `chunk-${index}`);
  form.append("uploadId", uploadId);
  form.append("index", String(index));
  form.append("chunkHash", chunkHash);
  const { data } = await http.post<ChunkResponse>("/upload/chunk", form, {
    headers: { "Content-Type": "multipart/form-data" },
    signal,
    timeout: 0,
    onUploadProgress: (event) => onLoaded(event.loaded),
  });
  return data;
}

async function mergeUpload(
  uploadId: string,
  signal: AbortSignal,
): Promise<DriveItem> {
  const { data } = await http.post<DriveItem>(
    "/upload/merge",
    { uploadId },
    { signal, timeout: 0 },
  );
  return data;
}

/**
 * 分片上传：init 返回的 uploadId 由文件信息派生，刷新或重试后重新 init 会命中同一会话，
 * 因此已上传的分片会被跳过，实现断点续传。合并阶段服务端会校验整文件 SHA-256。
 */
export async function uploadFileChunked({
  file,
  parentId,
  signal,
  onProgress,
}: ChunkedUploadOptions): Promise<DriveItem> {
  onProgress(0);
  const buffer = await file.arrayBuffer();
  throwIfAborted(signal);
  const init = await initUpload(
    file,
    parentId,
    await sha256Hex(buffer),
    signal,
  );
  if (init.status === COMPLETED) {
    const item = await mergeUpload(init.uploadId, signal);
    onProgress(100);
    return item;
  }

  const { chunkSize, totalChunks } = init;
  const sizeOf = (index: number) =>
    index === totalChunks - 1 ? file.size - index * chunkSize : chunkSize;
  const uploaded = new Set(init.uploadedIndexes);
  let uploadedBytes = 0;
  uploaded.forEach((index) => {
    uploadedBytes += sizeOf(index);
  });
  const inFlight = new Map<number, number>();
  const report = () => {
    let bytes = uploadedBytes;
    inFlight.forEach((loaded) => {
      bytes += loaded;
    });
    onProgress(Math.min(99, Math.round((bytes / file.size) * 100)));
  };
  report();

  const pending: number[] = [];
  for (let index = 0; index < totalChunks; index += 1)
    if (!uploaded.has(index)) pending.push(index);

  if (pending.length > 0) {
    const stop = new AbortController();
    const abortAll = () => stop.abort();
    signal.addEventListener("abort", abortAll, { once: true });
    if (signal.aborted) stop.abort();
    let cursor = 0;
    let failed = false;
    let failure: unknown;
    const worker = async () => {
      while (!failed) {
        const position = cursor;
        cursor += 1;
        if (position >= pending.length) return;
        const index = pending[position];
        const start = index * chunkSize;
        const chunk = buffer.slice(start, start + sizeOf(index));
        inFlight.set(index, 0);
        report();
        try {
          const chunkHash = await sha256Hex(chunk);
          throwIfAborted(signal);
          await sendChunk(
            init.uploadId,
            index,
            chunk,
            chunkHash,
            stop.signal,
            (loaded) => {
              inFlight.set(index, Math.min(loaded, sizeOf(index)));
              report();
            },
          );
          inFlight.delete(index);
          uploadedBytes += sizeOf(index);
          report();
        } catch (error) {
          inFlight.delete(index);
          if (!failed) {
            failed = true;
            failure = error;
          }
          stop.abort();
        }
      }
    };
    try {
      await Promise.all(
        Array.from(
          { length: Math.min(CHUNK_CONCURRENCY, pending.length) },
          worker,
        ),
      );
    } finally {
      signal.removeEventListener("abort", abortAll);
    }
    if (failed) throw failure;
  }

  const item = await mergeUpload(init.uploadId, signal);
  onProgress(100);
  return item;
}
