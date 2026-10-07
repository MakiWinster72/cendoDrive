export type PreviewKind = "docx" | "pdf" | "image" | "video" | "text" | "markdown";
const formats: Record<string, { kind: PreviewKind; mime: string }> = {
  docx: { kind: "docx", mime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document" },
  pdf: { kind: "pdf", mime: "application/pdf" },
  jpg: { kind: "image", mime: "image/jpeg" },
  jpeg: { kind: "image", mime: "image/jpeg" },
  png: { kind: "image", mime: "image/png" },
  gif: { kind: "image", mime: "image/gif" },
  webp: { kind: "image", mime: "image/webp" },
  bmp: { kind: "image", mime: "image/bmp" },
  avif: { kind: "image", mime: "image/avif" },
  svg: { kind: "image", mime: "image/svg+xml" },
  mp4: { kind: "video", mime: "video/mp4" },
  m4v: { kind: "video", mime: "video/mp4" },
  mov: { kind: "video", mime: "video/quicktime" },
  webm: { kind: "video", mime: "video/webm" },
  ogv: { kind: "video", mime: "video/ogg" },
  txt: { kind: "text", mime: "text/plain" },
  md: { kind: "markdown", mime: "text/plain" },
  markdown: { kind: "markdown", mime: "text/plain" },
};
export function previewFormat(name: string) {
  return formats[name.split(".").pop()?.toLowerCase() || ""] ?? null;
}
export function previewLimit(kind: PreviewKind) {
  return (kind === "text" || kind === "markdown" ? 5 : kind === "docx" ? 20 : 100) * 1024 * 1024;
}
export function decodeText(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  if (bytes[0] === 0xff && bytes[1] === 0xfe) return new TextDecoder("utf-16le").decode(bytes.subarray(2));
  if (bytes[0] === 0xfe && bytes[1] === 0xff) return new TextDecoder("utf-16be").decode(bytes.subarray(2));
  try { return new TextDecoder("utf-8", { fatal: true }).decode(bytes); }
  catch { return new TextDecoder("gb18030").decode(bytes); }
}
