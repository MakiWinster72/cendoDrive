// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ShareComposer from "./ShareComposer.vue";
import {
  buildSharePoster,
  copyShareText,
  downloadSharePoster,
  launchShareApp,
} from "./sharePoster";
const { share } = vi.hoisted(() => ({ share: vi.fn() }));
vi.mock("../stores/drive", async (original) => ({
  ...(await original<typeof import("../stores/drive")>()),
  useDrive: () => ({ share }),
}));
vi.mock("./sharePoster", () => ({
  buildSharePoster: vi.fn(),
  copyShareText: vi.fn(),
  downloadSharePoster: vi.fn(),
  launchShareApp: vi.fn(),
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
const record = {
  id: "51",
  token: "opaque",
  fileId: "42",
  fileName: file.name,
  kind: "file" as const,
  size: 120,
  createdAt: "2026-01-01",
  expiresAt: "9999-12-31T23:59:59Z",
  status: "ACTIVE" as const,
};
let wrapper: VueWrapper;
const open = async () => {
  wrapper = mount(ShareComposer, { props: { file }, attachTo: document.body });
  await flushPromises();
  return wrapper;
};
beforeEach(() => {
  vi.clearAllMocks();
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  share.mockImplementation(
    async (_id: string, _seconds: number, code?: string) => ({
      ...record,
      extractionCode: code,
      hasExtractionCode: !!code,
    }),
  );
  vi.mocked(copyShareText).mockImplementation(async (text) => {
    await text;
  });
  vi.mocked(buildSharePoster).mockResolvedValue(
    new Blob(["qr"], { type: "image/png" }),
  );
  vi.stubGlobal(
    "URL",
    class extends URL {
      static createObjectURL = vi.fn(() => "blob:poster");
      static revokeObjectURL = vi.fn();
    },
  );
  Object.defineProperty(navigator, "canShare", {
    configurable: true,
    value: undefined,
  });
  Object.defineProperty(navigator, "share", {
    configurable: true,
    value: undefined,
  });
});
afterEach(() => {
  wrapper?.unmount();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
const setting = (text: string) =>
  wrapper
    .findAll("button.share-setting")
    .find((button) => button.text().includes(text))!;
describe("share composer", () => {
  it("lazily creates a permanent share, reuses it, and shows only a small copied toast", async () => {
    await open();
    expect(share).not.toHaveBeenCalled();
    expect(document.body.style.overflow).toBe("hidden");
    expect(wrapper.get("#share-composer-title").text()).toBe("分享");
    await wrapper.get('[data-action="copy"]').trigger("click");
    await flushPromises();
    expect(share).toHaveBeenCalledWith(
      "42",
      0,
      expect.stringMatching(/^[a-z0-9]{4}$/),
    );
    await expect(vi.mocked(copyShareText).mock.calls[0]![0]).resolves.toMatch(
      /\/share\/opaque#code=[a-z0-9]{4}\n提取码：/,
    );
    expect(wrapper.get(".share-toast").text()).toBe("链接已复制");
    await wrapper.get('[data-action="copy"]').trigger("click");
    await flushPromises();
    expect(share).toHaveBeenCalledTimes(1);
    wrapper.unmount();
    expect(document.body.style.overflow).toBe("");
  });
  it("creates a new share when validity/code settings change, and can omit extraction codes", async () => {
    await open();
    await wrapper.get('[data-action="copy"]').trigger("click");
    await flushPromises();
    await setting("有效期").trigger("click");
    await wrapper.findAll(".expiry-option")[2]!.trigger("click");
    await wrapper.get('[aria-label="使用提取码"]').setValue(false);
    await wrapper.get('[data-action="copy"]').trigger("click");
    await flushPromises();
    expect(share).toHaveBeenLastCalledWith("42", 604800, undefined);
    expect(share).toHaveBeenCalledTimes(2);
    await expect(vi.mocked(copyShareText).mock.lastCall![0]).resolves.toBe(
      `${window.location.origin}/share/opaque`,
    );
  });
  it("validates edited codes and can disable auto-fill without changing the server share", async () => {
    await open();
    await setting("随机生成提取码").trigger("click");
    await wrapper.get("#share-extraction-code").setValue("!");
    await wrapper.get(".code-form").trigger("submit");
    expect(wrapper.get('[role="alert"]').text()).toContain("4–16");
    expect(share).not.toHaveBeenCalled();
    await wrapper.get("#share-extraction-code").setValue("Ab12");
    await wrapper.get(".code-form").trigger("submit");
    await wrapper.get('[data-action="copy"]').trigger("click");
    await flushPromises();
    await wrapper.get('[aria-label="分享链接自动填充提取码"]').setValue(false);
    await wrapper.get('[data-action="copy"]').trigger("click");
    await flushPromises();
    expect(share).toHaveBeenCalledTimes(1);
    await expect(vi.mocked(copyShareText).mock.lastCall![0]).resolves.toBe(
      `${window.location.origin}/share/opaque\n提取码：Ab12`,
    );
  });
  it("locks duplicate actions and closing while creating, then permits retries after failure", async () => {
    let reject!: (reason: unknown) => void;
    share.mockImplementationOnce(
      () =>
        new Promise((_resolve, r) => {
          reject = r;
        }),
    );
    await open();
    await wrapper.get('[data-action="copy"]').trigger("click");
    expect(wrapper.get(".share-close").attributes("disabled")).toBeDefined();
    await wrapper.get('[data-action="qr"]').trigger("click");
    expect(share).toHaveBeenCalledTimes(1);
    reject(new Error("offline"));
    await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    expect(wrapper.find(".share-toast").exists()).toBe(false);
    await wrapper.get('[data-action="copy"]').trigger("click");
    await flushPromises();
    expect(share).toHaveBeenCalledTimes(2);
  });
  it("does not claim copy success or launch an app when clipboard writing fails", async () => {
    vi.mocked(copyShareText).mockRejectedValue(new Error("denied"));
    await open();
    await wrapper.get('[data-action="wechat"]').trigger("click");
    await flushPromises();
    expect(launchShareApp).not.toHaveBeenCalled();
    expect(wrapper.find(".share-toast").exists()).toBe(false);
    expect(wrapper.find('[role="alert"]').exists()).toBe(true);
  });
  it.each(["wechat", "qq"] as const)(
    "copies a real link then attempts %s app launch",
    async (app) => {
      await open();
      await wrapper.get(`[data-action="${app}"]`).trigger("click");
      await flushPromises();
      expect(copyShareText).toHaveBeenCalled();
      expect(launchShareApp).toHaveBeenCalledWith(app);
      expect(wrapper.get(".share-toast").text()).toContain("粘贴发送");
    },
  );
  it("uses a generated poster, offers download guidance, and returns to the share card", async () => {
    await open();
    await wrapper.get('[data-action="qr"]').trigger("click");
    await flushPromises();
    expect(buildSharePoster).toHaveBeenCalledWith(
      expect.objectContaining({ token: "opaque" }),
      window.location.origin,
      true,
      expect.stringContaining("<svg"),
    );
    expect(wrapper.get(".share-qr-poster").attributes("src")).toBe(
      "blob:poster",
    );
    await wrapper.get('[aria-label="保存二维码到相册"]').trigger("click");
    expect(downloadSharePoster).toHaveBeenCalled();
    expect(wrapper.get(".share-toast").text()).toContain("长按");
    await wrapper.get('[aria-label="朋友圈分享二维码"]').trigger("click");
    await flushPromises();
    expect(wrapper.get(".share-toast").text()).toContain("选择图片发布");
    await wrapper.get('[aria-label="返回分享"]').trigger("click");
    expect(wrapper.find('[data-action="copy"]').exists()).toBe(true);
  });
  it("uses native file sharing from a QR button and treats cancellation as non-error", async () => {
    const nativeShare = vi
      .fn()
      .mockRejectedValue(new DOMException("cancelled", "AbortError"));
    Object.defineProperty(navigator, "canShare", {
      configurable: true,
      value: () => true,
    });
    Object.defineProperty(navigator, "share", {
      configurable: true,
      value: nativeShare,
    });
    await open();
    await wrapper.get('[data-action="qr"]').trigger("click");
    await flushPromises();
    await wrapper.get('[aria-label="保存二维码到相册"]').trigger("click");
    await flushPromises();
    expect(nativeShare).toHaveBeenCalledWith(
      expect.objectContaining({ files: [expect.any(File)] }),
    );
    expect(downloadSharePoster).not.toHaveBeenCalled();
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  });
  it("leaves deliberate placeholders inert and opens the friend picker without creating a public link", async () => {
    await open();
    for (const action of ["weibo", "secret"])
      await wrapper.get(`[data-action="${action}"]`).trigger("click");
    await setting("群名片").trigger("click");
    await setting("分享皮肤").trigger("click");
    expect(share).not.toHaveBeenCalled();
    expect(wrapper.get(".share-toast").text()).toBe("此功能暂未开放");
  });
});
