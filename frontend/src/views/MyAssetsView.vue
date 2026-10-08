<script setup lang="ts">
import { computed, ref } from "vue";
import { ChevronLeft, Ticket } from "lucide-vue-next";
import { useRouter } from "vue-router";
import "../styles/mobile-commerce.css";

const router = useRouter();
const categories = ["特权券", "福利券", "礼品券", "生日礼"] as const;
const statuses = ["待领取", "可使用", "即将过期", "已失效"] as const;
const activeCategory = ref<(typeof categories)[number]>("特权券");
const activeStatus = ref<(typeof statuses)[number]>("待领取");
const emptyText = computed(() => {
  if (activeStatus.value === "待领取" && activeCategory.value === "特权券") return "暂无特权券";
  if (activeStatus.value === "待领取") return `暂无待领取${activeCategory.value}`;
  if (activeStatus.value === "可使用") return `暂无可使用${activeCategory.value}`;
  if (activeStatus.value === "即将过期") return `暂无即将过期的${activeCategory.value}`;
  return `暂无已失效的${activeCategory.value}`;
});
const description = computed(() => activeStatus.value === "待领取" && activeCategory.value === "特权券" ? "已收到体验券，请尽快领取" : "有新的资产时会显示在这里");

function goBack() {
  if (window.history.state?.back) router.back();
  else void router.replace({ name: "home" });
}
</script>

<template>
  <main class="commerce-page assets-page">
    <header class="commerce-header assets-header">
      <button class="back-button" aria-label="返回" @click="goBack"><ChevronLeft /></button>
      <h1>我的资产</h1>
      <span class="header-placeholder"></span>
    </header>

    <nav class="asset-categories" aria-label="资产类型">
      <button v-for="category in categories" :key="category" :class="{ active: activeCategory === category }" @click="activeCategory = category">{{ category }}</button>
    </nav>
    <div class="asset-statuses" aria-label="资产状态筛选">
      <button v-for="status in statuses" :key="status" :class="{ active: activeStatus === status }" @click="activeStatus = status">{{ status }}</button>
    </div>
    <p class="asset-hint">{{ description }}</p>

    <section class="asset-empty" aria-live="polite">
      <div class="ticket-stack"><Ticket :size="31" fill="currentColor" /></div>
      <p>{{ emptyText }}</p>
    </section>
  </main>
</template>
