import { beforeEach, describe, expect, it, vi } from "vitest";
import http from "./http";
import { downloadPublicShare, getPublicShare, shareErrorMessage } from "./shares";

const { publicGet, clientOptions } = vi.hoisted(() => ({
  publicGet: vi.fn(), clientOptions: [] as unknown[],
}));
vi.mock("axios", async (original) => {
  const actual = await original<typeof import("axios")>();
  return { ...actual, default: { ...actual.default, create: vi.fn((config: unknown) => { clientOptions.push(config); return { get: publicGet }; }) } };
});
vi.mock("./http", () => ({ default: { get: vi.fn(), post: vi.fn() } }));

beforeEach(() => vi.clearAllMocks());

describe("public sharing API", () => {
  it("reads without the authenticated client and can cancel the request", async () => {
    const data = { file: { name: "hello.txt" }, expiresAt: "2030-01-01T00:00:00Z" };
    publicGet.mockResolvedValue({ data });
    const signal = new AbortController().signal;
    await expect(getPublicShare("token/42", signal)).resolves.toEqual(data);
    expect(publicGet).toHaveBeenCalledWith("/shares/token%2F42", { signal });
    expect(http.get).not.toHaveBeenCalled();
    expect(clientOptions).toHaveLength(1);
    expect(clientOptions[0]).not.toHaveProperty("headers");
  });

  it("preserves an expired-share code when downloading a JSON Blob error", async () => {
    const error = { isAxiosError: true, response: {
      status: 404, data: new Blob([JSON.stringify({ code: "SHARE_NOT_FOUND" })], { type: "application/json" }),
    } };
    publicGet.mockRejectedValue(error);
    await expect(downloadPublicShare("token", "hello.txt")).rejects.toBe(error);
    expect(shareErrorMessage(error, "下载失败")).toBe("分享链接不存在、已过期或已取消");
  });

  it("retains a safe fallback for malformed Blob errors", async () => {
    const error = { isAxiosError: true, response: { status: 503, data: new Blob(["unavailable"]) } };
    publicGet.mockRejectedValue(error);
    await expect(downloadPublicShare("token", "hello.txt")).rejects.toBe(error);
    expect(shareErrorMessage(error, "下载失败")).toBe("服务器处理失败，请稍后重试");
  });

  it.each([
    ["NAME_CONFLICT", "我的网盘中已有同名文件，请先重命名后再转存"],
    ["UNAUTHORIZED", "登录状态已失效，请重新登录"],
    ["STORAGE_UNAVAILABLE", "分享或文件存储服务暂不可用，请稍后重试"],
  ])("translates %s for a recipient", (code, expected) => {
    expect(shareErrorMessage({ isAxiosError: true, response: { status: 409, data: { code } } }, "失败"))
      .toBe(expected);
  });
});
