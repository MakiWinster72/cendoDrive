import http from "./http";
import SparkMD5 from "spark-md5";
import type { DriveItem } from "../stores/drive";

export const CHUNK_UPLOAD_THRESHOLD = 100 * 1024 * 1024;
export const CHUNK_SIZE = 5 * 1024 * 1024;
const HASH_READ_SIZE = 2 * 1024 * 1024;

export function shouldUseChunkedUpload(fileSize: number): boolean {
  return fileSize > CHUNK_UPLOAD_THRESHOLD;
}

export type ChunkedUploadPhase = "hashing" | "uploading";

export interface ChunkedUploadOptions {
  file: File;
  parentId: string | null;
  signal: AbortSignal;
  onPhase: (phase: ChunkedUploadPhase) => void;
  onProgress: (progress: number) => void;
}

interface InitUploadResponse {
  uploadId: string;
  chunkSize: number;
  totalChunks: number;
}

interface UploadStatusResponse {
  uploadId: string;
  totalChunks: number;
  uploadedChunks: number[];
}

function throwIfAborted(signal: AbortSignal) {
  if (signal.aborted) throw new DOMException("Upload cancelled", "AbortError");
}

async function calculateFileHash(
  file: File,
  signal: AbortSignal,
  onProgress: (progress: number) => void,
) {
  const hash = new SparkMD5.ArrayBuffer();
  for (let start = 0; start < file.size; start += HASH_READ_SIZE) {
    throwIfAborted(signal);
    const buffer = await file
      .slice(start, Math.min(start + HASH_READ_SIZE, file.size))
      .arrayBuffer();
    throwIfAborted(signal);
    hash.append(buffer);
    onProgress(
      Math.round(
        (Math.min(start + HASH_READ_SIZE, file.size) / file.size) * 100,
      ),
    );
  }
  return hash.end();
}

export async function uploadFileInChunks({
  file,
  parentId,
  signal,
  onPhase,
  onProgress,
}: ChunkedUploadOptions): Promise<DriveItem> {
  onPhase("hashing");
  const fileHash = await calculateFileHash(file, signal, onProgress);
  throwIfAborted(signal);

  const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
  const { data: upload } = await http.post<InitUploadResponse>(
    "/upload/init",
    {
      fileName: file.name,
      fileSize: file.size,
      fileHash,
      parentId,
      chunkSize: CHUNK_SIZE,
      totalChunks,
    },
    { signal },
  );

  if (
    !upload.uploadId ||
    upload.chunkSize !== CHUNK_SIZE ||
    upload.totalChunks !== totalChunks
  ) {
    throw new Error("分片初始化响应与接口协议不一致");
  }

  const { data: status } = await http.get<UploadStatusResponse>(
    `/upload/${encodeURIComponent(upload.uploadId)}/status`,
    { signal },
  );
  if (
    status.uploadId !== upload.uploadId ||
    status.totalChunks !== totalChunks
  ) {
    throw new Error("分片状态响应与接口协议不一致");
  }

  const uploadedChunks = new Set(status.uploadedChunks || []);
  let confirmedBytes = 0;
  for (const chunkNumber of uploadedChunks) {
    if (
      Number.isInteger(chunkNumber) &&
      chunkNumber >= 0 &&
      chunkNumber < totalChunks
    ) {
      confirmedBytes += Math.min(
        CHUNK_SIZE,
        file.size - chunkNumber * CHUNK_SIZE,
      );
    }
  }

  onPhase("uploading");
  onProgress(Math.min(99, Math.floor((confirmedBytes / file.size) * 100)));

  for (let chunkNumber = 0; chunkNumber < totalChunks; chunkNumber += 1) {
    throwIfAborted(signal);
    if (uploadedChunks.has(chunkNumber)) continue;

    const start = chunkNumber * CHUNK_SIZE;
    const end = Math.min(start + CHUNK_SIZE, file.size);
    const chunk = file.slice(start, end);
    const form = new FormData();
    form.append("uploadId", upload.uploadId);
    form.append("chunkNumber", String(chunkNumber));
    form.append("totalChunks", String(totalChunks));
    form.append("fileHash", fileHash);
    form.append("chunk", chunk, file.name);

    await http.post("/upload/chunk", form, {
      headers: { "Content-Type": "multipart/form-data" },
      signal,
      timeout: 0,
      onUploadProgress: (event) => {
        if (!event.total) return;
        const sentChunkBytes = Math.min(
          chunk.size,
          (event.loaded / event.total) * chunk.size,
        );
        onProgress(
          Math.min(
            99,
            Math.floor(((confirmedBytes + sentChunkBytes) / file.size) * 100),
          ),
        );
      },
    });

    confirmedBytes += chunk.size;
    uploadedChunks.add(chunkNumber);
    onProgress(Math.min(99, Math.floor((confirmedBytes / file.size) * 100)));
  }

  throwIfAborted(signal);
  const { data: item } = await http.post<DriveItem>(
    "/upload/merge",
    {
      uploadId: upload.uploadId,
      fileHash,
    },
    { signal, timeout: 0 },
  );
  onProgress(100);
  return item;
}
