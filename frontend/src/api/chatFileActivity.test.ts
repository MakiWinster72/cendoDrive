// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  getLikedChatFiles,
  getViewedChatFiles,
  isChatFileLiked,
  recordChatFileViewed,
  setChatFileLiked,
} from "./chatFileActivity";

beforeEach(() => {
  const values = new Map<string, string>();
  vi.stubGlobal("localStorage", {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
  });
});
afterEach(() => vi.unstubAllGlobals());

describe("chat file activity", () => {
  it("keeps the most recent view first and deduplicates a file", () => {
    const file = {
      roomId: "room-a",
      messageId: 14,
      name: "brief.pdf",
      size: 1024,
    };
    recordChatFileViewed(file);
    recordChatFileViewed({ ...file, name: "brief-final.pdf" });

    expect(getViewedChatFiles()).toHaveLength(1);
    expect(getViewedChatFiles()[0]?.name).toBe("brief-final.pdf");
    expect(getLikedChatFiles()).toEqual([]);
  });

  it("adds and removes likes by room and message identity", () => {
    const file = {
      roomId: "room-a",
      messageId: 14,
      name: "brief.pdf",
      size: 1024,
    };
    setChatFileLiked(file, true);
    expect(isChatFileLiked(file)).toBe(true);
    expect(getLikedChatFiles()).toHaveLength(1);

    setChatFileLiked(file, false);
    expect(isChatFileLiked(file)).toBe(false);
    expect(getLikedChatFiles()).toEqual([]);
  });
});
