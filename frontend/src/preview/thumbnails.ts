import { watch } from "vue";
import type { DriveItemResponse } from "../api/drive";
import { fetchPreviewFile } from "../api/preview";
import { getToken } from "../stores/auth";
import { decodeText, previewFormat, previewLimit } from "./formats";
import { thumbnailSlot } from "./thumbnailQueue";

export interface Thumbnail {
  url?: string;
  html?: string;
  bytes: number;
}
interface Entry {
  controller: AbortController;
  promise: Promise<Thumbnail | null>;
  result?: Thumbnail | null;
  failedUntil?: number;
}
const entries = new Map<string, Entry>();
const MAX_ENTRIES = 128;
const MAX_BYTES = 128 * 1024 * 1024;
let generation = 0;
export function thumbnailKey(file: DriveItemResponse): string {
  return JSON.stringify([
    file.id,
    file.name,
    file.size,
    file.updatedAt,
    file.kind,
    file.deletedAt,
  ]);
}
function supported(file: DriveItemResponse) {
  const format = previewFormat(file.name);
  return file.kind === "file" &&
    !file.deletedAt &&
    format &&
    ["image", "pdf", "docx", "markdown"].includes(format.kind) &&
    file.size <= previewLimit(format.kind)
    ? format
    : null;
}
function dispose(entry: Entry) {
  entry.controller.abort();
  if (entry.result?.url) URL.revokeObjectURL(entry.result.url);
}
export function clearThumbnailCache() {
  generation++;
  entries.forEach(dispose);
  entries.clear();
}
// Private file content never survives a login/logout or account change.
watch(getToken, clearThumbnailCache, { flush: "sync" });
function touch(key: string, entry: Entry) {
  entries.delete(key);
  entries.set(key, entry);
}
function trim(latestKey: string) {
  let bytes = [...entries.values()].reduce(
    (sum, entry) => sum + (entry.result?.bytes ?? 0),
    0,
  );
  for (const [key, entry] of entries) {
    if (entries.size <= MAX_ENTRIES && bytes <= MAX_BYTES) break;
    // A view switch must not interrupt an active transfer. Evict completed entries first.
    if (entry.result === undefined || key === latestKey) continue;
    bytes -= entry.result?.bytes ?? 0;
    entries.delete(key);
    dispose(entry);
  }
}
export function cachedThumbnail(file: DriveItemResponse): Thumbnail | null {
  if (!supported(file)) return null;
  const key = thumbnailKey(file);
  const entry = entries.get(key);
  if (!entry?.result) return null;
  touch(key, entry);
  return entry.result;
}
export function loadThumbnail(
  file: DriveItemResponse,
): Promise<Thumbnail | null> {
  if (!supported(file)) return Promise.resolve(null);
  const key = thumbnailKey(file);
  const existing = entries.get(key);
  if (
    existing &&
    (!existing.failedUntil || existing.failedUntil > Date.now())
  ) {
    touch(key, existing);
    return existing.promise;
  }
  const controller = new AbortController();
  const run = generation;
  const entry: Entry = { controller, promise: Promise.resolve(null) };
  entry.promise = generate({ ...file }, controller.signal).then((result) => {
    if (run !== generation || controller.signal.aborted) {
      if (result?.url) URL.revokeObjectURL(result.url);
      return null;
    }
    entry.result = result;
    if (!result) entry.failedUntil = Date.now() + 30_000;
    trim(key);
    return result;
  });
  entries.set(key, entry);
  return entry.promise;
}
export function forgetThumbnail(file: DriveItemResponse) {
  const key = thumbnailKey(file);
  const entry = entries.get(key);
  if (entry) {
    entries.delete(key);
    dispose(entry);
  }
}
async function generate(
  file: DriveItemResponse,
  signal: AbortSignal,
): Promise<Thumbnail | null> {
  const release = await thumbnailSlot();
  let destroy: (() => Promise<void>) | undefined;
  try {
    if (signal.aborted) return null;
    const blob = await fetchPreviewFile(file, signal);
    if (signal.aborted) return null;
    const format = previewFormat(file.name)!;
    if (format.kind === "image")
      return { url: URL.createObjectURL(blob), bytes: blob.size };
    if (format.kind === "pdf") {
      const { getDocument, GlobalWorkerOptions } = await import("pdfjs-dist");
      const { default: workerUrl } =
        await import("pdfjs-dist/build/pdf.worker.min.mjs?url");
      const data = await blob.arrayBuffer();
      if (signal.aborted) return null;
      GlobalWorkerOptions.workerSrc = workerUrl;
      const task = getDocument({
        data,
        maxImageSize: 16_000_000,
        enableXfa: false,
        cMapUrl: `${import.meta.env.BASE_URL}pdfjs/cmaps/`,
        cMapPacked: true,
        standardFontDataUrl: `${import.meta.env.BASE_URL}pdfjs/standard_fonts/`,
        wasmUrl: `${import.meta.env.BASE_URL}pdfjs/wasm/`,
      });
      destroy = () => task.destroy();
      const abort = () => {
        void task.destroy().catch(() => {});
      };
      signal.addEventListener("abort", abort, { once: true });
      task.onPassword = abort;
      try {
        const pdf = await task.promise;
        const page = await pdf.getPage(1);
        if (signal.aborted) return null;
        const natural = page.getViewport({ scale: 1 });
        const viewport = page.getViewport({
          scale: 240 / Math.max(natural.width, natural.height),
        });
        const canvas = document.createElement("canvas");
        canvas.width = Math.ceil(viewport.width);
        canvas.height = Math.ceil(viewport.height);
        await page.render({
          canvas,
          canvasContext: canvas.getContext("2d")!,
          viewport,
        }).promise;
        const rendered = await new Promise<Blob | null>((resolve) =>
          canvas.toBlob(resolve, "image/png"),
        );
        if (!rendered || signal.aborted) return null;
        return { url: URL.createObjectURL(rendered), bytes: rendered.size };
      } finally {
        signal.removeEventListener("abort", abort);
      }
    }
    const { renderDocx, renderMarkdown } = await import("./render");
    const buffer = await blob.arrayBuffer();
    if (signal.aborted) return null;
    const html =
      format.kind === "docx"
        ? await renderDocx(buffer)
        : await renderMarkdown(decodeText(buffer).slice(0, 32_000));
    return html.trim() ? { html, bytes: html.length * 2 } : null;
  } catch {
    return null;
  } finally {
    try {
      await destroy?.().catch(() => {});
    } finally {
      release();
    }
  }
}
