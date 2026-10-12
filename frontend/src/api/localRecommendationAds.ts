import manifest from "./recommendationAdResources.json";

export type RecommendationAd = { image: string; title: string };

export const localRecommendationAds: readonly RecommendationAd[] = [
  { image: "4.jpg", title: "谁还没get到颜值爆棚cp呢" },
  { image: "3.jpg", title: "九门花絮：全都是被鸟啄的惨叫#曾舜系" },
  { image: "5.jpg", title: "我草当年凭一己之力掰弯整个港娱，没有人能拒绝..." },
  { image: "6.jpg", title: "和美女老婆流落荒岛" },
  { image: "7.jpg", title: "《危险派对》" },
  { image: "8.jpg", title: "樊拍凯摄" },
  { image: "9.jpg", title: "禁欲男神又宠又撩" },
  ...manifest.resources.map(({ image, title }) => ({ image, title })),
];
