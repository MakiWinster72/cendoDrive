import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { localRecommendationAds } from "./localRecommendationAds";
import manifest from "./recommendationAdResources.json";

describe("local recommendation resources", () => {
  it("contains 30 unique local JPEGs including the seven existing ads", () => {
    expect(localRecommendationAds).toHaveLength(30);
    expect(new Set(localRecommendationAds.map((ad) => ad.image)).size).toBe(30);
    for (const ad of localRecommendationAds) {
      expect(ad.title.trim()).not.toBe("");
      expect(ad.image).toMatch(/^[a-z0-9-]+\.jpg$/);
      const bytes = readFileSync(
        new URL(`../../public/ad/${ad.image}`, import.meta.url),
      );
      expect([...bytes.subarray(0, 2)]).toEqual([0xff, 0xd8]);
    }
    for (let number = 3; number <= 9; number++) {
      expect(
        localRecommendationAds.some((ad) => ad.image === `${number}.jpg`),
      ).toBe(true);
    }
  });

  it("tracks verified source metadata and distinct hashes for all 23 fetched assets", () => {
    expect(manifest.resources).toHaveLength(23);
    expect(new Set(manifest.resources.map((ad) => ad.sha256)).size).toBe(23);
    for (const resource of manifest.resources) {
      expect(new URL(resource.sourceUrl).hostname).toBe("unsplash.com");
      expect(new URL(resource.downloadUrl).hostname).toBe("picsum.photos");
      expect(resource.author.trim()).not.toBe("");
      const bytes = readFileSync(
        new URL(`../../public/ad/${resource.image}`, import.meta.url),
      );
      expect(createHash("sha256").update(bytes).digest("hex")).toBe(
        resource.sha256,
      );
    }
  });
});
