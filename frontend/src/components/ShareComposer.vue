<script setup lang="ts">
import { ChevronLeft, ChevronRight, CircleHelp, Download, Link2, LockKeyhole, QrCode, RefreshCw, UsersRound, X } from "@lucide/vue";
import type { DriveItem } from "../stores/drive";
import { iconForFile } from "./fileIcon";
import ShareChannelIcon from "./ShareChannelIcon.vue";
import ShareFriendPicker from "./ShareFriendPicker.vue";
import { useShareComposer } from "./useShareComposer";
const props = defineProps<{ file: DriveItem }>();
const emit = defineEmits<{ close: [] }>();
const {
  dialog, fileArtwork, visible, view, busy, friendBusy, useCode, autoFill, days,
  extractionCode, codeDraft, codeError, error, toast, posterUrl, actionsDisabled,
  expiry, customDays, channels, randomCode, notify, placeholder, copy, openApp,
  showQr, moments, savePoster, channelClick, close, back, cancel, editCode,
  saveCode, selectExpiry, confirmCustomDays, focusPage,
} = useShareComposer(props.file);
</script>

<template>
  <dialog ref="dialog" class="share-composer" aria-labelledby="share-composer-title" @cancel.prevent="cancel">
    <Transition name="share-stage" @after-leave="emit('close')">
      <div v-if="visible" class="share-stage" :class="{ 'is-qr': view === 'qr' }" @click.self="cancel">
        <Transition name="share-page" mode="out-in" @after-enter="focusPage">
          <section v-if="view === 'qr'" key="qr" class="qr-overlay" aria-label="分享二维码">
            <h2 id="share-composer-title" class="sr-only">分享二维码</h2>
            <div class="share-qr-card">
              <img class="share-qr-poster" :src="posterUrl" :alt="`${file.name}的分享二维码，${expiry}，可长按保存`" />
              <div class="share-qr-actions">
                <button type="button" aria-label="微信分享二维码" class="qr-wechat" :disabled="actionsDisabled" @click="openApp('wechat')"><ShareChannelIcon channel="wechat"/></button>
                <button type="button" aria-label="朋友圈分享二维码" class="qr-moments" :disabled="actionsDisabled" @click="moments"><ShareChannelIcon channel="moments"/></button>
                <button type="button" aria-label="保存二维码到相册" class="qr-download" :disabled="actionsDisabled" @click="savePoster"><Download :size="28" :stroke-width="3"/></button>
              </div>
            </div>
            <button class="qr-close" type="button" aria-label="返回分享" :disabled="actionsDisabled" @click="back"><X :size="27"/></button>
            <p v-if="error" class="share-error qr-error" role="alert">{{ error }}</p>
          </section>
          <section v-else :key="view" class="share-sheet">
            <header class="share-sheet-header">
              <button v-if="view !== 'link'" type="button" aria-label="返回分享" :disabled="actionsDisabled" class="share-back" @click="back"><ChevronLeft :size="23"/></button>
              <h2 id="share-composer-title">{{ { link: '分享', friends: '选择好友', expiry: '有效期', code: '提取码' }[view] }}</h2>
              <button type="button" class="share-close" aria-label="关闭分享" :disabled="actionsDisabled" @click="close"><X :size="22"/></button>
            </header>
            <div v-if="view === 'link'" class="share-sheet-body">
              <div class="share-channel-row" aria-label="分享渠道">
                <button v-for="channel in channels" :key="channel.channel" type="button" :disabled="actionsDisabled" :data-action="channel.channel" @click="channelClick(channel.channel)"><span class="share-action-circle" :class="channel.channel"><ShareChannelIcon :channel="channel.channel"/></span><span>{{ channel.label }}</span></button>
              </div>
              <div class="share-channel-row share-tool-row" aria-label="分享操作">
                <button type="button" data-action="copy" :disabled="actionsDisabled" @click="copy"><span class="share-action-circle"><Link2 :size="26"/></span><span>复制链接</span></button>
                <button type="button" data-action="qr" :disabled="actionsDisabled" @click="showQr"><span class="share-action-circle"><QrCode :size="26"/></span><span>生成二维码</span></button>
                <button type="button" data-action="friends" :disabled="actionsDisabled" @click="view = 'friends'"><span class="share-action-circle friend-action"><UsersRound :size="26"/></span><span>共享</span></button>
                <button type="button" data-action="secret" :disabled="actionsDisabled" @click="placeholder"><span class="share-action-circle"><LockKeyhole :size="26"/></span><span>密享</span></button>
              </div>
              <div class="share-preferences">
                <button type="button" class="share-setting" :disabled="actionsDisabled" @click="view = 'expiry'"><span>有效期</span><span class="setting-value">{{ expiry }}<ChevronRight :size="18"/></span></button>
                <label class="share-setting"><span>使用提取码</span><input v-model="useCode" class="share-switch" type="checkbox" role="switch" :disabled="actionsDisabled" aria-label="使用提取码"/></label>
                <label v-if="useCode" class="share-setting"><span>分享链接自动填充提取码<button type="button" class="code-help" aria-label="自动填充提取码说明" @click.prevent="notify('开启后，打开链接无需手动输入提取码；请勿向不可信的人分享')"><CircleHelp :size="16"/></button></span><input v-model="autoFill" class="share-switch" type="checkbox" role="switch" :disabled="actionsDisabled" aria-label="分享链接自动填充提取码"/></label>
                <button v-if="useCode" type="button" class="share-setting" :disabled="actionsDisabled" @click="editCode"><span>随机生成提取码</span><span class="setting-value">{{ extractionCode }}<ChevronRight :size="18"/></span></button>
                <button type="button" class="share-setting" :disabled="actionsDisabled" @click="placeholder"><span>群名片</span><span class="setting-muted">未添加<ChevronRight :size="18"/></span></button>
                <button type="button" class="share-setting" :disabled="actionsDisabled" @click="placeholder"><span>分享皮肤 <span class="share-vip">SVIP</span></span><span class="setting-dark">设置分享页皮肤<ChevronRight :size="18"/></span></button>
              </div>
              <p v-if="busy" class="share-working" role="status">正在准备分享…</p>
              <p v-if="error" class="share-error" role="alert">{{ error }}</p>
              <footer class="share-disclaimer">请勿侵犯他人合法权益，严禁传播违法内容</footer>
            </div>
            <ShareFriendPicker v-else-if="view === 'friends'" :file="file" @busy="friendBusy = $event" @sent="view = 'link'; notify(`已发送给${$event}`)"/>
            <div v-else-if="view === 'expiry'" class="share-subpage">
              <button v-for="option in [0, 1, 7, 30]" :key="option" type="button" class="expiry-option" :aria-pressed="days === option" @click="selectExpiry(option)"><span>{{ option === 0 ? '永久有效' : `${option}天有效` }}</span><span v-if="days === option" class="selected-check">✓</span></button>
              <form class="custom-expiry" @submit.prevent="confirmCustomDays"><label for="share-custom-days">自定义有效期</label><div><input id="share-custom-days" v-model.number="customDays" type="number" min="1" max="30" required/><span>天</span><button type="submit">确定</button></div><small>1–30天，设置后下次操作会创建新链接，旧链接不变。</small></form>
              <p v-if="error" class="share-error" role="alert">{{ error }}</p>
            </div>
            <form v-else class="share-subpage code-form" @submit.prevent="saveCode">
              <label for="share-extraction-code">设置提取码</label><div><input id="share-extraction-code" v-model="codeDraft" maxlength="16" autocomplete="off" spellcheck="false" aria-describedby="code-hint"/><button type="button" aria-label="重新生成提取码" @click="codeDraft = randomCode()"><RefreshCw :size="18"/></button></div>
              <small id="code-hint">4–16位字母或数字，修改后下次操作会创建新链接。</small><p v-if="codeError" class="share-error" role="alert">{{ codeError }}</p><button class="share-primary" type="submit">完成</button>
            </form>
          </section>
        </Transition>
        <Transition name="share-toast"><div v-if="toast" class="share-toast" role="status" aria-live="polite">{{ toast }}</div></Transition>
      </div>
    </Transition>
    <span ref="fileArtwork" hidden><svg v-if="/\.(zip|rar|7z|tar|gz|bz2|xz|tgz)$/i.test(file.name)" viewBox="0 0 48 48" fill="none" aria-hidden="true"><rect x="3" y="2" width="42" height="44" rx="3" fill="#4dc8f4"/><path d="M3 17h42v15H3z" fill="#ff5e62"/><path d="M3 32h42v11a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3z" fill="#80d133"/><path d="M19 2h10v44H19z" fill="#ffb341"/><rect x="18" y="20" width="12" height="10" rx="1" fill="#fff"/><rect x="20" y="22" width="8" height="6" fill="#ffb341"/></svg><component v-else :is="iconForFile(file)" :size="48"/></span>
  </dialog>
</template>

<style scoped src="../styles/share-composer.css"></style>
