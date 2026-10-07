import { beforeEach, describe, expect, it, vi } from "vitest";
import http from "./http";
import { cancelShare, createShare, listShares } from "./shares";

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

  it("cancels a share by its owner-side share id", async () => {
    vi.mocked(http.delete).mockResolvedValue({ data: undefined });
    await cancelShare("share/51");
    expect(http.delete).toHaveBeenCalledWith("/shares/share%2F51");
  });
});
