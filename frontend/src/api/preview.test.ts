import { beforeEach, describe, expect, it, vi } from "vitest";
import http from "./http";
import { fetchPreviewFile } from "./preview";
import { previewLimit } from "../preview/formats";
vi.mock("./http", () => ({ default: { get: vi.fn() } }));
const file = { id: "42", name: "README.md", size: 10 };
beforeEach(() => vi.resetAllMocks());
describe("authenticated preview loader", () => {
  it("uses owner-checked endpoint, blob response and cancellable request", async () => {
    vi.mocked(http.get).mockResolvedValue({ data: new Blob(["# hello"]) });
    const result = await fetchPreviewFile(file, new AbortController().signal);
    expect(http.get).toHaveBeenCalledWith(
      "/files/42/download",
      expect.objectContaining({ responseType: "blob", timeout: 120000 }),
    );
    expect(result.type).toBe("text/plain");
    expect(await result.text()).toBe("# hello");
  });
  it("rejects unsupported or oversized files without a request", async () => {
    await expect(
      fetchPreviewFile(
        { ...file, name: "a.zip" },
        new AbortController().signal,
      ),
    ).rejects.toThrow("不支持");
    await expect(
      fetchPreviewFile(
        { ...file, size: previewLimit("markdown") + 1 },
        new AbortController().signal,
      ),
    ).rejects.toThrow("5 MiB");
    expect(http.get).not.toHaveBeenCalled();
  });
  it("checks actual bytes when metadata lies", async () => {
    vi.mocked(http.get).mockResolvedValue({
      data: new Blob([new Uint8Array(previewLimit("markdown") + 1)]),
    });
    await expect(
      fetchPreviewFile(file, new AbortController().signal),
    ).rejects.toThrow("5 MiB");
  });
  it("aborts transfers above the limit", async () => {
    vi.mocked(http.get).mockImplementation(async (_url, config) => {
      config!.onDownloadProgress!({
        loaded: previewLimit("markdown") + 1,
        bytes: 1,
        lengthComputable: false,
      });
      expect(config!.signal!.aborted).toBe(true);
      throw new Error("cancelled");
    });
    await expect(
      fetchPreviewFile(file, new AbortController().signal),
    ).rejects.toThrow("5 MiB");
  });
  it("propagates cancellation on close", async () => {
    const owner = new AbortController();
    vi.mocked(http.get).mockImplementation(async (_url, config) => {
      owner.abort();
      expect(config!.signal!.aborted).toBe(true);
      throw new Error("cancelled");
    });
    await expect(fetchPreviewFile(file, owner.signal)).rejects.toThrow(
      "cancelled",
    );
  });
  it("normalizes JSON Blob errors for permission and missing-file messages", async () => {
    const error = Object.assign(new Error("404"), {
      isAxiosError: true,
      response: {
        status: 404,
        data: new Blob([
          '{"code":"FILE_NOT_FOUND","message":"not owned"}',
        ]) as unknown,
      },
    });
    vi.mocked(http.get).mockRejectedValue(error);
    await expect(
      fetchPreviewFile(file, new AbortController().signal),
    ).rejects.toBe(error);
    expect(error.response.data).toEqual({
      code: "FILE_NOT_FOUND",
      message: "not owned",
    });
  });
  it("retains authorization and network failures", async () => {
    const error = new Error("unauthorized");
    vi.mocked(http.get).mockRejectedValue(error);
    await expect(
      fetchPreviewFile(file, new AbortController().signal),
    ).rejects.toBe(error);
  });
});
