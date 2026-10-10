<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ChevronLeft, ChevronRight, Ellipsis, X } from "@lucide/vue";
import { useRouter } from "vue-router";
import UnavailableFeatureDialog from "../components/UnavailableFeatureDialog.vue";
import { getNovelHubContent, type NovelContentItem } from "../api/content";
import { demoNovelHubContent } from "../api/contentDemo";
import "../styles/mobile-media.css";

const router = useRouter();
const notice = ref("");
const contentSource = ref<"demo" | "backend">("demo");
const demoTones = ["ink", "mist", "rose", "night", "sea", "violet", "sunset"];
type DisplayBook = NovelContentItem & { tone?: string };
const recommendationSection = ref({
  id: demoNovelHubContent.sections[0]!.id,
  title: demoNovelHubContent.sections[0]!.title,
  books: demoNovelHubContent.sections[0]!.books.map((book, index) => ({
    ...book,
    tone: demoTones[index],
  })),
});
const hotReadSection = ref({
  id: demoNovelHubContent.sections[1]!.id,
  title: demoNovelHubContent.sections[1]!.title,
  books: demoNovelHubContent.sections[1]!.books.map((book, index) => ({
    ...book,
    tone: demoTones[index + 4],
  })),
});

onMounted(async () => {
  try {
    const content = await getNovelHubContent();
    const sections = content.sections;
    const featured =
      sections.find((section) => section.id === "you-may-like") ?? sections[0];
    const popular =
      sections.find((section) => section.id === "male-popular") ?? sections[1];
    recommendationSection.value = featured
      ? {
          ...featured,
          books: featured.books.map((book, index) => ({
            ...book,
            tone: demoTones[index % demoTones.length],
          })),
        }
      : { id: "you-may-like", title: "你可能在找", books: [] };
    hotReadSection.value = popular
      ? {
          ...popular,
          books: popular.books.map((book, index) => ({
            ...book,
            tone: demoTones[(index + 4) % demoTones.length],
          })),
        }
      : { id: "male-popular", title: "男生热读", books: [] };
    contentSource.value = "backend";
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
function openBook(targetUrl: string | null) {
  if (!targetUrl) {
    showNotice("小说详情及阅读服务暂未接入");
    return;
  }
  try {
    const target = new URL(targetUrl, window.location.origin);
    if (target.origin === window.location.origin) {
      void router.push(`${target.pathname}${target.search}${target.hash}`);
    } else if (target.protocol === "https:") {
      window.open(target.href, "_blank", "noopener,noreferrer");
    } else {
      showNotice("小说跳转地址无效");
    }
  } catch {
    showNotice("小说跳转地址无效");
  }
}
</script>

<template>
  <main class="media-hub novel-hub">
    <header class="media-header novel-header">
      <button class="novel-back" aria-label="返回" @click="goBack">
        <ChevronLeft />
      </button>
      <h1>小说</h1>
      <div class="novel-head-actions">
        <button aria-label="更多" @click="showNotice('更多功能暂未开放')">
          <Ellipsis /></button
        ><button aria-label="关闭" @click="goBack"><X /></button>
      </div>
    </header>

    <div class="media-scroll novel-scroll">
      <section class="novel-local-section">
        <h2>网盘和本地小说</h2>
        <button
          class="novel-empty-action"
          @click="showNotice('小说导入功能暂未开放')"
        >
          暂无内容，点击添加小说 <ChevronRight :size="16" />
        </button>
      </section>

      <section class="novel-bookshelf-section">
        <h2>书城书架</h2>
        <button
          class="novel-empty-action"
          @click="showNotice('书城内容暂未接入')"
        >
          暂无内容，去书城看看 <ChevronRight :size="16" />
        </button>
      </section>

      <div class="novel-divider"></div>
      <button
        v-if="contentSource === 'demo'"
        class="novel-promo"
        aria-label="小说推荐活动"
        @click="showNotice('书城活动暂未开放')"
      >
        <span>云端阅读 · 好书常伴</span><i>BOOKS</i>
      </button>

      <section class="novel-recommend-section">
        <h2>
          {{ recommendationSection.title }}
          <small v-if="contentSource === 'demo'" class="novel-demo-tag"
            >演示推荐</small
          >
        </h2>
        <p v-if="contentSource === 'demo'" class="novel-quote">
          “ 爆款小说免费读，真香预警！”
        </p>
        <div class="novel-card-strip">
          <button
            v-for="book in recommendationSection.books"
            :key="book.id"
            class="novel-book-card"
            @click="openBook(book.targetUrl)"
          >
            <span
              class="novel-cover"
              :class="book.tone"
              :style="
                book.coverUrl
                  ? { backgroundImage: `url(${book.coverUrl})` }
                  : undefined
              "
              ><i>HOT</i><b>{{ book.title }}</b
              ><small>云端精选</small></span
            >
            <strong>{{ book.title }}</strong
            ><small>{{ book.author }}</small>
          </button>
          <p
            v-if="!recommendationSection.books.length"
            class="novel-list-empty"
          >
            暂无小说推荐
          </p>
        </div>
      </section>

      <section class="novel-recommend-section hot-read-section">
        <h2>{{ hotReadSection.title }}</h2>
        <div class="novel-card-strip">
          <button
            v-for="book in hotReadSection.books"
            :key="book.id"
            class="novel-book-card"
            @click="openBook(book.targetUrl)"
          >
            <span
              class="novel-cover"
              :class="book.tone"
              :style="
                book.coverUrl
                  ? { backgroundImage: `url(${book.coverUrl})` }
                  : undefined
              "
              ><i>推荐</i><b>{{ book.title }}</b
              ><small>云端精选</small></span
            >
            <strong>{{ book.title }}</strong
            ><small>{{ book.author }}</small>
          </button>
          <p v-if="!hotReadSection.books.length" class="novel-list-empty">
            暂无小说推荐
          </p>
        </div>
      </section>
      <p class="media-demo-note">
        {{
          contentSource === "demo"
            ? "小说内容接口暂未接入，当前推荐为演示数据"
            : "小说展示数据由内容接口返回；书籍阅读服务尚未接入"
        }}
      </p>
    </div>
    <UnavailableFeatureDialog
      :open="Boolean(notice)"
      :message="notice"
      @close="notice = ''"
    />
  </main>
</template>
