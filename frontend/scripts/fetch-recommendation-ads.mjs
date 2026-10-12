import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = fileURLToPath(new URL("../", import.meta.url));
const sourceUrl = "https://picsum.photos/v2/list?page=2&limit=30";
const curl = (url) =>
  execFileSync(
    "curl",
    [
      "--fail",
      "--location",
      "--silent",
      "--show-error",
      "--retry",
      "3",
      "--retry-all-errors",
      "--max-time",
      "30",
      url,
    ],
    { maxBuffer: 5 * 1024 * 1024 },
  );
let photos;
try {
  photos = JSON.parse(curl(sourceUrl).toString()).slice(0, 23);
} catch (cause) {
  throw new Error("Could not load the public photo resource list", { cause });
}
if (
  photos.length !== 23 ||
  new Set(photos.map((photo) => photo.id)).size !== 23
) {
  throw new Error("Expected 23 distinct photo resources");
}
const directory = path.join(root, "public/ad");
mkdirSync(directory, { recursive: true });
const resources = [];
const hashes = new Set();
for (const photo of photos) {
  const image = `recommendation-${photo.id}.jpg`;
  const downloadUrl = `https://picsum.photos/id/${photo.id}/540/720`;
  const bytes = curl(downloadUrl);
  if (bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes.length < 1024) {
    throw new Error(`Invalid JPEG: ${photo.id}`);
  }
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  if (hashes.has(sha256)) throw new Error(`Duplicate image: ${photo.id}`);
  hashes.add(sha256);
  writeFileSync(path.join(directory, image), bytes);
  resources.push({
    image,
    title: `发现好风景 · 镜头故事 ${resources.length + 1}`,
    author: photo.author,
    sourceUrl: photo.url,
    downloadUrl,
    sha256,
  });
  console.log(`Downloaded ${image} (${bytes.length} bytes)`);
}
writeFileSync(
  path.join(root, "src/api/recommendationAdResources.json"),
  JSON.stringify(
    {
      sourceUrl,
      licenseUrl: "https://unsplash.com/license",
      usage:
        "本地推荐广告展示素材，非真实品牌投放；上线前复核原图权利及广告使用条件。",
      resources,
    },
    null,
    2,
  ) + "\n",
);
console.log(`Saved ${resources.length} distinct JPEGs and source manifest`);
