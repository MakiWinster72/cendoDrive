// @vitest-environment jsdom
import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import {
  FileArchive,
  FileCode2,
  FileImage,
  FileSpreadsheet,
  FileText,
  FileType2,
  FileVideo,
  Folder,
  Presentation,
  File,
} from "@lucide/vue";
import ShareList from "./ShareList.vue";
import MobileMyShares from "./MobileMyShares.vue";
import type { ShareRecord } from "../api/shares";

const cases = [
  ["slides.ppt", Presentation],
  ["slides.pptx", Presentation],
  ["notes.txt", FileText],
  ["report.doc", FileType2],
  ["report.docx", FileType2],
  ["budget.xls", FileSpreadsheet],
  ["budget.xlsx", FileSpreadsheet],
  ["README.md", FileCode2],
  ["photo.png", FileImage],
  ["clip.mp4", FileVideo],
  ["unknown.bin", File],
  ["archive.zip", FileArchive],
] as const;

for (const [label, component] of [
  ["desktop", ShareList],
  ["mobile", MobileMyShares],
] as const) {
  describe(`${label} share file icons`, () => {
    it.each(cases)("renders the type icon for %s", (fileName, expected) => {
      const share: ShareRecord = {
        id: "s1",
        token: "token",
        fileId: "f1",
        fileName,
        kind: "file",
        size: 12,
        createdAt: "2026-01-01T00:00:00Z",
        expiresAt: "2099-01-01T00:00:00Z",
        status: "ACTIVE",
      };
      const wrapper = mount(component, {
        props: { shares: [share], loading: false, error: "" },
      });
      expect(wrapper.findComponent(expected).exists()).toBe(true);
      expect(wrapper.text()).toContain(fileName);
      wrapper.unmount();
    });
    it("preserves the folder icon even with a file extension", () => {
      const share: ShareRecord = {
        id: "s1",
        token: "token",
        fileId: "f1",
        fileName: "folder.zip",
        kind: "folder",
        size: 0,
        createdAt: "2026-01-01T00:00:00Z",
        expiresAt: "2099-01-01T00:00:00Z",
        status: "ACTIVE",
      };
      const wrapper = mount(component, {
        props: { shares: [share], loading: false, error: "" },
      });
      expect(wrapper.findComponent(Folder).exists()).toBe(true);
      wrapper.unmount();
    });
  });
}
