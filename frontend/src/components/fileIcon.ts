import {
  File,
  FileArchive,
  FileCode2,
  FileImage,
  FileSpreadsheet,
  FileText,
  FileType2,
  FileVideo,
  Folder,
  Music2,
  Presentation,
} from "@lucide/vue";
import type { DriveItem } from "../stores/drive";

export type FileCategory = "image" | "video" | "audio" | "doc" | "other";

/** kind 是后端的 file/folder；页面分类由文件名扩展名推断。 */
export function fileCategory(
  item: Pick<DriveItem, "name" | "kind">,
): FileCategory | null {
  if (item.kind === "folder") return null;
  const extension = item.name.match(/\.([^.]+)$/)?.[1]?.toLowerCase();
  if (!extension) return "other";
  if (
    [
      "jpg",
      "jpeg",
      "png",
      "gif",
      "webp",
      "bmp",
      "svg",
      "heic",
      "avif",
    ].includes(extension)
  )
    return "image";
  if (["mp4", "mov", "avi", "mkv", "webm", "m4v"].includes(extension))
    return "video";
  if (["mp3", "wav", "flac", "aac", "ogg", "m4a", "wma"].includes(extension))
    return "audio";
  if (
    [
      "pdf",
      "doc",
      "docx",
      "odt",
      "rtf",
      "ppt",
      "pptx",
      "odp",
      "xls",
      "xlsx",
      "csv",
      "ods",
      "txt",
      "log",
      "md",
      "markdown",
      "mdx",
    ].includes(extension)
  )
    return "doc";
  return "other";
}

/** 后端目前只返回 folder/file；优先依据文件名扩展名选择文件图标。 */
export function iconForFile(item: Pick<DriveItem, "name" | "kind">) {
  if (item.kind === "folder") return Folder;

  const extension = item.name.match(/\.([^.]+)$/)?.[1]?.toLowerCase();
  if (extension && ["doc", "docx", "odt", "rtf"].includes(extension))
    return FileType2;
  if (extension && ["ppt", "pptx", "odp"].includes(extension))
    return Presentation;
  if (extension && ["xls", "xlsx", "csv", "ods"].includes(extension))
    return FileSpreadsheet;
  if (extension && ["txt", "log"].includes(extension)) return FileText;
  if (extension && ["md", "markdown", "mdx"].includes(extension))
    return FileCode2;
  if (
    extension &&
    [
      "jpg",
      "jpeg",
      "png",
      "gif",
      "webp",
      "bmp",
      "svg",
      "heic",
      "avif",
    ].includes(extension)
  )
    return FileImage;
  if (
    extension &&
    ["mp4", "mov", "avi", "mkv", "webm", "m4v"].includes(extension)
  )
    return FileVideo;
  if (
    extension &&
    ["zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz"].includes(extension)
  )
    return FileArchive;

  if (fileCategory(item) === "audio") return Music2;
  if (fileCategory(item) === "doc") return FileText;
  return File;
}
