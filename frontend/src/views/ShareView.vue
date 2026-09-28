<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Download, File, Folder, ShieldCheck } from 'lucide-vue-next'
import BrandLogo from '../components/BrandLogo.vue'
import { formatSize, useDrive } from '../stores/drive'

const route = useRoute(), drive = useDrive(), code = ref(''), verified = ref(false), error = ref('')
const share = computed(() => drive.state.shares.find((item) => item.id === route.params.id))
const file = computed(() => share.value ? drive.get(share.value.itemId) : undefined)
const invalid = computed(() => !share.value || share.value.cancelled || new Date(share.value.expiresAt) <= new Date() || !file.value || file.value.deletedAt)
function verify() { error.value = ''; if (invalid.value) return; if (code.value !== share.value?.code) return void (error.value = '提取码不正确'); verified.value = true }
function download() { if (!file.value || file.value.kind === 'folder') return; const blob = new Blob([`CendoDrive Mock 文件：${file.value.name}`]); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = file.value.name; link.click(); URL.revokeObjectURL(link.href) }
</script>

<template>
  <main class="share-page"><header><BrandLogo /></header><section class="share-card">
    <template v-if="invalid"><div class="status-icon">!</div><h1>分享已失效</h1><p>分享可能已过期、被取消或文件已删除。</p><RouterLink to="/">返回首页</RouterLink></template>
    <template v-else-if="!verified"><ShieldCheck :size="48" /><h1>访问分享文件</h1><p>请输入四位提取码</p><form @submit.prevent="verify"><input v-model="code" maxlength="4" placeholder="提取码" autofocus /><button>提取文件</button></form><small v-if="error">{{ error }}</small></template>
    <template v-else><component :is="file?.kind === 'folder' ? Folder : File" :size="56" /><h1>{{ file?.name }}</h1><p>{{ formatSize(file?.size || 0) }} · 有效期至 {{ new Date(share!.expiresAt).toLocaleDateString('zh-CN') }}</p><button class="download" :disabled="file?.kind === 'folder'" @click="download"><Download :size="18" />{{ file?.kind === 'folder' ? '文件夹暂不支持下载' : '下载文件' }}</button></template>
  </section></main>
</template>

<style scoped>
.share-page{min-height:100vh;background:linear-gradient(135deg,#eef4ff,#f9fbff);padding:0 24px}.share-page header{height:72px;display:flex;align-items:center;max-width:1100px;margin:auto}.share-card{width:min(460px,100%);min-height:340px;margin:10vh auto 0;padding:46px 38px;border-radius:18px;background:#fff;box-shadow:0 18px 60px rgba(42,74,130,.14);display:flex;flex-direction:column;align-items:center;text-align:center;color:#316cff}.share-card h1{margin:18px 0 8px;color:#263247;font-size:23px}.share-card p{margin:0 0 26px;color:#8993a2;font-size:13px}.share-card form{width:100%;display:flex;gap:10px}.share-card input{flex:1;min-width:0;height:44px;border:1px solid #dce1e8;border-radius:8px;padding:0 13px;outline:0}.share-card input:focus{border-color:#316cff}.share-card button,.share-card a{height:44px;border:0;border-radius:8px;background:#316cff;color:#fff;padding:0 20px;display:inline-flex;align-items:center;justify-content:center;gap:7px}.share-card small{color:#ef4444;margin-top:13px}.status-icon{width:52px;height:52px;border-radius:50%;display:grid;place-items:center;background:#fff0f0;color:#f05252;font-size:28px;font-weight:700}.download:disabled{opacity:.55}
</style>
