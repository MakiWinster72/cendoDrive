<script setup lang="ts">
import { FilePlus2, FileText, FolderPlus, Image, Music2, UploadCloud, Video, X } from 'lucide-vue-next'

defineProps<{
  open: boolean
}>()

const emit = defineEmits<{
  close: []
}>()
</script>

<template>
  <div v-if="open" class="upload-overlay" role="presentation" @click.self="emit('close')">
    <section class="upload-panel" role="dialog" aria-modal="true" aria-labelledby="upload-title">
      <header class="upload-header">
        <div>
          <span class="upload-eyebrow">CendoDrive · 文件中心</span>
          <h2 id="upload-title">上传文件</h2>
        </div>
        <button class="close-button" type="button" aria-label="关闭上传面板" @click="emit('close')">
          <X :size="20" />
        </button>
      </header>

      <div class="desktop-dropzone">
        <span class="dropzone-icon"><UploadCloud :size="28" /></span>
        <strong>把文件拖到这里</strong>
        <p>也可以选择下方的文件类型开始上传</p>
      </div>

      <div class="upload-section-heading">
        <span>选择上传内容</span>
        <small>下一步接入文件选择</small>
      </div>

      <div class="upload-options">
        <div class="upload-option option-image">
          <span><Image :size="21" /></span>
          <b>照片</b>
          <small>JPG、PNG</small>
        </div>
        <div class="upload-option option-video">
          <span><Video :size="21" /></span>
          <b>视频</b>
          <small>MP4、MOV</small>
        </div>
        <div class="upload-option option-document">
          <span><FileText :size="21" /></span>
          <b>文档</b>
          <small>PDF、Word</small>
        </div>
        <div class="upload-option option-audio">
          <span><Music2 :size="21" /></span>
          <b>音频</b>
          <small>MP3、WAV</small>
        </div>
        <div class="upload-option option-folder">
          <span><FolderPlus :size="21" /></span>
          <b>新建文件夹</b>
          <small>整理文件</small>
        </div>
        <div class="upload-option option-note">
          <span><FilePlus2 :size="21" /></span>
          <b>新建文档</b>
          <small>稍后开放</small>
        </div>
      </div>

      <footer class="upload-footer">
        <span>选择文件后，可在这里查看上传进度</span>
        <button type="button" @click="emit('close')">暂时取消</button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.upload-overlay{position:fixed;inset:0;z-index:100;display:grid;place-items:center;padding:24px;background:rgba(17,29,52,.42);backdrop-filter:blur(5px)}
.upload-panel{width:min(560px,100%);overflow:hidden;border:1px solid rgba(255,255,255,.8);border-radius:24px;background:#fff;box-shadow:0 26px 80px rgba(22,44,83,.24);color:#19243a}
.upload-header{display:flex;align-items:flex-start;justify-content:space-between;padding:28px 30px 22px;border-bottom:1px solid #edf1f7}
.upload-eyebrow{display:block;margin-bottom:8px;color:#6e7b91;font-size:11px;font-weight:700;letter-spacing:.12em;text-transform:uppercase}.upload-header h2{margin:0;font-size:25px;letter-spacing:-.03em}.close-button{display:grid;place-items:center;width:36px;height:36px;border:0;border-radius:11px;background:#f3f6fb;color:#64728a}.close-button:hover{background:#e8eef9;color:#2868ed}
.desktop-dropzone{margin:24px 30px 22px;padding:25px 20px;border:1px dashed #b8caec;border-radius:17px;background:linear-gradient(135deg,#f7faff,#eef5ff);text-align:center}.dropzone-icon{display:grid;place-items:center;width:52px;height:52px;margin:0 auto 12px;border-radius:16px;background:#dce9ff;color:#2868ed}.desktop-dropzone strong{display:block;font-size:15px}.desktop-dropzone p{margin:7px 0 0;color:#8895a9;font-size:12px}
.upload-section-heading{display:flex;align-items:center;justify-content:space-between;padding:0 30px 12px;color:#26344d;font-size:13px;font-weight:700}.upload-section-heading small{color:#a0a9b8;font-size:11px;font-weight:500}.upload-options{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;padding:0 30px 24px}.upload-option{min-height:91px;padding:14px 12px;border:1px solid #edf0f5;border-radius:15px;background:#fff}.upload-option>span{display:grid;place-items:center;width:34px;height:34px;margin-bottom:8px;border-radius:10px}.upload-option b,.upload-option small{display:block}.upload-option b{font-size:12px}.upload-option small{margin-top:3px;color:#9aa5b5;font-size:10px}.option-image>span{background:#e7f1ff;color:#4285ed}.option-video>span{background:#e9f8f0;color:#21a96a}.option-document>span{background:#fff0e7;color:#ef8238}.option-audio>span{background:#f0ebff;color:#8469df}.option-folder>span{background:#fff7de;color:#d69921}.option-note>span{background:#f1f3f6;color:#8e98a8}
.upload-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 30px;background:#fafbfd;color:#8995a8;font-size:11px}.upload-footer button{height:34px;padding:0 15px;border:1px solid #dce4f1;border-radius:9px;background:#fff;color:#3d6ec9;font-size:12px;font-weight:600}.upload-footer button:hover{border-color:#9db9ef;background:#f5f8ff}
@media(max-width:700px){.upload-overlay{align-items:end;padding:0;background:rgba(17,29,52,.48)}.upload-panel{width:100%;border-radius:25px 25px 0 0;box-shadow:0 -18px 55px rgba(22,44,83,.18);animation:upload-sheet-in .22s ease-out}.upload-header{padding:22px 22px 17px}.upload-header h2{font-size:22px}.desktop-dropzone{display:none}.upload-section-heading{padding:20px 22px 12px}.upload-options{grid-template-columns:repeat(3,1fr);gap:9px;padding:0 22px 20px}.upload-option{min-height:84px;padding:12px 9px}.upload-footer{padding:14px 22px max(14px,env(safe-area-inset-bottom));margin:0}.upload-footer span{max-width:200px;line-height:1.5}}
@keyframes upload-sheet-in{from{transform:translateY(22px);opacity:.6}to{transform:translateY(0);opacity:1}}
@media(prefers-reduced-motion:reduce){.upload-panel{animation:none}}
</style>
