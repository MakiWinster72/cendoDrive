// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import ShareView from "./ShareView.vue";
import { downloadPublicShare, getPublicShare, saveSharedFile } from "../api/shares";

const { ensureSession } = vi.hoisted(() => ({ ensureSession: vi.fn() }));
vi.mock("../stores/auth", () => ({ useAuth: () => ({ ensureSession, loggedIn: { value: true }, verificationError: { value: "" } }) }));
vi.mock("../api/shares", async original => ({ ...await original<typeof import("../api/shares")>(), getPublicShare: vi.fn(), downloadPublicShare: vi.fn(), saveSharedFile: vi.fn() }));
const access = { file: { id: "42", name: "机密笔记.md", kind: "file" as const, size: 42, parentId: null, deletedAt: null, createdAt: "2026-10-08T00:00:00Z", updatedAt: "2026-10-08T00:00:00Z" }, expiresAt: "2030-01-01T00:00:00Z" };
const failure = (code: string, status = 403) => ({ isAxiosError: true, response: { status, data: { code } } });
let wrapper: VueWrapper | undefined, router: Router;
async function open(token = "protected") {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/share/:token", component: { template: "<div />" } }, { path: "/login", name: "login", component: { template: "<div />" } }] });
  await router.push(`/share/${token}`); await router.isReady();
  wrapper = mount(ShareView, { global: { plugins: [router] }, attachTo: document.body }); await flushPromises(); return wrapper;
}
beforeEach(() => { vi.clearAllMocks(); ensureSession.mockResolvedValue(true); vi.mocked(downloadPublicShare).mockResolvedValue(undefined); vi.mocked(saveSharedFile).mockResolvedValue(access.file); });
afterEach(() => { wrapper?.unmount(); wrapper = undefined; });

describe("public extraction-code form", () => {
  it("loads an opt-in fragment code through the header without a query parameter", async () => {
    vi.mocked(getPublicShare).mockResolvedValue({ ...access, expiresAt: "9999-12-31T23:59:59Z" });
    const page = await open("protected#code=Ab12");
    expect(getPublicShare).toHaveBeenCalledWith("protected", expect.any(AbortSignal), "Ab12");
    expect(page.text()).toContain("永久有效");
    expect(router.currentRoute.value.query).toEqual({});
  });
  it("ignores malformed fragment codes", async () => {
    vi.mocked(getPublicShare).mockResolvedValue(access);
    await open("open#code=%3Cscript%3E");
    expect(getPublicShare).toHaveBeenCalledWith("open", expect.any(AbortSignal), undefined);
  });
  it("hides protected metadata until validation and passes the same code to download and save", async () => {
    vi.mocked(getPublicShare).mockRejectedValueOnce(failure("SHARE_CODE_REQUIRED")).mockResolvedValue(access);
    const page = await open(); expect(page.text()).toContain("请输入提取码"); expect(page.text()).not.toContain(access.file.name);
    expect(document.activeElement).toBe(page.get("#public-share-code").element);
    await page.get("input").setValue("Ab12"); await page.get("form").trigger("submit"); await flushPromises();
    expect(getPublicShare).toHaveBeenLastCalledWith("protected", expect.any(AbortSignal), "Ab12");
    expect(page.text()).toContain(access.file.name); expect(router.currentRoute.value.fullPath).toBe("/share/protected");
    await page.get(".download").trigger("click"); await page.get(".save-share").trigger("click"); await flushPromises();
    expect(downloadPublicShare).toHaveBeenCalledWith("protected", access.file.name, "Ab12");
    expect(saveSharedFile).toHaveBeenCalledWith("protected", null, "Ab12"); expect(page.text()).toContain("已转存");
  });
  it("keeps the form for an incorrect code and rate-limit response, then allows explicit retry", async () => {
    vi.mocked(getPublicShare).mockRejectedValueOnce(failure("SHARE_CODE_REQUIRED"))
      .mockRejectedValueOnce(failure("SHARE_CODE_INVALID")).mockRejectedValueOnce(failure("RATE_LIMITED", 429)).mockResolvedValue(access);
    const page = await open(); await page.get("input").setValue("Bad1"); await page.get("form").trigger("submit"); await flushPromises();
    expect(page.get("[role=alert]").text()).toContain("提取码不正确");
    await page.get("form").trigger("submit"); await flushPromises(); expect(page.get("[role=alert]").text()).toContain("15 分钟");
    await page.get("input").setValue("Ab12"); await page.get("form").trigger("submit"); await flushPromises(); expect(page.text()).toContain(access.file.name);
  });
  it("switches to unavailable if a protected share expires while entering the code", async () => {
    vi.mocked(getPublicShare).mockRejectedValueOnce(failure("SHARE_CODE_REQUIRED")).mockRejectedValueOnce(failure("SHARE_NOT_FOUND", 404));
    const page = await open(); await page.get("input").setValue("Ab12"); await page.get("form").trigger("submit"); await flushPromises();
    expect(page.text()).toContain("分享不可用"); expect(page.find("form").exists()).toBe(false);
  });

  it("does not prompt for a code on an open share or a revoked link", async () => {
    vi.mocked(getPublicShare).mockResolvedValueOnce(access).mockRejectedValueOnce(failure("SHARE_NOT_FOUND", 404));
    const page = await open("open"); expect(page.find("input").exists()).toBe(false); expect(page.text()).toContain(access.file.name);
    await router.push("/share/revoked"); await flushPromises(); expect(page.text()).toContain("分享不可用"); expect(page.find("form").exists()).toBe(false);
  });
  it("clears the code and ignores an old response when the token changes", async () => {
    let resolve!: (value: typeof access) => void;
    vi.mocked(getPublicShare).mockRejectedValueOnce(failure("SHARE_CODE_REQUIRED"))
      .mockImplementationOnce(() => new Promise(r => { resolve = r; })).mockRejectedValueOnce(failure("SHARE_CODE_REQUIRED"));
    const page = await open(); await page.get("input").setValue("Ab12"); await page.get("form").trigger("submit");
    await router.push("/share/new-token"); await flushPromises(); resolve(access); await flushPromises();
    expect(getPublicShare).toHaveBeenLastCalledWith("new-token", expect.any(AbortSignal), undefined);
    expect((page.get("input").element as HTMLInputElement).value).toBe(""); expect(page.text()).not.toContain(access.file.name);
  });
  it("redirects an anonymous save to login without sending a transfer request", async () => {
    vi.mocked(getPublicShare).mockResolvedValue(access); ensureSession.mockResolvedValue(false);
    const page = await open("open"); await page.get(".save-share").trigger("click"); await flushPromises();
    expect(saveSharedFile).not.toHaveBeenCalled(); expect(router.currentRoute.value.query.redirect).toBe("/share/open");
  });
});
