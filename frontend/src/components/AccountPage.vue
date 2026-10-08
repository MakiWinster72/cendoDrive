<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { Camera, ChevronLeft, UserRound } from "lucide-vue-next";
import * as api from "../api/users";
import { useAuth } from "../stores/auth";
import "../styles/account.css";
const emit=defineEmits<{ back: []; changed: []; signedOut: [reason: "password" | "deletion", purgeAfter?: string] }>();
const auth=useAuth();
const profile=ref<api.UserProfile|null>(null),nickname=ref(""),avatarUrl=ref(""),busy=ref(false),loading=ref(true);
const error=ref(""),notice=ref(""),currentPassword=ref(""),newPassword=ref(""),repeatPassword=ref("");
const deleting=ref(false),deletePassword=ref(""),confirmation=ref(""),page=ref<HTMLElement|null>(null);
let alive=true;
const previousOverflow=document.body.style.overflow;
async function loadAvatar() {
  if (!profile.value?.hasAvatar) { if (avatarUrl.value) URL.revokeObjectURL(avatarUrl.value); avatarUrl.value=""; return; }
  const blob=await api.getAvatar();
  if (!alive) return;
  if (avatarUrl.value) URL.revokeObjectURL(avatarUrl.value);
  avatarUrl.value=URL.createObjectURL(blob);
}
async function load() {
  loading.value=true; error.value="";
  try { const value=await api.getProfile(); if (!alive) return; profile.value=value; nickname.value=value.nickname; await loadAvatar(); }
  catch (e) { if (alive) error.value=api.accountError(e,"个人资料加载失败，请重试"); }
  finally { if (alive) loading.value=false; }
}
async function run(action: ()=>Promise<void>) {
  if (busy.value) return;
  busy.value=true; error.value=""; notice.value="";
  try { await action(); } catch (e) { if (alive) error.value=api.accountError(e,"操作失败，请稍后重试"); }
  finally { if (alive) busy.value=false; }
}
async function save() {
  const name=nickname.value.trim();
  if (!name || name.length>64) { error.value="请输入 1–64 个字符的昵称"; return; }
  await run(async ()=>{
    const value=await api.updateProfile(name); if (!alive) return;
    profile.value=value; nickname.value=value.nickname; auth.updateNickname(value.nickname);
    notice.value="个人资料已保存"; emit("changed");
  });
}
async function selectAvatar(event: Event) {
  const input=event.target as HTMLInputElement,file=input.files?.[0]; input.value="";
  if (!file) return;
  if (!["image/png","image/jpeg"].includes(file.type) || file.size>2*1024*1024) {
    error.value="请选择不超过 2 MiB 的 PNG 或 JPEG 图片"; return;
  }
  await run(async ()=>{ await api.uploadAvatar(file); if (!alive) return; if (profile.value) profile.value.hasAvatar=true;
    await loadAvatar(); if (!alive) return; notice.value="头像已更新"; emit("changed"); });
}
async function removeAvatar() {
  await run(async ()=>{ await api.removeAvatar(); if (!alive) return; if (profile.value) profile.value.hasAvatar=false;
    await loadAvatar(); notice.value="头像已移除"; emit("changed"); });
}
async function password() {
  if (newPassword.value!==repeatPassword.value) { error.value="两次输入的新密码不一致"; return; }
  if (newPassword.value.length<8 || new TextEncoder().encode(newPassword.value).length>72) { error.value="新密码需要 8–72 个字符，且不超过 72 个 UTF-8 字节"; return; }
  await run(async ()=>{ await api.changePassword(currentPassword.value,newPassword.value); if (!alive) return;
    currentPassword.value=newPassword.value=repeatPassword.value=""; emit("signedOut","password"); });
}
async function deletion() {
  if (confirmation.value!=="注销账号" || !deletePassword.value) { error.value="请验证密码并输入“注销账号”"; return; }
  await run(async ()=>{ const value=await api.deleteAccount(deletePassword.value,confirmation.value); if (!alive) return;
    deletePassword.value=""; emit("signedOut","deletion",value.purgeAfter); });
}
function back() { if (!busy.value) emit("back"); }
function keydown(event: KeyboardEvent) { if (event.key==="Escape") { event.preventDefault(); back(); } }
onMounted(()=>{ document.body.style.overflow="hidden"; page.value?.focus(); void load(); });
onUnmounted(()=>{ alive=false; if (avatarUrl.value) URL.revokeObjectURL(avatarUrl.value); document.body.style.overflow=previousOverflow; });
watch(deleting,async value=>{
  await nextTick();
  if (!alive) return;
  if (value) page.value?.querySelector<HTMLInputElement>("#delete-password")?.focus();
  else page.value?.querySelector<HTMLButtonElement>(".account-danger > button")?.focus();
});
</script>

<template>
  <main ref="page" class="account-page" tabindex="-1" aria-labelledby="account-title" @keydown="keydown">
    <header class="account-top">
      <button class="account-back" type="button" aria-label="返回文件" :disabled="busy" @click="back"><ChevronLeft :size="22" /></button>
      <h1 id="account-title">账号管理</h1>
      <span class="account-header-spacer" aria-hidden="true"></span>
    </header>
    <div class="account-body">
      <p v-if="error" class="account-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="account-notice" role="status">{{ notice }}</p>
      <p v-if="loading" class="account-loading" role="status">正在加载个人资料…</p>
      <button v-else-if="!profile" class="account-primary" type="button" @click="load">重新加载</button>
      <template v-else>
        <section class="account-card account-profile" aria-labelledby="profile-title">
          <h2 id="profile-title">个人资料</h2>
          <div class="account-profile-layout">
            <div class="account-profile-summary">
              <div class="account-identity">
                <div class="account-avatar"><img v-if="avatarUrl" :src="avatarUrl" alt="个人头像"><UserRound v-else :size="32" /></div>
                <div><strong>{{ profile.nickname }}</strong><p>个人资料与账号安全</p></div>
              </div>
              <div class="account-avatar-actions">
                <label class="account-upload"><Camera :size="17" />更换头像<input aria-label="上传头像" type="file" accept="image/png,image/jpeg" :disabled="busy" @change="selectAvatar"></label>
                <button v-if="profile.hasAvatar" :disabled="busy" type="button" @click="removeAvatar">移除头像</button>
              </div>
              <p class="account-hint account-avatar-hint">PNG / JPEG，最多 2 MiB、2048×2048；保存为 256×256 PNG，不保留原图元数据。</p>
            </div>
            <div class="account-profile-details">
              <dl class="account-info">
                <div><dt>用户名：</dt><dd>{{ profile.username }}</dd></div>
                <div><dt>用户 ID：</dt><dd><code>{{ profile.id }}</code></dd></div>
              </dl>
              <form class="account-form" @submit.prevent="save">
                <div class="account-field"><label for="nickname">昵称</label><input id="nickname" v-model="nickname" maxlength="64" required :disabled="busy" placeholder="输入昵称"></div>
                <p class="account-hint">昵称可以修改；用户名和用户 ID 不变。</p>
                <button class="account-primary" :disabled="busy || loading">{{ busy ? '处理中…' : '保存资料' }}</button>
              </form>
            </div>
          </div>
        </section>
        <section class="account-card" aria-labelledby="password-title">
          <h2 id="password-title">账号安全</h2>
          <p class="account-hint">修改密码后，所有设备的旧会话都会失效，需重新登录。</p>
          <form class="account-form" @submit.prevent="password">
            <div class="account-field"><label for="current-password">当前密码</label><input id="current-password" v-model="currentPassword" type="password" autocomplete="current-password" maxlength="128" required :disabled="busy" placeholder="输入当前密码"></div>
            <div class="account-field"><label for="new-password">新密码</label><input id="new-password" v-model="newPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" required :disabled="busy" placeholder="输入新密码"></div>
            <div class="account-field"><label for="repeat-password">再次输入新密码</label><input id="repeat-password" v-model="repeatPassword" type="password" autocomplete="new-password" required :disabled="busy" placeholder="再次确认新密码"></div>
            <p class="account-hint">8–72 个字符，且不超过 72 个 UTF-8 字节。</p>
            <button class="account-primary" :disabled="busy">修改密码并退出</button>
          </form>
        </section>
        <section class="account-card account-danger" aria-labelledby="deletion-title">
          <h2 id="deletion-title">注销账号</h2>
          <p>注销后立即退出所有设备、撤销分享并隐藏用户身份。账号和文件先保留 7 天，之后彻底删除。</p>
          <p class="account-hint">7 天内可在登录页验证原密码恢复；旧分享和旧会话不会恢复。到期后不能恢复，清理失败会自动重试。</p>
          <button v-if="!deleting" class="account-danger-button" :disabled="busy" type="button" @click="deleting=true">申请注销账号</button>
          <form v-else class="account-form" @submit.prevent="deletion">
            <div class="account-field"><label for="delete-password">验证当前密码</label><input id="delete-password" v-model="deletePassword" type="password" autocomplete="current-password" required :disabled="busy"></div>
            <div class="account-field"><label for="delete-confirmation">输入“注销账号”确认</label><input id="delete-confirmation" v-model="confirmation" required :disabled="busy" autocomplete="off"></div>
            <div class="account-delete-actions"><button class="account-danger-button" :disabled="busy || confirmation!=='注销账号'">确认注销，7 天后彻底删除</button><button type="button" :disabled="busy" @click="deleting=false;deletePassword='';confirmation=''">取消</button></div>
          </form>
        </section>
      </template>
    </div>
  </main>
</template>
