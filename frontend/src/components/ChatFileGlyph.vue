<script setup lang="ts">
import { computed } from 'vue';
import { FileText, Image, Film, Music2, FileArchive, File } from 'lucide-vue-next';
import FolderGlyph from './FolderGlyph.vue';
const props = defineProps<{name: string; folder?: boolean}>();
const type = computed(() => { const ext = props.name.split('.').pop()?.toLowerCase() ?? ''; return /^(png|jpe?g|gif|webp|svg|avif|bmp)$/.test(ext) ? 'image' : /^(mp4|mov|webm|mkv)$/.test(ext) ? 'video' : /^(mp3|wav|flac|m4a)$/.test(ext) ? 'audio' : /^(zip|rar|7z|tar|gz)$/.test(ext) ? 'archive' : /^(pdf|docx?|txt|md|xlsx?|pptx?)$/.test(ext) ? 'document' : 'file'; });
const icon = computed(() => ({image:Image,video:Film,audio:Music2,archive:FileArchive,document:FileText,file:File})[type.value]);
</script>
<template><span class="chat-file-glyph" :class="folder ? 'glyph-folder' : 'glyph-'+type"><FolderGlyph v-if="folder"/><component v-else :is="icon" :size="26" :stroke-width="1.6" aria-hidden="true"/></span></template>
