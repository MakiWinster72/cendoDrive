import { beforeEach, describe, expect, it, vi } from "vitest";
import http from "./http";
import { cancelShare, createShare, listShares, saveSharedFile } from "./shares";

vi.mock("./http", () => ({
  default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));

beforeEach(() => vi.clearAllMocks());

describe("share owner API contract", () => {
  const record = {
    id: "51",
    token: "opaque-token",
    fileId: "42",
    fileName: "project.pdf",
    kind: "file",
    size: 120,
    createdAt: "2026-09-30T04:00:00Z",
    expiresAt: "2026-10-07T04:00:00Z",
    status: "ACTIVE" as const,
  };

  it("creates and lists the authenticated user shares", async () => {
    vi.mocked(http.post).mockResolvedValue({ data: record });
    vi.mocked(http.get).mockResolvedValue({ data: [record] });

    await expect(createShare("42", 604800)).resolves.toEqual(record);
    await expect(listShares()).resolves.toEqual([record]);

    expect(http.post).toHaveBeenCalledWith("/shares", {
      fileId: "42",
      expiresInSeconds: 604800,
    });
    expect(http.get).toHaveBeenCalledWith("/shares");
  });

  it("saves into the recipient root with the authenticated client", async () => {
    const file = { id: "100", name: "project.pdf", kind: "file", size: 120, parentId: null };
    vi.mocked(http.post).mockResolvedValue({ data: file });
    await expect(saveSharedFile("token/51")).resolves.toEqual(file);
    expect(http.post).toHaveBeenCalledWith("/shares/token%2F51/save", { parentId: null }, { timeout: 0 });
  });

  it("can save into a specified recipient folder", async () => {
    vi.mocked(http.post).mockResolvedValue({ data: {} });
    await saveSharedFile("token", "12");
    expect(http.post).toHaveBeenCalledWith("/shares/token/save", { parentId: "12" }, { timeout: 0 });
  });

  it("cancels a share by its owner-side share id", async () => {
    vi.mocked(http.delete).mockResolvedValue({ data: undefined });
    await cancelShare("share/51");
    expect(http.delete).toHaveBeenCalledWith("/shares/share%2F51");
  });
});
