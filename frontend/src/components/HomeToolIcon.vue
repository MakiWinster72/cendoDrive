<script setup lang="ts">
defineProps<{
  name: "photo" | "video" | "career" | "document" | "scan" | "audio";
}>();
</script>

<template>
  <svg
    viewBox="0 0 80 80"
    width="64"
    height="64"
    aria-hidden="true"
    xmlns="http://www.w3.org/2000/svg"
  >
    <defs>
      <linearGradient id="tool-blue" x1="0" y1="0" x2="1" y2="1">
        <stop stop-color="#4b8ef9" />
        <stop offset="1" stop-color="#62d5f4" />
      </linearGradient>
      <linearGradient id="tool-pink" x1="0" y1="0" x2="1" y2="1">
        <stop stop-color="#ee628f" />
        <stop offset="1" stop-color="#f18bc4" />
      </linearGradient>
    </defs>
    <g v-if="name === 'photo'">
      <path
        d="M9 57 28 17c2.4-5 8.5-5 11 0l26 43c2.3 4-.5 9-5.2 9H15c-6.5 0-9.2-5.8-6-12Z"
        fill="url(#tool-blue)"
      />
      <path
        d="m52 38 16 26c1.7 2.6-.1 5-3 5H49c-4.2 0-6.1-4.5-3.9-8L52 48c-3-5-2.8-7.5 0-10Z"
        fill="url(#tool-pink)"
      />
      <path
        d="m44 39 9 17-6 11c-2 3.3-7.1 3-9-.2-1-1.8-.7-3.6.3-5.3Z"
        fill="#397ff2"
      />
      <circle cx="50" cy="18" r="4.7" fill="#4c90fa" />
    </g>
    <g v-else-if="name === 'video'">
      <rect
        x="9"
        y="12"
        width="54"
        height="56"
        rx="10"
        fill="url(#tool-blue)"
      />
      <path
        d="m61 30 10-7c3.3-2.3 7 .1 7 4v27c0 4-3.7 6.3-7 4l-10-7Z"
        fill="url(#tool-pink)"
      />
      <path d="m61 30-12 9c-3 2.2-3 5.8 0 8l12 8Z" fill="#347cf1" />
      <circle cx="23" cy="27" r="4" fill="white" />
    </g>
    <g v-else-if="name === 'career'">
      <circle cx="40" cy="20" r="14" fill="#5195fa" />
      <path
        d="M12 64c0-16 12-28 28-28s28 12 28 28c0 3-2 5-5 5H17c-3 0-5-2-5-5Z"
        fill="url(#tool-blue)"
      />
      <path d="m40 39-6 16 6 7 6-7Z" fill="white" />
      <path
        d="M58 51h16c3 0 4.5 2 4 5l-2 10c-.4 2-2 3-4 3H54Z"
        fill="url(#tool-pink)"
      />
      <path d="M60 51h8v13c0 3-2 5-5 5H47l3-13c1-3 4-5 10-5Z" fill="#367cf3" />
    </g>
    <g v-else-if="name === 'document'">
      <rect
        x="12"
        y="10"
        width="52"
        height="60"
        rx="10"
        fill="url(#tool-blue)"
      />
      <path
        d="M59 32h10c4 0 6 3 6 7v27c0 3-2 5-5 5H59Z"
        fill="url(#tool-pink)"
      />
      <path d="M59 32h7v38h-7c-4 0-6-3-6-7V39c0-4 2-7 6-7Z" fill="#347df2" />
      <text
        x="22"
        y="43"
        fill="white"
        font-size="24"
        font-family="sans-serif"
        font-weight="600"
      >
        文
      </text>
    </g>
    <g v-else-if="name === 'scan'">
      <path
        d="M10 37V27c0-10 5-15 15-15h4v10h-4c-4 0-5 1-5 5v10Z"
        fill="url(#tool-pink)"
      />
      <path
        d="M48 12h19c3 0 5 2 5 5v34c0 12-8 21-22 21V61c8 0 11-4 11-11V23H48Z"
        fill="url(#tool-blue)"
      />
      <circle cx="44" cy="41" r="15" fill="#4189f8" />
      <circle cx="44" cy="41" r="6" fill="white" />
    </g>
    <g v-else-if="name === 'audio'">
      <circle cx="40" cy="40" r="32" fill="url(#tool-blue)" />
      <circle cx="40" cy="40" r="6" fill="white" />
      <path d="M65 41h9v6h-9Z" fill="url(#tool-pink)" />
      <path
        d="M71 45c-8 2-16 8-18 16-1 4 2 7 6 5 9-4 13-11 12-21Z"
        fill="url(#tool-pink)"
      />
      <path
        d="M70 46c-8 3-14 8-17 15-1 3 0 5 2 6 9-6 15-13 15-21Z"
        fill="#347df2"
      />
    </g>
  </svg>
</template>
