<script setup lang="ts">
import { ref } from "vue";
import { ChevronLeft, ChevronRight, Ellipsis, X } from "lucide-vue-next";
import { useRouter } from "vue-router";
import UnavailableFeatureDialog from "../components/UnavailableFeatureDialog.vue";
import "../styles/mobile-media.css";

const router = useRouter();
const notice = ref("");
const recommendations = [
  { title: "我，修仙，一开始就无敌", author: "画江山", tone: "ink" },
  { title: "替嫁宠妃：残疾大佬…", author: "糖果可可", tone: "mist" },
  { title: "我的绝美特工老婆", author: "程以武", tone: "rose" },
  { title: "二婚嫁京圈大佬，渣…", author: "程以武", tone: "night" },
];
const hotReads = [
  { title: "山海拾遗", author: "云上行", tone: "sea" },
  { title: "长夜有星", author: "青禾", tone: "violet" },
  { title: "风起人间", author: "南枝", tone: "sunset" },
];

function goBack() {
  if (window.history.state?.back) router.back();
  else void router.replace({ name: "home" });
}
function showNotice(message: string) {
  notice.value = message;
}
</script>

<template>
  <main class="media-hub novel-hub">
    <header class="media-header novel-header">
      <button class="novel-back" aria-label="返回" @click="goBack"><ChevronLeft /></button>
      <h1>小说</h1>
      <div class="novel-head-actions"><button aria-label="更多" @click="showNotice('更多功能暂未开放')"><Ellipsis /></button><button aria-label="关闭" @click="goBack"><X /></button></div>
    </header>

    <div class="media-scroll novel-scroll">
      <section class="novel-local-section">
        <h2>网盘和本地小说</h2>
        <button class="novel-empty-action" @click="showNotice('小说导入功能暂未开放')">暂无内容，点击添加小说 <ChevronRight :size="16" /></button>
      </section>

      <section class="novel-bookshelf-section">
        <h2>书城书架</h2>
        <button class="novel-empty-action" @click="showNotice('书城内容暂未接入')">暂无内容，去书城看看 <ChevronRight :size="16" /></button>
      </section>

      <div class="novel-divider"></div>
      <button class="novel-promo" aria-label="小说推荐活动" @click="showNotice('书城活动暂未开放')"><span>云端阅读 · 好书常伴</span><i>BOOKS</i></button>

      <section class="novel-recommend-section">
        <h2>你可能在找 <small class="novel-demo-tag">演示推荐</small></h2>
        <p class="novel-quote">“ 爆款小说免费读，真香预警！”</p>
        <div class="novel-card-strip">
          <button v-for="book in recommendations" :key="book.title" class="novel-book-card" @click="showNotice('书籍详情暂未接入')">
            <span class="novel-cover" :class="book.tone"><i>HOT</i><b>{{ book.title }}</b><small>云端精选</small></span>
            <strong>{{ book.title }}</strong><small>{{ book.author }}</small>
          </button>
        </div>
      </section>

      <section class="novel-recommend-section hot-read-section">
        <h2>男生热读</h2>
        <div class="novel-card-strip">
          <button v-for="book in hotReads" :key="book.title" class="novel-book-card" @click="showNotice('书籍详情暂未接入')">
            <span class="novel-cover" :class="book.tone"><i>推荐</i><b>{{ book.title }}</b><small>云端精选</small></span>
            <strong>{{ book.title }}</strong><small>{{ book.author }}</small>
          </button>
        </div>
      </section>
      <p class="media-demo-note">书城推荐为页面演示内容，书籍数据及阅读服务尚未接入</p>
    </div>
    <UnavailableFeatureDialog :open="Boolean(notice)" :message="notice" @close="notice = ''" />
  </main>
</template>
