// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ShareSettingsDialog from "./ShareSettingsDialog.vue";
import ShareLinkDialog from "./ShareLinkDialog.vue";

const { share } = vi.hoisted(() => ({ share: vi.fn() }));
vi.mock("../stores/drive", () => ({ useDrive: () => ({ share }) }));
const file = { id: "42", name: "项目资料.md" };
const record = {
  id: "51",
  token: "abc",
  fileId: "42",
  fileName: file.name,
  kind: "file" as const,
  size: 120,
  createdAt: "2026-10-08T00:00:00Z",
  expiresAt: "2030-01-01T00:00:00Z",
  status: "ACTIVE" as const,
};
let wrapper: VueWrapper | undefined;
function open() {
  wrapper = mount(ShareSettingsDialog, {
    props: { file },
    attachTo: document.body,
  });
  return wrapper;
}
beforeEach(() => {
  vi.clearAllMocks();
  document.body.style.overflow = "auto";
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  share.mockResolvedValue(record);
});
afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  vi.unstubAllGlobals();
});

describe("share settings", () => {
  it("creates without a code only after confirming the selected duration", async () => {
    const page = open();
    expect(share).not.toHaveBeenCalled();
    expect(page.text()).toContain("获得链接的任何人均可访问");
    await page.get("#share-duration").setValue("30");
    await page.get("form").trigger("submit");
    await flushPromises();
    expect(share).toHaveBeenCalledWith("42", 2592000, undefined);
    expect(page.emitted("created")?.[0]).toEqual([record]);
  });
  it("supports custom days and an explicitly chosen case-sensitive code", async () => {
    const page = open();
    await page.get("#share-duration").setValue("custom");
    await page.get("#share-custom-days").setValue("3");
    await page.get("[type=checkbox]").setValue(true);
    expect(
      (page.get("#share-extraction-code").element as HTMLInputElement).value,
    ).toMatch(/^[A-Z2-9]{6}$/);
    await page.get("#share-extraction-code").setValue("Ab12");
    await page.get("form").trigger("submit");
    await flushPromises();
    expect(share).toHaveBeenCalledWith("42", 259200, "Ab12");
  });
  it("does not send an invalid lifetime or extraction code", async () => {
    const page = open();
    await page.get("#share-duration").setValue("custom");
    await page.get("#share-custom-days").setValue("31");
    await page.get("form").trigger("submit");
    expect(page.get("[role=alert]").text()).toContain("1–30");
    expect(share).not.toHaveBeenCalled();
    await page.get("#share-custom-days").setValue("2");
    await page.get("[type=checkbox]").setValue(true);
    await page.get("#share-extraction-code").setValue("bad code");
    await page.get("form").trigger("submit");
    expect(page.get("[role=alert]").text()).toContain("4–16");
    expect(share).not.toHaveBeenCalled();
  });
  it("retains choices for retry and restores scrolling when closed", async () => {
    share.mockRejectedValueOnce(new Error("存储暂不可用"));
    const page = open();
    await page.get("[type=checkbox]").setValue(true);
    await page.get("#share-extraction-code").setValue("Ab12");
    await page.get("form").trigger("submit");
    await flushPromises();
    expect(page.get("[role=alert]").text()).toContain("存储暂不可用");
    expect(
      (page.get("#share-extraction-code").element as HTMLInputElement).value,
    ).toBe("Ab12");
    await page.get("form").trigger("submit");
    await flushPromises();
    expect(share).toHaveBeenCalledTimes(2);
    await page.get("dialog").trigger("cancel");
    expect(page.emitted("close")).toHaveLength(1);
    page.unmount();
    wrapper = undefined;
    expect(document.body.style.overflow).toBe("auto");
  });
  it("blocks duplicate submissions and Escape while pending, and ignores a late response", async () => {
    let resolve!: (value: typeof record) => void;
    share.mockImplementation(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    const page = open();
    await page.get("form").trigger("submit");
    await page.get("form").trigger("submit");
    await page.get("dialog").trigger("cancel");
    expect(share).toHaveBeenCalledTimes(1);
    expect(page.emitted("close")).toBeUndefined();
    page.unmount();
    wrapper = undefined;
    resolve(record);
    await flushPromises();
    expect(page.emitted("created")).toBeUndefined();
    expect(document.body.style.overflow).toBe("auto");
  });
  it("copies the link and current code without putting the code in the URL", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    wrapper = mount(ShareLinkDialog, {
      props: {
        share: { ...record, hasExtractionCode: true, extractionCode: "Ab12" },
      },
      global: { stubs: { teleport: true } },
    });
    await wrapper.findAll("button")[1].trigger("click");
    await flushPromises();
    expect(writeText).toHaveBeenCalledWith(
      `${window.location.origin}/share/abc\n提取码：Ab12`,
    );
    expect(
      (wrapper.get("#share-link-value").element as HTMLInputElement).value,
    ).not.toContain("Ab12");
  });
});
