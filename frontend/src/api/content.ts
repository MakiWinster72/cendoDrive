import http from "./http";

export interface SplashAdContent {
  id: string;
  title: string;
  imageUrl: string;
  targetUrl: string | null;
  displaySeconds: number;
  skipAfterSeconds: number;
}

export interface ContentHomeResponse {
  membershipEntry: {
    title: string;
    subtitle: string;
    targetUrl: string;
  } | null;
  profileCampaign: {
    id: string;
    title: string;
    subtitle: string;
    buttonText: string;
    targetUrl: string;
    imageUrl: string | null;
  } | null;
}

export interface MembershipPlanContent {
  id: string;
  name: string;
  price: string;
  suffix: string;
  originalPrice: string | null;
  badge: string | null;
  sortOrder: number;
}

export interface MembershipBenefitGroup {
  id: string;
  title: string;
  columns: string[];
  rows: Array<{ label: string; values: string[] }>;
}

export interface MembershipContentResponse {
  plans: MembershipPlanContent[];
  benefitGroups: MembershipBenefitGroup[];
}

export interface GameContentItem {
  id: string;
  name: string;
  category: string;
  description: string;
  coverUrl: string | null;
  targetUrl: string | null;
}

export interface GameCenterContentResponse {
  categories: string[];
  games: GameContentItem[];
}

export interface NovelContentItem {
  id: string;
  title: string;
  author: string;
  coverUrl: string | null;
  description: string | null;
  targetUrl: string | null;
}

export interface NovelSectionContent {
  id: string;
  title: string;
  books: NovelContentItem[];
}

export interface NovelHubContentResponse {
  sections: NovelSectionContent[];
}

async function getContent<T>(path: string): Promise<T> {
  const { data } = await http.get<T>(path);
  return data;
}

export async function getSplashAd(): Promise<SplashAdContent | null> {
  const response = await http.get<SplashAdContent | null>("/content/splash-ad");
  if (response.status === 204 || response.data == null) return null;
  return response.data;
}

export const getHomeContent = () =>
  getContent<ContentHomeResponse>("/content/home");

export const getMembershipContent = () =>
  getContent<MembershipContentResponse>("/content/membership");

export const getGameCenterContent = () =>
  getContent<GameCenterContentResponse>("/content/games");

export const getNovelHubContent = () =>
  getContent<NovelHubContentResponse>("/content/novels");
