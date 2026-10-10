// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ShareLinkDialog from "../components/ShareLinkDialog.vue";
import { useDrive } from "./drive";
import { listShares } from "../api/shares";
vi.mock("../api/shares", async (original) => ({
  ...(await original<typeof import("../api/shares")>()),
  listShares: vi.fn(),
}));
const record = {
  id: "1",
  token: "abc",
  fileId: "42",
  fileName: "test.md",
  kind: "file" as const,
  size: 10,
  createdAt: "2026-01-01",
  expiresAt: "2030-01-01",
  status: "ACTIVE" as const,
  hasExtractionCode: true,
};
afterEach(() => {
  useDrive().reset();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});
describe("owner share extraction code reload", () => {
  it("displays the server code after reopening shares and after resetting page state", async () => {
    const drive = useDrive();
    vi.mocked(listShares).mockResolvedValue([
      { ...record, extractionCode: "Ab12" },
    ]);
    await drive.loadShares();
    expect(drive.state.shares[0]?.extractionCode).toBe("Ab12");
    drive.reset();
    await drive.loadShares();
    expect(drive.state.shares[0]?.extractionCode).toBe("Ab12");
  });
  it("shows and independently copies a recovered code with no previous page memory", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    const drive = useDrive();
    drive.reset();
    vi.mocked(listShares).mockResolvedValue([
      { ...record, extractionCode: "Ab12" },
    ]);
    await drive.loadShares();
    const page = mount(ShareLinkDialog, {
      props: { share: drive.state.shares[0]! },
      global: { stubs: { teleport: true } },
    });
    try {
      expect(
        (page.get("#share-code-value").element as HTMLInputElement).value,
      ).toBe("Ab12");
      await page
        .findAll("button")
        .find((button) => button.text() === "复制提取码")!
        .trigger("click");
      await flushPromises();
      expect(writeText).toHaveBeenCalledWith("Ab12");
      expect(page.text()).not.toContain("为安全起见");
    } finally {
      page.unmount();
    }
  });
  it("uses the server response rather than overwriting it with a stale memory code", async () => {
    const drive = useDrive();
    drive.state.shares = [{ ...record, extractionCode: "Old1" }];
    vi.mocked(listShares).mockResolvedValue([
      { ...record, extractionCode: "New2" },
      { ...record, id: "2", extractionCode: null },
    ]);
    await drive.loadShares();
    expect(drive.state.shares[0]?.extractionCode).toBe("New2");
    expect(drive.state.shares[1]?.extractionCode).toBeNull();
  });
});
