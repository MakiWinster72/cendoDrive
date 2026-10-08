<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ChevronLeft, ChevronRight, Coins, Ellipsis, Gift, RefreshCw, Trophy } from "@lucide/vue";
import { useRouter } from "vue-router";
import UnavailableFeatureDialog from "../components/UnavailableFeatureDialog.vue";
import { getGameCenterContent, type GameContentItem } from "../api/content";
import { demoGameCenterContent } from "../api/contentDemo";
import "../styles/mobile-media.css";
import "../styles/game-center.css";

const router = useRouter();
const notice = ref("");
const activeCategory = ref("热门");
const contentSource = ref<"demo" | "backend">("demo");
const categories = ref([...demoGameCenterContent.categories]);
const rewardSteps = [
  { reward: "100MB", icon: "⬡", task: "1个任务" },
  { reward: "30s", icon: "ϟ", task: "2个任务" },
  { reward: "5金币", icon: "◉", task: "4个任务" },
  { reward: "500MB", icon: "⬡", task: "6个任务" },
  { reward: "5分", icon: "ϟ", task: "8个任务" },
];
type DisplayGame = GameContentItem & { tagline: string; emoji?: string; tone: string };
const tones = ["peach", "blue", "mint", "rose", "sand", "lavender"];
const emojis = ["🐻", "🧧", "🐼", "🌺", "🗡️", "🐑"];
const games = ref<DisplayGame[]>(demoGameCenterContent.games.map((game, index) => ({
  ...game,
  tagline: game.description,
  emoji: emojis[index % emojis.length],
  tone: tones[index % tones.length]!,
})));
const filteredGames = computed(() => activeCategory.value === "热门" ? games.value : games.value.filter(game => game.category === activeCategory.value));

onMounted(async () => {
  try {
    const content = await getGameCenterContent();
    categories.value = content.categories;
    games.value = content.games.map((game, index) => ({
      ...game,
      tagline: game.description,
      tone: tones[index % tones.length]!,
    }));
    contentSource.value = "backend";
    if (!categories.value.includes(activeCategory.value)) activeCategory.value = categories.value[0] ?? "";
  } catch {
    // Keep the clearly marked local demo content until the backend endpoint is available.
  }
});

function goBack() {
  if (window.history.state?.back) router.back();
  else void router.replace({ name: "home" });
}
function showNotice(message: string) {
  notice.value = message;
}
function openGame(targetUrl: string | null) {
  if (!targetUrl) {
    showNotice("游戏详情及下载暂未接入");
    return;
  }
  try {
    const target = new URL(targetUrl, window.location.origin);
    if (target.origin === window.location.origin) {
      void router.push(`${target.pathname}${target.search}${target.hash}`);
    } else if (target.protocol === "https:") {
      window.open(target.href, "_blank", "noopener,noreferrer");
    } else {
      showNotice("游戏跳转地址无效");
    }
  } catch {
    showNotice("游戏跳转地址无效");
  }
}
function refreshRecommendations() {
  if (contentSource.value === "demo") {
    showNotice("推荐内容接口暂未接入");
    return;
  }
  if (games.value.length > 1) games.value = [...games.value.slice(1), games.value[0]!];
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

      <section v-if="contentSource === 'demo'" class="game-task-card">
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
        <div class="game-section-heading"><h2>精品推荐 <small v-if="contentSource === 'demo'" class="game-demo-tag">演示内容</small></h2><button aria-label="换一换" @click="refreshRecommendations">换一换 <RefreshCw :size="14" /></button></div>
        <div class="featured-games">
          <button v-for="(game, index) in games" :key="game.id" class="featured-game" @click="openGame(game.targetUrl)">
            <span class="game-art" :class="game.tone"><img v-if="game.coverUrl" :src="game.coverUrl" :alt="game.name" /><i v-else>{{ game.emoji || '🎮' }}</i><small>{{ index % 2 === 0 ? '精选' : '热门' }}</small></span>
            <strong>{{ game.name }}</strong><small>{{ game.tagline }}</small>
          </button>
          <p v-if="!games.length" class="game-empty-state">暂无游戏推荐</p>
        </div>
      </section>

      <section class="more-games-section">
        <div class="game-section-heading"><h2>更多游戏</h2><button @click="showNotice('游戏列表暂未接入')">查看全部 <ChevronRight :size="15" /></button></div>
        <div class="game-category-strip">
          <button v-for="category in categories" :key="category" :class="{ active: activeCategory === category }" @click="activeCategory = category">{{ category }}</button>
        </div>
        <div class="more-game-list">
          <button v-for="game in filteredGames" :key="game.id" class="more-game-row" @click="openGame(game.targetUrl)">
            <span class="more-game-icon" :class="game.tone"><img v-if="game.coverUrl" :src="game.coverUrl" :alt="game.name" /><template v-else>{{ game.emoji || '🎮' }}</template></span>
            <span class="more-game-copy"><strong>{{ game.name }}</strong><small>{{ game.category }} · {{ game.tagline }}</small></span>
            <span class="game-open-button">打开</span>
          </button>
          <p v-if="!filteredGames.length" class="game-empty-state">该分类暂无游戏</p>
        </div>
      </section>
      <p class="media-demo-note"><Trophy :size="13" />{{ contentSource === "demo" ? "游戏内容接口尚未接入，当前展示演示数据；任务和奖励服务也未接入" : "游戏推荐由内容接口返回；任务和奖励服务尚未接入" }}</p>
    </div>
    <UnavailableFeatureDialog :open="Boolean(notice)" :message="notice" @close="notice = ''" />
  </main>
</template>
