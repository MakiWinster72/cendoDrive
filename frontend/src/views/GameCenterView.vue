<script setup lang="ts">
import { computed, ref } from "vue";
import { ChevronLeft, ChevronRight, Coins, Ellipsis, Gift, RefreshCw, Trophy } from "lucide-vue-next";
import { useRouter } from "vue-router";
import "../styles/mobile-media.css";
import "../styles/game-center.css";

const router = useRouter();
const notice = ref("");
const activeCategory = ref("热门");
const categories = ["热门", "休闲益智", "模拟经营", "角色扮演", "放置挂机"];
const rewardSteps = [
  { reward: "100MB", icon: "⬡", task: "1个任务" },
  { reward: "30s", icon: "ϟ", task: "2个任务" },
  { reward: "5金币", icon: "◉", task: "4个任务" },
  { reward: "500MB", icon: "⬡", task: "6个任务" },
  { reward: "5分", icon: "ϟ", task: "8个任务" },
];
const games = [
  { name: "卡皮巴拉小餐厅", tagline: "指尖魔法畅享休闲", category: "模拟经营", emoji: "🐻", tone: "peach" },
  { name: "JJ斗地主", tagline: "各路高手都在这", category: "休闲益智", emoji: "🧧", tone: "blue" },
  { name: "寻道大千", tagline: "砍树爆装，挂机修仙", category: "放置挂机", emoji: "🐼", tone: "mint" },
  { name: "我的花园世界", tagline: "玩游戏赢真实花礼", category: "模拟经营", emoji: "🌺", tone: "rose" },
  { name: "兵法三十七计", tagline: "封疆扩张，攻城略地", category: "角色扮演", emoji: "🗡️", tone: "sand" },
  { name: "哈拉小铺", tagline: "助力一场探索神秘", category: "休闲益智", emoji: "🐑", tone: "lavender" },
];
const filteredGames = computed(() => activeCategory.value === "热门" ? games : games.filter(game => game.category === activeCategory.value));
let noticeTimer: number | undefined;

function goBack() {
  if (window.history.state?.back) router.back();
  else void router.replace({ name: "home" });
}
function showNotice(message: string) {
  notice.value = message;
  if (noticeTimer) window.clearTimeout(noticeTimer);
  noticeTimer = window.setTimeout(() => (notice.value = ""), 2600);
}
</script>

<template>
  <main class="media-hub game-center-page">
    <header class="media-header game-center-header">
      <button aria-label="返回" @click="goBack"><ChevronLeft /></button>
      <h1>游戏中心</h1>
      <div class="game-header-actions">
        <button aria-label="金币兑换" @click="showNotice('金币兑换暂未开放')"><Coins /></button>
        <button aria-label="更多" @click="showNotice('更多游戏服务暂未开放')"><Ellipsis /></button>
      </div>
    </header>

    <div class="media-scroll game-center-scroll">
      <section class="game-balance">
        <span class="balance-coin"><Coins :size="35" fill="currentColor" /></span>
        <div class="game-balance-copy"><strong>0</strong><button @click="showNotice('金币兑换暂未开放')">1 🟡 可兑换 <Gift :size="13" /><ChevronRight :size="15" /></button></div>
        <span class="balance-glow"></span>
      </section>

      <section class="game-task-card">
        <div class="game-task-heading"><h2>做任务 <span>领权益</span></h2><small>完成游戏任务，领取累积奖励</small></div>
        <p class="task-progress-copy">今日已完成 <strong>0</strong> 个任务</p>
        <div class="task-rewards">
          <div v-for="step in rewardSteps" :key="step.task" class="task-reward">
            <strong>{{ step.reward }}</strong><span class="reward-icon">{{ step.icon }}</span><small>{{ step.task }}</small>
          </div>
        </div>
        <button class="task-cta" @click="showNotice('游戏任务服务暂未接入')"><Coins :size="23" fill="currentColor" />做任务 领金币</button>
      </section>

      <section class="game-recommend-section">
        <div class="game-section-heading"><h2>精品推荐</h2><button aria-label="换一换" @click="showNotice('推荐内容暂未接入')">换一换 <RefreshCw :size="14" /></button></div>
        <div class="featured-games">
          <button v-for="(game, index) in games" :key="game.name" class="featured-game" @click="showNotice('游戏详情及下载暂未接入')">
            <span class="game-art" :class="game.tone"><i>{{ game.emoji }}</i><small>{{ index % 2 === 0 ? '精选' : '热门' }}</small></span>
            <strong>{{ game.name }}</strong><small>{{ game.tagline }}</small>
          </button>
        </div>
      </section>

      <section class="more-games-section">
        <div class="game-section-heading"><h2>更多游戏</h2><button @click="showNotice('游戏列表暂未接入')">查看全部 <ChevronRight :size="15" /></button></div>
        <div class="game-category-strip">
          <button v-for="category in categories" :key="category" :class="{ active: activeCategory === category }" @click="activeCategory = category">{{ category }}</button>
        </div>
        <div class="more-game-list">
          <button v-for="game in filteredGames" :key="game.name" class="more-game-row" @click="showNotice('游戏详情及下载暂未接入')">
            <span class="more-game-icon" :class="game.tone">{{ game.emoji }}</span>
            <span class="more-game-copy"><strong>{{ game.name }}</strong><small>{{ game.category }} · {{ game.tagline }}</small></span>
            <span class="game-open-button">打开</span>
          </button>
        </div>
      </section>
      <p class="media-demo-note"><Trophy :size="13" /> 游戏推荐与任务为前端演示，游戏数据和奖励服务尚未接入</p>
    </div>
    <div v-if="notice" class="media-toast" role="status">{{ notice }}</div>
  </main>
</template>
