<script setup lang="ts">
import { useId } from "vue";
defineProps<{ kind: string }>();
const id = useId();
</script>

<template>
  <svg viewBox="0 0 64 64" fill="none" aria-hidden="true">
    <defs>
      <linearGradient :id="`${id}-blue`" x1="32" y1="8" x2="32" y2="60" gradientUnits="userSpaceOnUse"><stop stop-color="#4b88ff"/><stop offset="1" stop-color="#65ddf4"/></linearGradient>
      <linearGradient :id="`${id}-pink`" x1="32" y1="8" x2="32" y2="60" gradientUnits="userSpaceOnUse"><stop stop-color="#f2558c"/><stop offset="1" stop-color="#f39bd1"/></linearGradient>
    </defs>
    <g v-if="kind === 'photo'">
      <path d="M4 48 20 16Q24 8 29 16L48 49Q51 55 44 55H10Q1 55 4 48Z" :fill="`url(#${id}-blue)`"/>
      <path d="m34 49 11-21q4-7 8 0l11 21q3 6-4 6H39q-8 0-5-6Z" :fill="`url(#${id}-pink)`"/>
      <path d="m34 49 8-16 6 16q3 6-4 6h-5q-8 0-5-6Z" fill="#3478ff"/><circle cx="44" cy="15" r="4" fill="#4b88ff"/>
    </g>
    <g v-else-if="kind === 'video'">
      <rect x="5" y="11" width="46" height="43" rx="7" :fill="`url(#${id}-blue)`"/><path d="m43 27 13-8q7-3 7 4v18q0 8-7 4l-13-8q-7-5 0-10Z" :fill="`url(#${id}-pink)`"/><path d="m43 27 8-5v20l-8-5q-7-5 0-10Z" fill="#3478ff"/><circle cx="17" cy="23" r="3" fill="white"/>
    </g>
    <g v-else-if="kind === 'file' || kind === 'folder' || kind === 'note'">
      <path v-if="kind !== 'note'" d="M8 18V12q0-7 8-7h8l9 3h10q7 0 7 9v7H8Z" :fill="`url(#${id}-pink)`"/>
      <rect x="6" :y="kind === 'note' ? 5 : 14" width="51" :height="kind === 'note' ? 52 : 45" rx="7" :fill="`url(#${id}-blue)`"/>
      <path v-if="kind !== 'note'" d="M12 18h36" stroke="#357aff" stroke-width="7" stroke-linecap="round"/>
      <path v-if="kind === 'folder'" d="M25 38h14m-7-7v14" stroke="white" stroke-width="5" stroke-linecap="round"/>
      <path v-else d="M18 27h20M18 38h10" stroke="white" stroke-width="4.5" stroke-linecap="round"/>
      <path v-if="kind === 'note'" d="m38 42 18-19q3-3 6 0l3 3q3 3 0 6L47 51l-10 2Z" :fill="`url(#${id}-pink)`"/><path v-if="kind === 'note'" d="m38 42 12-12v18l-3 3-10 2Z" fill="#3478ff"/>
    </g>
    <g v-else-if="kind === 'wechat'">
      <path d="M56 31c0 14-11 24-25 24-4 0-7-1-10-2l-9 3-2-8C4 44 2 38 2 31 2 17 14 7 29 7s27 10 27 24Z" :fill="`url(#${id}-blue)`"/><path d="M59 45q0 6-4 10l1 6-7-2q-17 0-17-14t14-14q13 0 13 14Z" :fill="`url(#${id}-pink)`"/><path d="M32 45q0-13 13-14h11q0 16-15 22-9-2-9-8Z" fill="#357aff"/><circle cx="21" cy="22" r="3" fill="white"/><circle cx="36" cy="22" r="3" fill="white"/>
    </g>
    <g v-else-if="kind === 'scan'" stroke-width="4.5" stroke-linecap="round">
      <path d="M12 19v-4q0-7 8-7m24 0q8 0 8 7v4" stroke="#5294ff"/><path d="M12 45v4q0 7 8 7m24 0q8 0 8-7v-4" stroke="#65d8fa"/><rect x="17" y="15" width="30" height="34" rx="5" :fill="`url(#${id}-blue)`" stroke="none"/><path d="M12 32h40" stroke="#f281b2" stroke-width="8"/><path d="M17 32h30" stroke="#3979f6" stroke-width="8"/>
    </g>
    <g v-else stroke="#090f1c" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round">
      <g v-if="kind === 'camera'"><path d="M22 12h20" stroke="#5697ff" stroke-width="9"/><rect x="10" y="15" width="44" height="40" rx="7"/><circle cx="32" cy="35" r="9"/><circle cx="46" cy="24" r="1"/></g>
      <g v-else-if="kind === 'mic'"><circle cx="39" cy="18" r="9" fill="#5697ff" stroke="none"/><rect x="23" y="13" width="23" height="36" rx="12"/><path d="M16 36v3a19 19 0 0 0 38 0v-3M35 58v-7"/></g>
      <g v-else-if="kind === 'ai-video'"><circle cx="14" cy="16" r="10" fill="#5697ff" stroke="none"/><path d="M37 55H19q-8 0-8-8V22q0-8 8-8h24q8 0 8 8v7"/><path d="m27 28 12 7-12 8Z"/><path d="m42 45 9-12q3-4 7 0l-1 4-10 14-8 3Z"/></g>
      <g v-else-if="kind === 'story'"><path d="m52 2 4 9 9 4-9 4-4 9-4-9-9-4 9-4Z" fill="#5697ff" stroke="none"/><rect x="9" y="14" width="45" height="43" rx="9"/><circle cx="21" cy="27" r="1"/><path d="M10 44q10-7 18 4m-3 9q5-25 29-24"/></g>
    </g>
  </svg>
</template>
