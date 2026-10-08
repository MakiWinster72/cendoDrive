<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ChevronLeft, ChevronRight, CircleHelp, Crown, ShieldCheck, Sparkles } from "lucide-vue-next";
import { useRouter } from "vue-router";
import UnavailableFeatureDialog from "../components/UnavailableFeatureDialog.vue";
import { getMembershipContent, type MembershipContentResponse } from "../api/content";
import { demoMembershipContent } from "../api/contentDemo";
import "../styles/mobile-commerce.css";

const router = useRouter();
const content = ref<MembershipContentResponse>(demoMembershipContent);
const contentSource = ref<"demo" | "backend">("demo");
const selectedPlan = ref(1);
const paymentMethod = ref<"alipay" | "wechat">("alipay");
const agreed = ref(true);
const notice = ref("");
const activePlan = computed(() => content.value.plans[selectedPlan.value] ?? null);

onMounted(async () => {
  try {
    content.value = await getMembershipContent();
    contentSource.value = "backend";
    selectedPlan.value = Math.min(selectedPlan.value, Math.max(content.value.plans.length - 1, 0));
  } catch {
    // Keep the clearly marked demo display until the backend endpoint is available.
  }
});

function goBack() {
  if (window.history.state?.back) router.back();
  else void router.replace({ name: "home" });
}
function showNotice(message: string) {
  notice.value = message;
}
function pay() {
  if (!activePlan.value) {
    showNotice("当前没有可展示的会员方案。");
    return;
  }
  if (!agreed.value) {
    showNotice("请先阅读并同意会员服务协议");
    return;
  }
  showNotice("支付功能暂未接入，当前不会产生扣款");
}
</script>

<template>
  <main class="commerce-page membership-page">
    <header class="commerce-header">
      <button class="back-button" aria-label="返回" @click="goBack"><ChevronLeft /></button>
      <h1>我的会员</h1>
      <button class="header-link" @click="showNotice('暂无会员订单')"><Crown :size="17" />订单</button>
    </header>

    <div class="commerce-scroll membership-scroll">
      <section class="member-hero">
        <div class="member-account">
          <div class="member-avatar">U</div>
          <div><strong>173****831</strong><span>您还不是 SVIP ›</span></div>
        </div>
        <div class="member-levels" aria-label="会员等级权益">
          <div class="level-active"><strong>SVIP</strong><span>不限速下载</span></div>
          <div><strong>VIP</strong><span>基础会员</span></div>
          <div><strong>空间扩容</strong><span>最高 5T</span></div>
          <div><strong>SVIP10</strong><span>专属权益</span></div>
        </div>
      </section>

      <p class="demo-inline">{{ contentSource === "demo" ? "内容接口尚未接入；当前展示内容均为演示数据" : "套餐和权益由内容接口返回；优惠、购买服务尚未接入" }}</p>
      <section v-if="content.plans.length" class="plan-section" aria-label="选择会员套餐">
        <button
          v-for="(plan, index) in content.plans"
          :key="plan.id"
          class="plan-card"
          :class="{ selected: selectedPlan === index }"
          :aria-pressed="selectedPlan === index"
          @click="selectedPlan = index"
        >
          <span v-if="plan.badge" class="plan-badge">{{ plan.badge }}</span>
          <strong>{{ plan.name }}</strong>
          <span class="plan-price"><small>¥</small>{{ plan.price }}<small>{{ plan.suffix }}</small></span>
          <del v-if="plan.originalPrice">¥{{ plan.originalPrice }}</del>
          <span class="plan-renew">选择此套餐 ›</span>
        </button>
      </section>
      <p v-else class="membership-empty-state">暂无可展示的会员方案</p>

      <button v-if="contentSource === 'demo'" class="coupon-row" @click="showNotice('优惠券服务暂未接入')">
        <span><b>券</b> 优惠券 <strong>最高减 ¥22.2</strong></span>
        <span>仅剩 00:09:43.17 <ChevronRight :size="16" /></span>
      </button>

      <section v-if="contentSource === 'demo'" class="upsell-card">
        <div><strong>买得多</strong><span>支付成功后自动领取 <CircleHelp :size="14" /></span></div>
        <button @click="router.push({ name: 'ai-points' })"><Sparkles :size="18" /><span>送10点<small>AI 点数</small></span><b>¥0</b></button>
      </section>

      <section v-if="contentSource === 'demo'" class="upsell-card single-buy">
        <strong>顺手买一件</strong>
        <button @click="showNotice('该优惠套餐暂未开放')"><span>7天SVIP 专享超低价 <b>¥5</b><del>¥49.9</del></span><span class="radio-dot"></span></button>
      </section>

      <section class="payment-card">
        <strong>支付方式</strong>
        <button :class="{ chosen: paymentMethod === 'alipay' }" @click="paymentMethod = 'alipay'"><span class="pay-logo alipay-logo">支</span>支付宝<span class="radio-dot"></span></button>
        <button :class="{ chosen: paymentMethod === 'wechat' }" @click="paymentMethod = 'wechat'"><span class="pay-logo wechat-logo">微</span>微信支付<span class="radio-dot"></span></button>
      </section>

      <section class="privilege-section">
        <div class="section-heading"><h2>SVIP尊享权益</h2><button @click="showNotice('会员权益对比暂未提供')">特权对比 <ChevronRight :size="15" /></button></div>
        <div v-for="group in content.benefitGroups" :key="group.id" class="privilege-table" :class="{ 'storage-table': group.id === 'storage-transfer' }">
          <div class="privilege-row table-head"><strong>{{ group.title }}</strong><b v-for="column in group.columns" :key="column">{{ column }}</b></div>
          <template v-for="row in group.rows" :key="row.label">
            <button v-if="row.label === 'AI点数充值'" class="privilege-row" @click="router.push({ name: 'ai-points' })"><span>{{ row.label }}</span><b v-for="(value, index) in row.values" :key="index">{{ value }}</b></button>
            <div v-else class="privilege-row"><span>{{ row.label }}</span><b v-for="(value, index) in row.values" :key="index">{{ value }}</b></div>
          </template>
        </div>
        <p class="demo-caption"><ShieldCheck :size="14" /> 页面为前端演示，支付与会员权益尚未接入</p>
      </section>
    </div>

    <footer class="commerce-paybar">
      <label><input v-model="agreed" type="checkbox" /> 我已阅读并同意 <button @click.prevent="showNotice('会员服务协议暂未提供')">《会员服务协议》</button></label>
      <button class="primary-pay" :disabled="!activePlan" @click="pay">{{ activePlan ? `立即支付 ¥${activePlan.price}${activePlan.suffix}` : "暂无可选方案" }}</button>
    </footer>
    <UnavailableFeatureDialog :open="Boolean(notice)" :message="notice" @close="notice = ''" />
  </main>
</template>
