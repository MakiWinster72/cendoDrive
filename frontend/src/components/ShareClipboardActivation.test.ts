// @vitest-environment jsdom
import { Blob as NativeBlob } from "node:buffer";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ShareComposer from "./ShareComposer.vue";
import type { ShareRecord } from "../api/shares";
const { share } = vi.hoisted(() => ({ share: vi.fn() }));
vi.mock("../stores/drive", async (original) => ({
  ...(await original<typeof import("../stores/drive")>()),
  useDrive: () => ({ share }),
}));
const file = {
  id: "42",
  name: "示例.zip",
  kind: "file" as const,
  size: 120,
  parentId: null,
  updatedAt: "2026-01-01",
  deletedAt: null,
};
const record: ShareRecord = {
  id: "51",
  token: "first-click",
  fileId: file.id,
  fileName: file.name,
  kind: "file",
  size: file.size,
  createdAt: "2026-01-01",
  expiresAt: "9999-12-31T23:59:59Z",
  status: "ACTIVE",
};
class PendingClipboardItem {
  constructor(private readonly data: Record<string, Blob | Promise<Blob>>) {}
  getType(type: string) {
    return Promise.resolve(this.data[type]);
  }
}
let wrapper: VueWrapper | undefined,
  active = false,
  copied = "";
let resolveShare: (value: ShareRecord) => void,
  rejectShare: (reason: unknown) => void;
const write = vi.fn(),
  writeText = vi.fn();
beforeEach(() => {
  vi.clearAllMocks();
  copied = "";
  active = false;
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  share.mockImplementation(
    () =>
      new Promise<ShareRecord>((resolve, reject) => {
        resolveShare = resolve;
        rejectShare = reject;
      }),
  );
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  vi.stubGlobal("Blob", NativeBlob);
  vi.stubGlobal("ClipboardItem", PendingClipboardItem);
  write.mockImplementation((items: PendingClipboardItem[]) => {
    if (!active)
      return Promise.reject(
        new DOMException("User gesture expired", "NotAllowedError"),
      );
    return items[0]!.getType("text/plain").then(async (blob) => {
      copied = await blob.text();
    });
  });
  writeText.mockImplementation((text: string) => {
    if (!active)
      return Promise.reject(
        new DOMException("User gesture expired", "NotAllowedError"),
      );
    copied = text;
    return Promise.resolve();
  });
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { write, writeText },
  });
});
afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("first-click clipboard activation", () => {
  it.each(["copy", "wechat", "qq"])(
    "starts %s clipboard writing inside the click, then supplies the delayed real link",
    async (action) => {
      wrapper = mount(ShareComposer, {
        props: { file },
        attachTo: document.body,
      });
      await flushPromises();
      expect(share).not.toHaveBeenCalled();
      active = true;
      (
        wrapper.get(`[data-action="${action}"]`).element as HTMLButtonElement
      ).click();
      active = false; // The HTTP response arrives after the trusted-click activation has gone away.
      await flushPromises();
      expect(write).toHaveBeenCalledTimes(1);
      expect(copied).toBe("");
      expect(wrapper.find(".share-toast").exists()).toBe(false);
      resolveShare({
        ...record,
        extractionCode: share.mock.calls[0]![2] as string,
      });
      await flushPromises();
      expect(wrapper.find('[role="alert"]').exists()).toBe(false);
      expect(wrapper.get(".share-toast").text()).toContain("链接已复制");
      expect(copied).toMatch(/\/share\/first-click#code=[a-z0-9]{4}\n提取码：/);
      expect(write).toHaveBeenCalledTimes(1);
      expect(writeText).not.toHaveBeenCalled();
      expect(share).toHaveBeenCalledTimes(1);
      active = true;
      (
        wrapper.get(`[data-action="${action}"]`).element as HTMLButtonElement
      ).click();
      active = false;
      await flushPromises();
      expect(write).toHaveBeenCalledTimes(2);
      expect(share).toHaveBeenCalledTimes(1);
    },
  );
  it("does not report copy success or launch an app when link creation fails", async () => {
    wrapper = mount(ShareComposer, {
      props: { file },
      attachTo: document.body,
    });
    await flushPromises();
    active = true;
    (
      wrapper.get('[data-action="wechat"]').element as HTMLButtonElement
    ).click();
    active = false;
    rejectShare(new Error("offline"));
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toBe("offline");
    expect(wrapper.find(".share-toast").exists()).toBe(false);
    expect(copied).toBe("");
    expect(HTMLAnchorElement.prototype.click).not.toHaveBeenCalled();
    expect(
      wrapper.get('[data-action="copy"]').attributes("disabled"),
    ).toBeUndefined();
  });
  it("shows a real permission error and allows a fresh-click retry with the cached link", async () => {
    write.mockRejectedValueOnce(new DOMException("denied", "NotAllowedError"));
    wrapper = mount(ShareComposer, {
      props: { file },
      attachTo: document.body,
    });
    await flushPromises();
    active = true;
    (wrapper.get('[data-action="copy"]').element as HTMLButtonElement).click();
    active = false;
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toBe("操作失败，请重试");
    expect(wrapper.find(".share-toast").exists()).toBe(false);
    resolveShare(record);
    await flushPromises();
    active = true;
    (wrapper.get('[data-action="copy"]').element as HTMLButtonElement).click();
    active = false;
    await flushPromises();
    expect(copied).toBe(`${window.location.origin}/share/first-click`);
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
    expect(share).toHaveBeenCalledTimes(1);
  });
  it("does not leave an unhandled payload rejection after the browser denies the write", async () => {
    write.mockRejectedValueOnce(new DOMException("denied", "NotAllowedError"));
    wrapper = mount(ShareComposer, {
      props: { file },
      attachTo: document.body,
    });
    await flushPromises();
    active = true;
    (wrapper.get('[data-action="copy"]').element as HTMLButtonElement).click();
    active = false;
    await flushPromises();
    rejectShare(new Error("offline"));
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toBe("操作失败，请重试");
    expect(copied).toBe("");
  });
  it("does not write a late link after the composer is unmounted", async () => {
    wrapper = mount(ShareComposer, {
      props: { file },
      attachTo: document.body,
    });
    await flushPromises();
    active = true;
    (wrapper.get('[data-action="copy"]').element as HTMLButtonElement).click();
    active = false;
    wrapper.unmount();
    wrapper = undefined;
    resolveShare(record);
    await flushPromises();
    expect(copied).toBe("");
    expect(document.body.style.overflow).toBe("");
  });
});
