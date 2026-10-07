import { describe, expect, it } from "vitest";
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
  Presentation,
} from "lucide-vue-next";
import { fileCategory, iconForFile } from "./fileIcon";

const icon = (name: string, kind: "folder" | "file" = "file") =>
  iconForFile({ name, kind });

describe("iconForFile", () => {
  it.each([
    ["report.docx", FileType2],
    ["slides.PPTX", Presentation],
    ["budget.xlsx", FileSpreadsheet],
    ["notes.txt", FileText],
    ["README.md", FileCode2],
    ["photo.PNG", FileImage],
    ["clip.mp4", FileVideo],
    ["archive.tar.gz", FileArchive],
  ])("maps %s to the matching Lucide icon", (name, expected) => {
    expect(icon(name)).toBe(expected);
  });

  it("uses the folder kind before extension and falls back for unknown files", () => {
    expect(icon("photo.png", "folder")).toBe(Folder);
    expect(icon("untitled")).toBe(File);
    expect(icon("unknown.bin")).toBe(File);
  });
});

describe("fileCategory", () => {
  it.each([
    ["photo.PNG", "image"],
    ["clip.mp4", "video"],
    ["song.MP3", "audio"],
    ["report.pdf", "doc"],
    ["budget.xlsx", "doc"],
    ["archive.zip", "other"],
  ] as const)("classifies backend file %s as %s", (name, category) => {
    expect(fileCategory({ name, kind: "file" })).toBe(category);
  });

  it("does not include folders in file categories", () => {
    expect(fileCategory({ name: "photo.png", kind: "folder" })).toBeNull();
  });
});
