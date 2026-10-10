<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import {
  Bell,
  BriefcaseBusiness,
  ChevronLeft,
  ChevronRight,
  FileText,
  Folder,
  Heart,
  Image,
  Mail,
  MessageCircle,
  MoreHorizontal,
  Search,
  Share2,
  ShieldCheck,
  UserRound,
  UserRoundPlus,
  UsersRound,
} from "@lucide/vue";
import { useRouter } from "vue-router";
import { listRooms, type Room } from "../api/chat";
import {
  getLikedChatFiles,
  getViewedChatFiles,
  setChatFileLiked,
  type ChatFileActivity,
} from "../api/chatFileActivity";
import MobileOfficialConversation, {
  type OfficialAccount,
} from "./MobileOfficialConversation.vue";
import "../styles/share-hub.css";

type ShareTab = "messages" | "chat-files" | "file-sharing";
interface ChatFileRow extends ChatFileActivity {
  roomName: string;
}

const emit = defineEmits<{
  openMyShares: [];
  notice: [message: string];
  unreadCount: [count: number];
  officialView: [active: boolean];
}>();
const router = useRouter();
const activeTab = ref<ShareTab>("messages");
const activeOfficial = ref<OfficialAccount | null>(null);
const officialDirectory = ref(false);
const returnToDirectory = ref(false);
const membershipUnread = ref(24);
const rooms = ref<Room[]>([]);
const chatError = ref("");
const notice = ref("");
const accounts = ref<OfficialAccount[]>([
  {
    id: "assistant",
    name: "会员专属助手",
    initials: "专",
    tone: "gold",
    unread: 8,
  },
  {
    id: "drive-helper",
    name: "百度网盘小助手",
    initials: "盘",
    tone: "rose",
    unread: 16,
  },
  {
    id: "enterprise",
    name: "百度网盘企业助手",
    initials: "企",
    tone: "blue",
    unread: 0,
  },
]);
const activeFileFilter = ref<"viewed" | "liked">("viewed");
const viewedFiles = ref<ChatFileActivity[]>([]);
const likedFiles = ref<ChatFileActivity[]>([]);
const fileSearchOpen = ref(false);
const fileSearch = ref("");
const totalUnread = computed(() =>
  accounts.value.reduce((sum, account) => sum + account.unread, 0),
);
const shortcuts = [
  { label: "新建群聊", icon: UsersRound, action: "group" },
  { label: "加好友/群", icon: UserRoundPlus, action: "discover" },
  { label: "转存和订阅", icon: Share2, action: "sharing" },
  { label: "通讯录", icon: UserRound, action: "discover" },
];

function showNotice(message: string) {
  notice.value = message;
  window.setTimeout(() => {
    if (notice.value === message) notice.value = "";
  }, 2600);
}

function openShortcut(action: string) {
  if (action === "group") void router.push("/groups/new");
  else if (action === "discover") void router.push("/friends");
  else if (action === "sharing") activeTab.value = "file-sharing";
}

function refreshFileActivity() {
  viewedFiles.value = getViewedChatFiles();
  likedFiles.value = getLikedChatFiles();
}

watch(activeTab, () => {
  notice.value = "";
});

function activityRows(activity: ChatFileActivity[]) {
  return activity
    .map((item) => ({
      ...item,
      roomName:
        rooms.value.find((room) => room.id === item.roomId)?.name || "聊天文件",
    }))
    .filter(
      (item) =>
        !fileSearch.value.trim() ||
        item.name.toLowerCase().includes(fileSearch.value.trim().toLowerCase()),
    );
}

const visibleChatFiles = computed(() =>
  activeFileFilter.value === "viewed"
    ? activityRows(viewedFiles.value)
    : activityRows(likedFiles.value),
);

function isLiked(item: ChatFileRow) {
  return likedFiles.value.some(
    (file) => file.roomId === item.roomId && file.messageId === item.messageId,
  );
}

function toggleLike(item: ChatFileRow) {
  setChatFileLiked(item, !isLiked(item));
  refreshFileActivity();
  if (activeFileFilter.value === "liked") refreshFileActivity();
}

function openChatFile(item: ChatFileRow) {
  void router.push(
    `/chat/${encodeURIComponent(item.roomId)}/files/${item.messageId}`,
  );
}

function fileSize(size: number) {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function openOfficial(account: OfficialAccount) {
  returnToDirectory.value = officialDirectory.value;
  officialDirectory.value = false;
  activeOfficial.value = account;
  account.unread = 0;
  if (account.id === "assistant") membershipUnread.value = 0;
  emit("unreadCount", totalUnread.value);
  emit("officialView", true);
}

function closeOfficial() {
  activeOfficial.value = null;
  officialDirectory.value = returnToDirectory.value;
  emit("officialView", officialDirectory.value);
}

function showSharingFeature(name: string) {
  showNotice(`${name}功能暂未开放；目前可以创建单文件分享链接`);
}

onMounted(async () => {
  refreshFileActivity();
  emit("unreadCount", totalUnread.value);
  try {
    rooms.value = await listRooms();
  } catch {
    chatError.value = "聊天列表加载失败";
  }
});
</script>

<template>
  <MobileOfficialConversation
    v-if="activeOfficial"
    :account="activeOfficial"
    @back="closeOfficial"
    @notice="showNotice"
  />

  <main
    v-else-if="officialDirectory"
    class="official-directory-page"
    aria-label="官方账号"
  >
    <header class="official-directory-header">
      <button
        type="button"
        aria-label="返回消息"
        @click="
          officialDirectory = false;
          emit('officialView', false);
        "
      >
        <ChevronLeft />
      </button>
      <h1>官方账号 ({{ totalUnread }})</h1>
      <button
        type="button"
        @click="
          accounts = accounts.map((account) => ({ ...account, unread: 0 }));
          membershipUnread = 0;
          emit('unreadCount', 0);
        "
      >
        清除未读
      </button>
    </header>
    <section class="official-directory-list">
      <button
        v-for="account in accounts"
        :key="account.id"
        type="button"
        class="share-hub-message"
        @click="openOfficial(account)"
      >
        <span class="share-message-avatar" :class="account.tone"
          ><span class="official-account-glyph">{{
            account.initials
          }}</span></span
        >
        <span class="share-message-copy"
          ><span class="share-message-title"
            ><strong>{{ account.name }}</strong></span
          ><span class="share-message-subtitle">{{
            account.id === "assistant"
              ? "专属优惠！SVIP月卡超底价 ¥12"
              : account.id === "drive-helper"
                ? "1分钟领网盘VIP会员 ❗"
                : "库库AI企业版送5000积分！"
          }}</span></span
        >
        <span class="share-message-meta"
          ><time>{{
            account.id === "assistant"
              ? "10-07 12:16"
              : account.id === "drive-helper"
                ? "09-23 16:00"
                : "09-18 10:00"
          }}</time
          ><span v-if="account.unread" class="share-unread">{{
            account.unread
          }}</span></span
        >
      </button>
    </section>
  </main>

  <main v-else class="mobile-share-hub" aria-label="共享">
    <header class="share-hub-header">
      <nav class="share-hub-tabs" aria-label="共享分类">
        <button
          type="button"
          :class="{ selected: activeTab === 'messages' }"
          :aria-current="activeTab === 'messages' ? 'page' : undefined"
          @click="activeTab = 'messages'"
        >
          消息
        </button>
        <button
          type="button"
          :class="{ selected: activeTab === 'chat-files' }"
          :aria-current="activeTab === 'chat-files' ? 'page' : undefined"
          @click="activeTab = 'chat-files'"
        >
          聊天文件
        </button>
        <button
          type="button"
          :class="{ selected: activeTab === 'file-sharing' }"
          :aria-current="activeTab === 'file-sharing' ? 'page' : undefined"
          @click="activeTab = 'file-sharing'"
        >
          文件共享
        </button>
      </nav>
      <button
        v-if="activeTab === 'messages'"
        type="button"
        class="share-hub-icon"
        aria-label="搜索消息"
        @click="router.push('/friends')"
      >
        <Search />
      </button>
      <button
        v-if="activeTab === 'messages'"
        type="button"
        class="share-hub-icon"
        aria-label="查看官方账号"
        @click="
          officialDirectory = true;
          emit('officialView', true);
        "
      >
        <MoreHorizontal />
      </button>
      <button
        v-if="activeTab === 'chat-files'"
        type="button"
        class="share-hub-icon"
        :aria-label="fileSearchOpen ? '关闭搜索' : '搜索聊天文件'"
        @click="
          fileSearchOpen = !fileSearchOpen;
          fileSearch = '';
        "
      >
        <Search />
      </button>
      <button
        v-if="activeTab === 'chat-files'"
        type="button"
        class="share-hub-icon"
        aria-label="更多聊天文件选项"
        @click="showNotice('聊天文件支持最近查看和本机点赞筛选')"
      >
        <MoreHorizontal />
      </button>
    </header>

    <template v-if="activeTab === 'messages'">
      <section class="share-hub-shortcuts" aria-label="快捷入口">
        <button
          v-for="shortcut in shortcuts"
          :key="shortcut.label"
          type="button"
          @click="openShortcut(shortcut.action)"
        >
          <component :is="shortcut.icon" :stroke-width="1.8" /><span>{{
            shortcut.label
          }}</span>
        </button>
      </section>
      <section
        class="share-hub-messages message-inbox-list"
        aria-label="消息列表"
      >
        <button
          type="button"
          class="share-hub-message"
          @click="showNotice('暂无新的系统通知')"
        >
          <span class="share-message-avatar mint"
            ><Bell class="filled-bell"
          /></span>
          <span class="share-message-copy"
            ><span class="share-message-title"
              ><strong>暂无消息通知</strong></span
            ><span class="share-message-subtitle">系统通知</span></span
          >
        </button>
        <button
          type="button"
          class="share-hub-message"
          @click="openOfficial(accounts[0]!)"
        >
          <span class="share-message-avatar blue"
            ><Mail class="filled-mail"
          /></span>
          <span class="share-message-copy"
            ><span class="share-message-title"
              ><strong>专属优惠！SVIP月卡超底价 ¥12</strong></span
            ><span class="share-message-subtitle"
              >官方消息&nbsp; 丨 &nbsp;会员专属助手</span
            ></span
          >
          <span class="share-message-meta"
            ><time>10-07 12:16</time
            ><span v-if="membershipUnread" class="share-unread">{{
              membershipUnread
            }}</span></span
          >
        </button>
        <button
          type="button"
          class="share-hub-message"
          @click="router.push('/friends')"
        >
          <span class="share-message-avatar rose"><UserRoundPlus /></span>
          <span class="share-message-copy"
            ><span class="share-message-title"><strong>添加好友</strong></span
            ><span class="share-message-subtitle">独乐乐不如众乐乐</span></span
          >
          <span class="share-message-meta"><time>07-14 21:04</time></span>
        </button>
      </section>

      <div v-if="rooms.length" class="messages-section-heading">
        <span>最近聊天</span><span>好友与群聊</span>
      </div>
      <section
        v-if="rooms.length || chatError"
        class="share-hub-messages live-room-list"
        aria-label="最近聊天"
      >
        <p v-if="chatError" class="share-hub-state" role="alert">
          {{ chatError }}
        </p>
        <button
          v-for="room in rooms"
          :key="room.id"
          type="button"
          class="share-hub-message"
          @click="router.push(`/chat/${encodeURIComponent(room.id)}`)"
        >
          <span class="share-message-avatar blue"
            ><UsersRound v-if="room.group" /><UserRound v-else
          /></span>
          <span class="share-message-copy"
            ><span class="share-message-title"
              ><strong>{{ room.name }}</strong></span
            ><span class="share-message-subtitle"
              >{{ room.group ? "群聊" : "好友聊天"
              }}{{ room.description ? ` · ${room.description}` : "" }}</span
            ></span
          >
          <ChevronRight class="room-arrow" />
        </button>
      </section>
    </template>

    <template v-else-if="activeTab === 'chat-files'">
      <label v-if="fileSearchOpen" class="chat-files-search"
        ><Search :size="17" /><input
          v-model="fileSearch"
          autofocus
          placeholder="搜索聊天文件"
          aria-label="搜索聊天文件"
        /><button
          type="button"
          @click="
            fileSearchOpen = false;
            fileSearch = '';
          "
        >
          取消
        </button></label
      >
      <nav class="chat-files-filter" aria-label="聊天文件筛选">
        <button
          type="button"
          :class="{ active: activeFileFilter === 'viewed' }"
          @click="
            activeFileFilter = 'viewed';
            refreshFileActivity();
          "
        >
          最近查看
        </button>
        <button
          type="button"
          :class="{ active: activeFileFilter === 'liked' }"
          @click="
            activeFileFilter = 'liked';
            refreshFileActivity();
          "
        >
          我点赞的
        </button>
      </nav>
      <section class="chat-files-content" aria-label="聊天文件列表">
        <ul v-if="visibleChatFiles.length" class="chat-file-activity-list">
          <li
            v-for="item in visibleChatFiles"
            :key="`${item.roomId}/${item.messageId}`"
            class="chat-file-activity-row"
          >
            <button
              type="button"
              class="chat-file-open"
              @click="openChatFile(item)"
            >
              <span class="chat-file-kind"><FileText /></span>
              <span class="chat-file-copy"
                ><strong>{{ item.name }}</strong
                ><small
                  >{{ item.roomName }} · {{ fileSize(item.size) }} ·
                  {{
                    new Date(item.changedAt).toLocaleDateString("zh-CN")
                  }}</small
                ></span
              >
            </button>
            <button
              type="button"
              class="chat-file-like"
              :class="{ liked: isLiked(item) }"
              :aria-label="isLiked(item) ? '取消点赞' : '点赞聊天文件'"
              :aria-pressed="isLiked(item)"
              @click="toggleLike(item)"
            >
              <Heart :fill="isLiked(item) ? 'currentColor' : 'none'" />
            </button>
          </li>
        </ul>
        <div v-else class="share-hub-empty">
          <span class="empty-file-art"><FileText /></span>
          <p>
            {{
              activeFileFilter === "viewed"
                ? "看过的好友/群文件会在这里哟～"
                : "点赞过的聊天文件会显示在这里"
            }}
          </p>
          <small class="chat-files-local-note"
            >最近查看和点赞记录保存在当前设备</small
          >
        </div>
      </section>
    </template>

    <template v-else>
      <section class="file-sharing-page" aria-label="文件共享">
        <article class="sharing-hero">
          <h2>创建共享相册 <span>全家同步</span></h2>
          <p>旅行回忆 · 宝宝成长 · 美好永久珍藏</p>
          <div class="shared-album-art" aria-hidden="true">
            <div class="album-photo album-photo-left">
              <span>✦</span><i></i>
            </div>
            <div class="album-photo album-photo-center">
              <span>家</span><i></i>
            </div>
            <div class="album-photo album-photo-right">
              <span>♡</span><i></i>
            </div>
          </div>
          <div class="sharing-pager"><i></i><i class="active"></i><i></i></div>
        </article>

        <p class="sharing-availability">
          <ShieldCheck :size="15" /> 目前已支持单文件分享链接
        </p>
        <section class="sharing-actions" aria-label="共享功能">
          <button type="button" @click="showSharingFeature('共享文件夹')">
            <span class="sharing-action-icon folder-action"><Folder /></span
            ><span
              ><strong>创建共享文件夹</strong
              ><small>重要文件，安心互传</small></span
            ><ChevronRight />
          </button>
          <button type="button" @click="showSharingFeature('共享相册')">
            <span class="sharing-action-icon album-action"><Image /></span
            ><span
              ><strong>创建共享相册</strong
              ><small>旅行回忆，宝宝成长，美好永珍藏</small></span
            ><ChevronRight />
          </button>
          <button type="button" @click="showSharingFeature('团队空间')">
            <span class="sharing-action-icon team-action"
              ><BriefcaseBusiness /></span
            ><span
              ><strong>创建团队空间</strong
              ><small>团队高效管理协作</small></span
            ><ChevronRight />
          </button>
          <button type="button" @click="showSharingFeature('微信群共享')">
            <span class="sharing-action-icon group-action"
              ><MessageCircle /></span
            ><span
              ><strong>创建微信群共享</strong
              ><small>微信文件一键同步，永久有效</small></span
            ><ChevronRight />
          </button>
          <button
            type="button"
            class="my-share-links"
            @click="emit('openMyShares')"
          >
            <span class="sharing-action-icon links-action"><Share2 /></span
            ><span
              ><strong>我的分享链接</strong
              ><small>管理已创建的单文件分享</small></span
            ><ChevronRight />
          </button>
        </section>
      </section>
    </template>

    <p v-if="notice" class="share-hub-toast" role="status">{{ notice }}</p>
  </main>
</template>
