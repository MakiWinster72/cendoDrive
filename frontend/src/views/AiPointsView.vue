<script setup lang="ts">
import { computed, ref } from "vue";
import { ChevronDown, ChevronLeft, ChevronRight, CircleHelp, Gem, Sparkles } from "@lucide/vue";
import { useRouter } from "vue-router";
import UnavailableFeatureDialog from "../components/UnavailableFeatureDialog.vue";
import "../styles/mobile-commerce.css";

const router = useRouter();
const packs = [
  { points: 200, price: 9, original: 22.5 },
  { points: 500, price: 19, original: 45 },
  { points: 1000, price: 36, original: 45 },
  { points: 1500, price: 48, original: 67.5 },
  { points: 2000, price: 59, original: 90 },
  { points: 5000, price: 135, original: 225 },
];
const selectedPackIndex = ref(0);
const paymentMethod = ref("支付宝");
const showHistory = ref(false);
const agreed = ref(true);
const notice = ref("");
const selectedPack = computed(() => packs[selectedPackIndex.value]!);

function goBack() {
  if (window.history.state?.back) router.back();
  else void router.replace({ name: "home" });
}
function showNotice(message: string) {
  notice.value = message;
}
function pay() {
  if (!agreed.value) {
    showNotice("请先阅读并同意充值协议");
    return;
  }
  showNotice("充值支付暂未接入，当前不会产生扣款");
}
</script>

<template>
  <main class="commerce-page points-page">
    <header class="commerce-header points-header">
      <button class="back-button" aria-label="返回" @click="goBack"><ChevronLeft /></button>
      <h1>AI点数</h1>
      <button class="header-link" @click="showHistory = !showHistory">点数明细</button>
    </header>

    <div class="commerce-scroll points-scroll">
      <section class="points-balance">
        <div class="balance-sparkle"><Sparkles :size="30" fill="currentColor" /></div>
        <strong>0</strong><span>点 <button aria-label="点数说明" @click="showNotice('AI点数用于兑换AI增值服务')"><CircleHelp :size="14" /></button></span>
        <div class="balance-detail"><span>赠送 0</span><i></i><span>充值 0</span></div>
        <div class="balance-watermark"><Sparkles :size="100" /></div>
      </section>

      <section v-if="showHistory" class="history-empty"><Sparkles :size="25" /><strong>暂无点数明细</strong><span>充值或使用点数后，记录会显示在这里</span></section>

      <section class="points-packs" aria-label="选择充值点数">
        <button
          v-for="(pack, index) in packs"
          :key="pack.points"
          class="points-pack"
          :class="{ selected: selectedPackIndex === index }"
          :aria-pressed="selectedPackIndex === index"
          @click="selectedPackIndex = index"
        >
          <strong><Sparkles :size="16" fill="currentColor" />{{ pack.points }}</strong>
          <span>¥ {{ pack.price }}</span>
          <del>¥ {{ pack.original }}</del>
        </button>
        <p>页面套餐为演示数据；充值及支付服务暂未接入</p>
      </section>

      <button class="points-member-link" @click="router.push({ name: 'membership' })"><Gem :size="16" />免费获得250点/月 <span>开通SVIP <ChevronRight :size="15" /></span></button>

      <section class="points-payment">
        <strong>支付方式</strong>
        <button @click="paymentMethod = paymentMethod === '支付宝' ? '微信支付' : '支付宝'"><span class="pay-logo alipay-logo">支</span>{{ paymentMethod }}<ChevronDown :size="16" /></button>
      </section>

      <section class="points-description">
        <h2>点数说明</h2>
        <p>AI点数用于兑换AI增值服务，具体规则将在服务开放后说明。</p>
        <p>当前余额和充值档位为演示数据。</p>
        <p>充值及支付服务暂未接入。</p>
      </section>
      <p class="demo-caption points-demo"><CircleHelp :size="14" />演示页面：充值和支付接口尚未接入</p>
    </div>

    <footer class="commerce-paybar points-paybar">
      <button class="primary-pay" @click="pay">¥ {{ selectedPack.price }} 确认协议并支付</button>
      <label><input v-model="agreed" type="checkbox" /> 我已阅读并同意 <button @click.prevent="showNotice('充值协议暂未提供')">充值协议</button></label>
    </footer>
    <UnavailableFeatureDialog :open="Boolean(notice)" :message="notice" @close="notice = ''" />
  </main>
</template>
