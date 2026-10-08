<script setup lang="ts">
defineProps<{ channel: "wechat" | "drive" | "qq" | "weibo" | "moments" }>();
</script>

<template>
  <img v-if="channel === 'drive'" class="channel-brand" src="/cendo_logo_light.svg" alt="" />
  <svg v-else-if="channel === 'wechat'" viewBox="0 0 48 48" aria-hidden="true">
    <path fill="currentColor" d="M21 6C10 6 3 12 3 20c0 5 3 9 7 12l-2 6 7-3 6 1c11 0 18-7 18-15S31 6 21 6Z" />
    <path fill="currentColor" stroke="var(--icon-bg, white)" stroke-width="2.6" d="M33 21c-8 0-14 5-14 11s6 11 14 11l5-1 6 3-1-6c3-2 5-5 5-8 0-5-7-10-15-10Z" />
    <g fill="var(--icon-bg, white)"><circle cx="14" cy="17" r="2"/><circle cx="26" cy="17" r="2"/><circle cx="28" cy="29" r="1.7"/><circle cx="38" cy="29" r="1.7"/></g>
  </svg>
  <svg v-else-if="channel === 'qq'" viewBox="0 0 48 48" aria-hidden="true">
    <ellipse cx="24" cy="23" rx="13" ry="20" fill="#161616"/>
    <path d="m12 24-5 12 4 3 5-8m20-7 5 12-4 3-5-8" fill="#161616"/>
    <ellipse cx="24" cy="27" rx="10" ry="13" fill="#fff"/>
    <ellipse cx="20" cy="13" rx="3" ry="5" fill="#fff"/><ellipse cx="28" cy="13" rx="3" ry="5" fill="#fff"/>
    <circle cx="21" cy="14" r="1.2"/><circle cx="27" cy="14" r="1.2"/>
    <path d="M15 18q9-5 18 0-9 8-18 0" fill="#ffbe22"/>
    <path d="M12 23q12 6 24 0v6q-12 4-24 0zM16 28h5v12h-5z" fill="#f42b33"/>
    <ellipse cx="16" cy="44" rx="7" ry="2" fill="#ffbe22"/><ellipse cx="32" cy="44" rx="7" ry="2" fill="#ffbe22"/>
  </svg>
  <svg v-else-if="channel === 'weibo'" viewBox="0 0 48 48" aria-hidden="true">
    <path d="M8 23C19 9 30 6 27 18c14-5 15 4 13 9C50 44 5 50 3 33c0-4 2-7 5-10" fill="#ef1239"/>
    <ellipse cx="24" cy="32" rx="15" ry="9" fill="#fff" transform="rotate(-13 24 32)"/>
    <ellipse cx="22" cy="32" rx="9" ry="6" fill="#111" transform="rotate(-20 22 32)"/>
    <ellipse cx="20" cy="30" rx="3" ry="2" fill="#fff"/><circle cx="26" cy="33" r="1.5" fill="#fff"/>
    <path d="M34 5q13-1 11 12M34 11q7 0 6 6" fill="none" stroke="#ff982a" stroke-width="4" stroke-linecap="round"/>
  </svg>
  <svg v-else viewBox="0 0 48 48" aria-hidden="true">
    <g v-for="(color, index) in ['#2bdb7e','#10c6e2','#258cff','#8670ff','#eb65a3','#f5b54b','#d7e958','#70d866']" :key="color" :transform="`rotate(${index * 45} 24 24)`">
      <path d="m25 2 14 10-14 13z" :fill="color"/>
    </g>
  </svg>
</template>

<style scoped>
svg, .channel-brand { width: 32px; height: 32px; display: block; }
.channel-brand { width: 36px; height: 36px; border-radius: 10px; }
</style>
