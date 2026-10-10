// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  copyShareText,
  downloadSharePoster,
  launchShareApp,
} from "./sharePoster";
afterEach(() => {
  document.body.innerHTML = "";
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
function clipboard(value: unknown) {
  Object.defineProperty(navigator, "clipboard", { configurable: true, value });
}
describe("share browser adapters", () => {
  it("writes the full text to the system clipboard", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    clipboard({ writeText });
    await copyShareText("https://drive.example/share/token\n提取码：Ab12");
    expect(writeText).toHaveBeenCalledWith(
      "https://drive.example/share/token\n提取码：Ab12",
    );
  });
  it("waits for the real text when only writeText is available", async () => {
    vi.stubGlobal("ClipboardItem", undefined);
    const writeText = vi.fn().mockResolvedValue(undefined);
    clipboard({ writeText });
    let resolve!: (text: string) => void;
    const copying = copyShareText(
      new Promise<string>((done) => {
        resolve = done;
      }),
    );
    expect(writeText).not.toHaveBeenCalled();
    resolve("生成后的真实链接");
    await copying;
    expect(writeText).toHaveBeenCalledWith("生成后的真实链接");
  });
  it("does not write a placeholder when asynchronous link creation fails", async () => {
    const writeText = vi.fn();
    clipboard({ writeText });
    await expect(
      copyShareText(Promise.reject(new Error("offline"))),
    ).rejects.toThrow("offline");
    expect(writeText).not.toHaveBeenCalled();
    expect(document.querySelector("textarea")).toBeNull();
  });
  it("falls back inside the open modal and restores the focused control", async () => {
    clipboard(undefined);
    const modal = document.createElement("dialog"),
      button = document.createElement("button");
    modal.setAttribute("open", "");
    modal.appendChild(button);
    document.body.appendChild(modal);
    button.focus();
    Object.defineProperty(document, "execCommand", {
      configurable: true,
      value: vi.fn(() => {
        const input = document.querySelector("textarea")!;
        expect(input.parentElement).toBe(modal);
        expect(input.value).toBe("真实链接");
        return true;
      }),
    });
    await copyShareText("真实链接");
    expect(document.querySelector("textarea")).toBeNull();
    expect(document.activeElement).toBe(button);
  });
  it("cleans up and rejects when legacy copying fails", async () => {
    clipboard(undefined);
    Object.defineProperty(document, "execCommand", {
      configurable: true,
      value: vi.fn(() => false),
    });
    await expect(copyShareText("link")).rejects.toThrow("无法复制");
    expect(document.querySelector("textarea")).toBeNull();
  });
  it("does not hide a denied clipboard permission behind a fake success", async () => {
    clipboard({
      writeText: vi
        .fn()
        .mockRejectedValue(new DOMException("denied", "NotAllowedError")),
    });
    await expect(copyShareText("link")).rejects.toMatchObject({
      name: "NotAllowedError",
    });
    expect(document.querySelector("textarea")).toBeNull();
  });
  it("launches only the explicit WeChat and QQ schemes", () => {
    const destinations: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      destinations.push(this.href);
    });
    launchShareApp("wechat");
    launchShareApp("qq");
    expect(destinations).toEqual(["weixin://", "mqq://"]);
    expect(document.querySelector("a")).toBeNull();
  });
  it("downloads a PNG and revokes its object URL after a safe delay", () => {
    vi.useFakeTimers();
    const create = vi.fn(() => "blob:poster"),
      revoke = vi.fn();
    vi.stubGlobal(
      "URL",
      class extends URL {
        static createObjectURL = create;
        static revokeObjectURL = revoke;
      },
    );
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      expect(this.href).toBe("blob:poster");
      expect(this.download).toBe("CendoDrive-分享二维码.png");
    });
    const blob = new Blob(["png"], { type: "image/png" });
    downloadSharePoster(blob);
    expect(create).toHaveBeenCalledWith(blob);
    expect(document.querySelector("a")).toBeNull();
    expect(revoke).not.toHaveBeenCalled();
    vi.advanceTimersByTime(60_000);
    expect(revoke).toHaveBeenCalledWith("blob:poster");
  });
});
