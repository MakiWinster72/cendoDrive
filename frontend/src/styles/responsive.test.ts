import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";

const read = (path: string) => readFileSync(new URL(path, import.meta.url), "utf8");
const main = read("./main.css");
const home = read("./home.css");
const profile = read("./profile.css");
const share = read("./share.css");
const files = read("./file-list.css");
const selection = read("./selection.css");
const layout = read("./mobile-layout.css");
const hub = read("./share-hub.css");
const upload = read("../components/UploadPanel.vue");

describe("responsive style ownership", () => {
  it("keeps bottom navigation in one shared stylesheet", () => {
    for (const source of [main, home, profile, hub]) {
      expect(source).not.toContain(".m-bottom-nav");
    }
    expect(layout).toContain("--mobile-gutter: 18px");
    expect(layout).toContain("height: var(--mobile-nav-height)");
    expect(layout).toContain("max(18px, env(safe-area-inset-bottom))");
    expect(layout).not.toContain(":has(");
  });

  it("uses common gutters and bottom clearance across primary tabs", () => {
    for (const source of [main, home, profile, hub]) {
      expect(source).toContain("var(--mobile-gutter)");
    }
    expect(main).toContain("padding-bottom: var(--mobile-content-bottom)");
    expect(home).toContain("padding-bottom: var(--mobile-content-bottom)");
    expect(profile).toContain("padding: 26px var(--mobile-gutter) 0");
    expect(hub).toContain("padding: 25px 0 0");
  });
  it.each([main, home, profile, share, files, selection, upload, layout, hub])(
    "uses the same exclusive mobile boundary",
    (source) => {
      expect(source).toContain("@media (width < 768px)");
      expect(source).not.toMatch(/max-width:\s*(700|768)px/);
    },
  );

  it("keeps homepage-only selectors out of global styles", () => {
    expect(main).not.toMatch(/\.m-(home-head|vip|profile-search|tools|panel|recent|banner|memory)\b/);
    expect(home).toContain(".m-panel-actions");
    expect(home).toContain("margin-left: auto");
    expect(home).toContain("flex-shrink: 0");
  });

  it("uses standard phone and tablet boundaries", () => {
    expect(main).toContain("@media (width < 640px)");
    expect(main).toContain("@media (width < 1024px)");
    expect(main).toContain("@media (768px <= width < 1024px)");
  });
});
