import { beforeEach, describe, expect, it, vi } from "vitest";
import http from "./http";
import {
  getGameCenterContent,
  getHomeContent,
  getMembershipContent,
  getNovelHubContent,
  getSplashAd,
} from "./content";

vi.mock("./http", () => ({ default: { get: vi.fn() } }));

beforeEach(() => vi.resetAllMocks());

describe("commercial content API proposal", () => {
  it("requests the active splash ad and maps 204 to no ad", async () => {
    vi.mocked(http.get).mockResolvedValueOnce({
      status: 200,
      data: { id: "ad-1" },
    });
    expect(await getSplashAd()).toEqual({ id: "ad-1" });
    expect(http.get).toHaveBeenCalledWith("/content/splash-ad");

    vi.mocked(http.get).mockResolvedValueOnce({ status: 204, data: "" });
    expect(await getSplashAd()).toBeNull();
  });

  it.each([
    [getHomeContent, "/content/home"],
    [getMembershipContent, "/content/membership"],
    [getGameCenterContent, "/content/games"],
    [getNovelHubContent, "/content/novels"],
  ])("requests %s", async (request, path) => {
    vi.mocked(http.get).mockResolvedValueOnce({
      status: 200,
      data: { ok: true },
    });
    await expect(request()).resolves.toEqual({ ok: true });
    expect(http.get).toHaveBeenCalledWith(path);
  });
});
