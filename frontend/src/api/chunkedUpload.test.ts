import { beforeEach, describe, expect, it, vi } from "vitest";
import http from "./http";
import { CHUNK_SIZE, chunkSizeFor, shouldUseChunkedUpload, uploadFileInChunks } from "./chunkedUpload";
import { createSpeedSampler } from "../utils/transferSpeed";
import type { DriveItem } from "../stores/drive";

vi.mock("./http", () => ({ default: { get: vi.fn(), post: vi.fn() } }));

beforeEach(() => vi.clearAllMocks());

describe("chunked upload API contract", () => {
  it("uses server-confirmed chunks even for small non-empty files", () => {
    expect(shouldUseChunkedUpload(13 * 1024 * 1024)).toBe(true);
    expect(shouldUseChunkedUpload(1)).toBe(true);
    expect(shouldUseChunkedUpload(0)).toBe(false);
    expect(CHUNK_SIZE).toBe(64 * 1024);
  });

  it("keeps large files within the server chunk-count and size limits", () => {
    for (const size of [1, 13 * 1024 ** 2, 128 * 1024 ** 2, 1024 ** 3, 10 * 1024 ** 3]) {
      const chunkSize = chunkSizeFor(size);
      expect(Math.ceil(size / chunkSize)).toBeLessThanOrEqual(2048);
      expect(chunkSize).toBeLessThanOrEqual(5 * 1024 ** 2);
    }
  });

  it("does not count buffered multipart bytes before server acknowledgement", async () => {
    const file = Object.assign(new Blob([new Uint8Array(CHUNK_SIZE)]), { name: "photo.png" }) as File;
    let acknowledge!: () => void;
    const onProgress = vi.fn();
    vi.mocked(http.get).mockResolvedValue({ data: { uploadId: "u", totalChunks: 1, uploadedChunks: [] } });
    vi.mocked(http.post).mockImplementation(async (url, _body, config) => {
      if (url === "/upload/init") return { data: { uploadId: "u", chunkSize: CHUNK_SIZE, totalChunks: 1 } };
      if (url === "/upload/chunk") {
        config?.onUploadProgress?.({ loaded: file.size, total: file.size } as never);
        await new Promise<void>(resolve => { acknowledge = resolve; });
      }
      return { data: { id: "42" } };
    });
    const pending = uploadFileInChunks({ file, parentId: null, signal: new AbortController().signal, onPhase: vi.fn(), onProgress });
    await vi.waitFor(() => expect(acknowledge).toBeTypeOf("function"));
    expect(onProgress.mock.calls.filter(call => call[1] !== undefined)).toEqual([[0, 0]]);
    acknowledge();
    await pending;
    expect(onProgress).toHaveBeenCalledWith(99, file.size);
    expect(onProgress).toHaveBeenLastCalledWith(100);
  });
  it("measures 128 KiB/s from timed server acknowledgements, not network buffering", async () => {
    let time = 0;
    const sample = createSpeedSampler(() => time);
    const speeds: number[] = [];
    const file = Object.assign(new Blob([new Uint8Array(CHUNK_SIZE * 2)]), { name: "photo.png" }) as File;
    vi.mocked(http.get).mockResolvedValue({ data: { uploadId: "u", totalChunks: 2, uploadedChunks: [] } });
    vi.mocked(http.post).mockImplementation(async url => {
      if (url === "/upload/init") return { data: { uploadId: "u", chunkSize: CHUNK_SIZE, totalChunks: 2 } };
      if (url === "/upload/chunk") time += 500;
      return { data: { id: "42" } };
    });
    await uploadFileInChunks({ file, parentId: null, signal: new AbortController().signal, onPhase: vi.fn(), onProgress: (_progress, bytes) => {
      if (bytes !== undefined) speeds.push(sample(bytes));
    } });
    expect(speeds).toEqual([0, 128 * 1024, 128 * 1024]);
  });

  it("resumes from server-reported chunks, sends only missing chunks, then merges", async () => {
    const file = Object.assign(new Blob([new Uint8Array(CHUNK_SIZE + 13)]), {
      name: "archive.zip",
    }) as File;
    const signal = new AbortController().signal;
    const onPhase = vi.fn();
    const onProgress = vi.fn();
    const uploadedItem = {
      id: "42",
      name: file.name,
      kind: "file",
      size: file.size,
      parentId: "7",
      updatedAt: "2026-09-30T00:00:00Z",
      deletedAt: null,
    } satisfies DriveItem;

    vi.mocked(http.post)
      .mockResolvedValueOnce({
        data: { uploadId: "upload-1", chunkSize: CHUNK_SIZE, totalChunks: 2 },
      })
      .mockResolvedValueOnce({
        data: { uploadId: "upload-1", chunkNumber: 1, received: true },
      })
      .mockResolvedValueOnce({ data: uploadedItem });
    vi.mocked(http.get).mockResolvedValueOnce({
      data: { uploadId: "upload-1", totalChunks: 2, uploadedChunks: [0] },
    });

    const result = await uploadFileInChunks({
      file,
      parentId: "7",
      signal,
      onPhase,
      onProgress,
    });

    expect(result).toEqual(uploadedItem);
    expect(http.post).toHaveBeenCalledTimes(3);
    expect(http.post).toHaveBeenNthCalledWith(
      1,
      "/upload/init",
      expect.objectContaining({
        fileName: "archive.zip",
        fileSize: file.size,
        parentId: "7",
        chunkSize: CHUNK_SIZE,
        totalChunks: 2,
        fileHash: expect.stringMatching(/^[a-f0-9]{32}$/),
      }),
      { signal },
    );

    expect(http.get).toHaveBeenCalledWith("/upload/upload-1/status", {
      signal,
    });
    const chunkForm = vi.mocked(http.post).mock.calls[1][1] as FormData;
    expect(vi.mocked(http.post).mock.calls[1][0]).toBe("/upload/chunk");
    expect(chunkForm.get("chunkNumber")).toBe("1");
    expect(chunkForm.get("totalChunks")).toBe("2");
    expect((chunkForm.get("chunk") as Blob).size).toBe(13);
    expect(vi.mocked(http.post).mock.calls[2][0]).toBe("/upload/merge");
    expect(onPhase).toHaveBeenNthCalledWith(1, "hashing");
    expect(onPhase).toHaveBeenNthCalledWith(2, "uploading");
    expect(onProgress).toHaveBeenLastCalledWith(100);
  });
});
