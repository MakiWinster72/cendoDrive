import type {
  ContentHomeResponse,
  GameCenterContentResponse,
  MembershipContentResponse,
  NovelHubContentResponse,
} from "./content";

export const demoHomeContent: ContentHomeResponse = {
  membershipEntry: {
    title: "会员免费领",
    subtitle: "新用户福利",
    targetUrl: "/membership",
  },
  profileCampaign: {
    id: "demo-campaign",
    title: "网盘 SVIP 会员活动",
    subtitle: "活动领取能力暂未开放",
    buttonText: "查看方案",
    targetUrl: "/membership",
    imageUrl: null,
  },
};

export const demoMembershipContent: MembershipContentResponse = {
  plans: [
    { id: "trial", name: "7天体验卡", price: "5.8", suffix: "", originalPrice: "22.2", badge: "国庆特惠", sortOrder: 0 },
    { id: "year", name: "连续包年", price: "152", suffix: "/年", originalPrice: "498", badge: "新人立减146", sortOrder: 1 },
    { id: "month", name: "连续包月", price: "0.6", suffix: "/天", originalPrice: "30", badge: "连续包月", sortOrder: 2 },
  ],
  benefitGroups: [
    {
      id: "ai-services",
      title: "AI智能服务",
      columns: ["SVIP", "VIP", "普通用户"],
      rows: [
        { label: "AI点数充值", values: ["赠送点数", "—", "—"] },
        { label: "视频生成", values: ["AI点数", "—", "—"] },
        { label: "图片生成", values: ["AI点数", "—", "—"] },
        { label: "PPT生成", values: ["AI点数", "—", "—"] },
        { label: "文档生成", values: ["AI点数", "—", "—"] },
      ],
    },
    {
      id: "storage-transfer",
      title: "储存与传输",
      columns: ["SVIP", "VIP", "普通用户"],
      rows: [
        { label: "储存空间", values: ["5120G", "—", "—"] },
        { label: "文件清理", values: ["✓", "✓", "—"] },
        { label: "文件恢复服务", values: ["✓", "✓", "—"] },
      ],
    },
  ],
};

export const demoGameCenterContent: GameCenterContentResponse = {
  categories: ["热门", "休闲益智", "模拟经营", "角色扮演", "放置挂机"],
  games: [
    { id: "demo-restaurant", name: "卡皮巴拉小餐厅", category: "模拟经营", description: "指尖魔法畅享休闲", coverUrl: null, targetUrl: null },
    { id: "demo-cards", name: "JJ斗地主", category: "休闲益智", description: "各路高手都在这", coverUrl: null, targetUrl: null },
    { id: "demo-cultivation", name: "寻道大千", category: "放置挂机", description: "砍树爆装，挂机修仙", coverUrl: null, targetUrl: null },
    { id: "demo-garden", name: "我的花园世界", category: "模拟经营", description: "玩游戏赢真实花礼", coverUrl: null, targetUrl: null },
    { id: "demo-strategy", name: "兵法三十七计", category: "角色扮演", description: "封疆扩张，攻城略地", coverUrl: null, targetUrl: null },
    { id: "demo-shop", name: "哈拉小铺", category: "休闲益智", description: "助力一场探索神秘", coverUrl: null, targetUrl: null },
  ],
};

export const demoNovelHubContent: NovelHubContentResponse = {
  sections: [
    {
      id: "you-may-like",
      title: "你可能在找",
      books: [
        { id: "demo-book-1", title: "我，修仙，一开始就无敌", author: "画江山", coverUrl: null, description: "", targetUrl: null },
        { id: "demo-book-2", title: "替嫁宠妃：残疾大佬…", author: "糖果可可", coverUrl: null, description: "", targetUrl: null },
        { id: "demo-book-3", title: "我的绝美特工老婆", author: "程以武", coverUrl: null, description: "", targetUrl: null },
        { id: "demo-book-4", title: "二婚嫁京圈大佬，渣…", author: "程以武", coverUrl: null, description: "", targetUrl: null },
      ],
    },
    {
      id: "male-popular",
      title: "男生热读",
      books: [
        { id: "demo-book-5", title: "山海拾遗", author: "云上行", coverUrl: null, description: "", targetUrl: null },
        { id: "demo-book-6", title: "长夜有星", author: "青禾", coverUrl: null, description: "", targetUrl: null },
        { id: "demo-book-7", title: "风起人间", author: "南枝", coverUrl: null, description: "", targetUrl: null },
      ],
    },
  ],
};
