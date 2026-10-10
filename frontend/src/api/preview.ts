import axios from "axios";
import http from "./http";
import { previewFormat, previewLimit } from "../preview/formats";
import type { DriveItemResponse } from "./drive";

/** Use the existing owner-checked download route; never expose tokens in media URLs. */
export async function fetchPreviewFile(
  file: Pick<DriveItemResponse, "id" | "name" | "size">,
  signal: AbortSignal,
  downloadPath = `/files/${encodeURIComponent(file.id)}/download`,
): Promise<Blob> {
  const format = previewFormat(file.name);
  if (!format) throw new Error("此格式暂不支持在线预览，请下载查看");
  const limit = previewLimit(format.kind);
  const tooLarge = () =>
    new Error(`文件超过 ${limit / 1024 / 1024} MiB 预览上限，请下载查看`);
  if (file.size > limit) throw tooLarge();
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal.addEventListener("abort", abort, { once: true });
  if (signal.aborted) abort();
  let exceeded = false;
  try {
    const { data } = await http.get<Blob>(downloadPath, {
      responseType: "blob",
      signal: controller.signal,
      timeout: 120000,
      onDownloadProgress: ({ loaded }) => {
        if (loaded > limit) {
          exceeded = true;
          controller.abort();
        }
      },
    });
    if (data.size > limit) throw tooLarge();
    return new Blob([data], { type: format.mime });
  } catch (error) {
    if (exceeded) throw tooLarge();
    // Axios keeps JSON error bodies as Blob when responseType is blob.
    // Normalize small API errors so the existing permission/error messages remain usable.
    if (
      axios.isAxiosError(error) &&
      error.response?.data instanceof Blob &&
      error.response.data.size <= 64 * 1024
    ) {
      try {
        error.response.data = JSON.parse(await error.response.data.text());
      } catch {
        /* Non-JSON error page: keep the generic failure message. */
      }
    }
    throw error;
  } finally {
    signal.removeEventListener("abort", abort);
  }
}
