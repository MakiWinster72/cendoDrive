<script setup lang="ts">
import { ChevronLeft, MoreHorizontal, ShieldCheck } from "@lucide/vue";

export interface OfficialAccount {
  id: string;
  name: string;
  initials: string;
  tone: "gold" | "rose" | "blue";
  unread: number;
}

defineProps<{ account: OfficialAccount }>();
const emit = defineEmits<{ back: []; notice: [message: string] }>();

function openPromo() {
  emit("notice", "该官方活动为页面示例，活动服务尚未接入");
}
</script>

<template>
  <main class="official-conversation" :class="`official-${account.tone}`">
    <header class="official-header">
      <button type="button" aria-label="返回官方账号" @click="emit('back')"><ChevronLeft /></button>
      <h1>{{ account.name }}</h1>
      <button type="button" aria-label="更多选项" @click="emit('notice', '官方账号设置暂未开放')"><MoreHorizontal /></button>
    </header>

    <section class="official-history" aria-label="官方账号消息">
      <article v-if="account.id === 'enterprise'" class="official-promo-card">
        <div class="enterprise-art" aria-hidden="true">
          <span class="art-window"><i></i><i></i><i></i><b></b><b></b></span>
          <span class="art-cloud">AI</span>
          <span class="art-spark">✦</span>
        </div>
        <div class="official-promo-copy">
          <strong>免费体验企业网盘！</strong>
          <p>超大存储空间，内外协同高效。了解更多 <span>›</span></p>
        </div>
      </article>

      <div class="official-date">09-10 16:00</div>
      <article class="official-message-row">
        <span class="official-avatar" aria-hidden="true">{{ account.initials }}</span>
        <div class="official-message-bubble">
          <template v-if="account.id === 'enterprise'">
            <strong>【库库AI】网盘资料直接办公，问答总结写稿一站搞定，免费试用：</strong>
            <button type="button" class="official-link" @click="openPromo">https://pan.baidu.com/disk/cert/kukuai?from=push</button>
          </template>
          <template v-else-if="account.id === 'assistant'">
            <strong>会员专属优惠提醒</strong>
            <p>SVIP 月卡限时优惠，点击查看活动详情。</p>
            <button type="button" class="official-cta" @click="openPromo">查看活动</button>
          </template>
          <template v-else>
            <strong>百度网盘小助手</strong>
            <p>1 分钟领取网盘 VIP 会员，更多服务持续更新。</p>
            <button type="button" class="official-cta" @click="openPromo">了解更多</button>
          </template>
        </div>
      </article>

      <template v-if="account.id === 'enterprise'">
        <div class="official-date">09-18 10:00</div>
        <button type="button" class="official-campaign" @click="openPromo">
          <div class="campaign-art" aria-hidden="true"><span>库库AI企业版</span><strong>网盘文件直接用<br />限时领5k积分</strong><i>▧</i><b>去体验　›</b></div>
          <strong>库库AI企业版送5000积分！</strong>
          <p>一站式企业AI办公工具，双席位免费试用30天～</p>
        </button>
      </template>
    </section>

    <p class="official-demo-note"><ShieldCheck :size="13" /> 官方账号消息为页面示例</p>
    <footer class="official-bottom-space" aria-hidden="true"></footer>
  </main>
</template>
