import type { SplashAdContent } from "./content";

const images = ["/ad/popAD.JPG", "/ad/popAD1.jpg"];

export const localSplashAdId = "local-pop-ad";

export function getLocalSplashAd(): SplashAdContent {
  return {
    id: localSplashAdId,
    title: "开屏广告",
    imageUrl: images[Math.floor(Math.random() * images.length)]!,
    targetUrl: null,
    displaySeconds: 5,
    skipAfterSeconds: 1,
  };
}
