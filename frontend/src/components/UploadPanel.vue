<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { FilePlus2, FileText, FolderPlus, Image, Music2, Trash2, UploadCloud, Video, X } from 'lucide-vue-next'

interface FolderOption { id: string | null; name: string }

const props = withDefaults(defineProps<{
  open: boolean
  folderOptions?: FolderOption[]
  initialFolderId?: string | null
}>(), {
  folderOptions: () => [{ id: null, name: 'Ula' }],
  initialFolderId: null,
})

const emit = defineEmits<{
  close: []
  upload: [files: File[], folderId: string | null]
}>()
type UploadType = 'image' | 'video' | 'document' | 'audio' | 'other'
interface SelectedFile { id: string; file: File }

const fileInput = ref<HTMLInputElement>()
const accept = ref('*/*')
const selectedFiles = ref<SelectedFile[]>([])
const selectedType = ref<UploadType | null>(null)
const selectedFolderId = ref<string | null>(props.initialFolderId)
const folderPickerOpen = ref(false)
const selectedTypeName = computed(() => ({ image: '照片', video: '视频', document: '文档', audio: '音频', other: '其他文件' }[selectedType.value || 'other']))
const selectedFolderName = computed(() => props.folderOptions.find((folder) => folder.id === selectedFolderId.value)?.name || props.folderOptions[0]?.name || 'Ula')

async function chooseType(type: UploadType, typeAccept: string) {
  selectedType.value = type
  accept.value = typeAccept
  await nextTick()
  fileInput.value?.click()
}

function handleFileSelection(event: Event) {
  const input = event.target as HTMLInputElement
  const files = [...(input.files || [])]
  selectedFiles.value.push(...files.map((file, index) => ({ id: `${file.name}-${file.lastModified}-${Date.now()}-${index}`, file })))
  input.value = ''
}

function removeFile(fileId: string) {
  selectedFiles.value = selectedFiles.value.filter(({ id }) => id !== fileId)
}

function formatSize(size: number) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function fileType(file: File) {
  if (file.type) return file.type.split('/').pop()?.toUpperCase() || file.type
  return file.name.split('.').pop()?.toUpperCase() || '文件'
}

function selectFolder(folderId: string | null) {
  selectedFolderId.value = folderId
  folderPickerOpen.value = false
}

function startUpload() {
  if (!selectedFiles.value.length) return
  emit('upload', selectedFiles.value.map(({ file }) => file), selectedFolderId.value)
}
</script>

<template>
  <div v-if="open" class="upload-overlay" role="presentation" @click.self="emit('close')">
    <section class="upload-panel" role="dialog" aria-modal="true" aria-labelledby="upload-title">
      <header class="upload-header">
        <div><span class="upload-eyebrow">CendoDrive · 文件中心</span><h2 id="upload-title">上传文件</h2></div>
        <button class="close-button" type="button" aria-label="关闭上传面板" @click="emit('close')"><X :size="20" /></button>
      </header>

      <div class="desktop-dropzone"><span class="dropzone-icon"><UploadCloud :size="28" /></span><strong>把文件拖到这里</strong><p>也可以选择下方的文件类型开始上传</p></div>

      <div class="upload-section-heading"><span>选择上传内容</span><small>可多选文件</small></div>
      <div class="upload-options">
        <button class="upload-option option-image" type="button" @click="chooseType('image', '.jpg,.jpeg,.png,.gif,.webp,.bmp,.svg')"><span><Image :size="21" /></span><b>照片</b><small>JPG、PNG</small></button>
        <button class="upload-option option-video" type="button" @click="chooseType('video', '.mp4,.mov,.mkv,.avi,.webm')"><span><Video :size="21" /></span><b>视频</b><small>MP4、MOV</small></button>
        <button class="upload-option option-document" type="button" @click="chooseType('document', '.pdf,.doc,.docx,.txt,.md,.xls,.xlsx,.ppt,.pptx')"><span><FileText :size="21" /></span><b>文档</b><small>PDF、Word</small></button>
        <button class="upload-option option-audio" type="button" @click="chooseType('audio', '.mp3,.wav,.flac,.aac,.m4a')"><span><Music2 :size="21" /></span><b>音频</b><small>MP3、WAV</small></button>
        <button class="upload-option option-other" type="button" @click="chooseType('other', '*/*')"><span><UploadCloud :size="21" /></span><b>其他文件</b><small>全部类型</small></button>
        <div class="upload-option option-folder"><span><FolderPlus :size="21" /></span><b>新建文件夹</b><small>整理文件</small></div>
        <div class="upload-option option-note"><span><FilePlus2 :size="21" /></span><b>新建文档</b><small>稍后开放</small></div>
      </div>

      <div v-if="selectedFiles.length" class="selected-files">
        <div class="selected-files-heading"><div><strong>待上传文件</strong><small>{{ selectedTypeName }} · {{ selectedFiles.length }} 个</small></div><span>尚未上传</span></div>
        <ul>
          <li v-for="item in selectedFiles" :key="item.id"><span class="file-badge"><FileText :size="17" /></span><div class="file-meta"><b :title="item.file.name">{{ item.file.name }}</b><small>{{ formatSize(item.file.size) }} · {{ fileType(item.file) }}</small></div><button type="button" aria-label="删除文件" @click="removeFile(item.id)"><Trash2 :size="16" /></button></li>
        </ul>
      </div>

      <div v-if="selectedFiles.length && folderPickerOpen" class="folder-picker">
        <div class="folder-picker-heading"><strong>选择上传位置</strong><button type="button" aria-label="关闭文件夹选择" @click="folderPickerOpen = false"><X :size="16" /></button></div>
        <button v-for="folder in props.folderOptions" :key="folder.id || 'root'" type="button" :class="{ selected: folder.id === selectedFolderId }" @click="selectFolder(folder.id)">{{ folder.name }}<span v-if="folder.id === selectedFolderId">已选择</span></button>
      </div>

      <input ref="fileInput" class="file-input" type="file" :accept="accept" multiple @change="handleFileSelection" />
      <footer class="upload-footer">
        <template v-if="selectedFiles.length">
          <button class="destination-button" type="button" @click="folderPickerOpen = !folderPickerOpen">上传到：<b>{{ selectedFolderName }}</b><span>›</span></button>
          <button class="start-upload-button" type="button" @click="startUpload">上传 {{ selectedFiles.length }} 个</button>
        </template>
        <template v-else><span>选择文件后，可在这里查看上传进度</span><button type="button" @click="emit('close')">暂时取消</button></template>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.upload-overlay{position:fixed;inset:0;z-index:100;display:grid;place-items:center;padding:24px;background:rgba(17,29,52,.42);backdrop-filter:blur(5px)}
.upload-panel{width:min(560px,100%);overflow:hidden;border:1px solid rgba(255,255,255,.8);border-radius:24px;background:#fff;box-shadow:0 26px 80px rgba(22,44,83,.24);color:#19243a}
.upload-header{display:flex;align-items:flex-start;justify-content:space-between;padding:28px 30px 22px;border-bottom:1px solid #edf1f7}.upload-eyebrow{display:block;margin-bottom:8px;color:#6e7b91;font-size:11px;font-weight:700;letter-spacing:.12em;text-transform:uppercase}.upload-header h2{margin:0;font-size:25px;letter-spacing:-.03em}.close-button{display:grid;place-items:center;width:36px;height:36px;border:0;border-radius:11px;background:#f3f6fb;color:#64728a}.close-button:hover{background:#e8eef9;color:#2868ed}
.desktop-dropzone{margin:24px 30px 22px;padding:25px 20px;border:1px dashed #b8caec;border-radius:17px;background:linear-gradient(135deg,#f7faff,#eef5ff);text-align:center}.dropzone-icon{display:grid;place-items:center;width:52px;height:52px;margin:0 auto 12px;border-radius:16px;background:#dce9ff;color:#2868ed}.desktop-dropzone strong{display:block;font-size:15px}.desktop-dropzone p{margin:7px 0 0;color:#8895a9;font-size:12px}
.upload-section-heading{display:flex;align-items:center;justify-content:space-between;padding:0 30px 12px;color:#26344d;font-size:13px;font-weight:700}.upload-section-heading small{color:#a0a9b8;font-size:11px;font-weight:500}.upload-options{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;padding:0 30px 24px}.upload-option{min-height:91px;padding:14px 12px;border:1px solid #edf0f5;border-radius:15px;background:#fff;color:#19243a;text-align:left}.upload-option button{cursor:pointer}.upload-option:hover{border-color:#9db9ef;background:#f7faff}.upload-option>span{display:grid;place-items:center;width:34px;height:34px;margin-bottom:8px;border-radius:10px}.upload-option b,.upload-option small{display:block}.upload-option b{font-size:12px}.upload-option small{margin-top:3px;color:#9aa5b5;font-size:10px}.option-image>span{background:#e7f1ff;color:#4285ed}.option-video>span{background:#e9f8f0;color:#21a96a}.option-document>span{background:#fff0e7;color:#ef8238}.option-audio>span{background:#f0ebff;color:#8469df}.option-other>span{background:#e8f7ff;color:#2793cf}.option-folder>span{background:#fff7de;color:#d69921}.option-note>span{background:#f1f3f6;color:#8e98a8}
.selected-files{margin:0 30px 24px;padding:16px;border:1px solid #e7edf6;border-radius:15px;background:#fbfcff}.selected-files-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:10px}.selected-files-heading strong,.selected-files-heading small{display:block}.selected-files-heading strong{font-size:13px}.selected-files-heading small{margin-top:4px;color:#8d9aae;font-size:11px}.selected-files-heading>span{color:#7c8da7;font-size:10px}.selected-files ul{max-height:170px;margin:0;padding:0;overflow:auto;list-style:none}.selected-files li{display:flex;align-items:center;gap:10px;padding:9px 0;border-top:1px solid #edf1f6}.file-badge{display:grid;place-items:center;flex:0 0 32px;width:32px;height:32px;border-radius:9px;background:#eeeaff;color:#7564e9}.file-meta{min-width:0;flex:1}.file-meta b,.file-meta small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.file-meta b{font-size:12px}.file-meta small{margin-top:3px;color:#8f9aac;font-size:10px}.selected-files li>button{display:grid;place-items:center;width:28px;height:28px;border:0;border-radius:8px;background:transparent;color:#a0aaba}.selected-files li>button:hover{background:#fff0f0;color:#e15d68}.file-input{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}
 .folder-picker{margin:0 30px 18px;padding:13px;border:1px solid #e5ebf5;border-radius:14px;background:#fff;box-shadow:0 10px 26px rgba(35,62,104,.08)}.folder-picker-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:5px;color:#26344d;font-size:12px}.folder-picker-heading button{display:grid;place-items:center;width:26px;height:26px;border:0;border-radius:7px;background:#f3f6fb;color:#718098}.folder-picker>button{display:flex;align-items:center;justify-content:space-between;width:100%;height:34px;padding:0 10px;border:0;border-radius:8px;background:transparent;color:#4a5870;text-align:left;font-size:12px}.folder-picker>button:hover,.folder-picker>button.selected{background:#eef4ff;color:#2868ed}.folder-picker>button span{font-size:10px}.upload-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:16px 30px;background:#fafbfd;color:#8995a8;font-size:11px}.upload-footer button{height:34px;padding:0 15px;border:1px solid #dce4f1;border-radius:9px;background:#fff;color:#3d6ec9;font-size:12px;font-weight:600}.upload-footer button:hover{border-color:#9db9ef;background:#f5f8ff}.destination-button{display:flex;align-items:center;gap:4px;min-width:0;flex:1;text-align:left}.destination-button b{overflow:hidden;color:#2868ed;text-overflow:ellipsis;white-space:nowrap}.destination-button span{margin-left:auto;color:#8d9bb0;font-size:20px;line-height:1}.start-upload-button{border-color:#8bc9f6!important;background:#8ed1f7!important;color:#fff!important}.start-upload-button:hover{border-color:#50afe8!important;background:#68c0f1!important}
 @media(max-width:700px){.upload-overlay{align-items:end;padding:0;background:rgba(17,29,52,.48)}.upload-panel{width:100%;border-radius:25px 25px 0 0;box-shadow:0 -18px 55px rgba(22,44,83,.18);animation:upload-sheet-in .22s ease-out}.upload-header{padding:22px 22px 17px}.upload-header h2{font-size:22px}.desktop-dropzone{display:none}.upload-section-heading{padding:20px 22px 12px}.upload-options{grid-template-columns:repeat(3,1fr);gap:9px;padding:0 22px 20px}.upload-option{min-height:84px;padding:12px 9px}.selected-files{margin:0 22px 20px}.folder-picker{margin:0 22px 16px}.upload-footer{padding:14px 22px max(14px,env(safe-area-inset-bottom));margin:0}.upload-footer span{max-width:200px;line-height:1.5}.destination-button{padding-left:0;padding-right:0}.start-upload-button{flex:0 0 auto;padding-inline:17px!important}}
@keyframes upload-sheet-in{from{transform:translateY(22px);opacity:.6}to{transform:translateY(0);opacity:1}}
@media(prefers-reduced-motion:reduce){.upload-panel{animation:none}}
</style>
