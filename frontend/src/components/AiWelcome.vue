<script setup lang="ts">
import { Sparkles } from "@lucide/vue";
defineProps<{ name: string; description: string; suggestions: string[] }>();
defineEmits<{ select: [value: string] }>();
</script>

<template>
  <section class="ai-welcome">
    <div class="greeting-row">
      <div class="greeting"><h1>Hello<span>!</span></h1><h2>我是{{ name }}</h2><p>{{ description }}</p></div>
      <div class="robot" aria-hidden="true"><div class="antenna" /><div class="robot-head"><div class="robot-face"><i /><i /></div><div class="robot-smile" /></div><div class="robot-body"><Sparkles :size="21" /></div><div class="robot-shadow" /></div>
    </div>
    <div class="suggestion-heading"><Sparkles :size="16" />从一个小问题开始</div>
    <div class="suggestions"><button v-for="suggestion in suggestions" :key="suggestion" @click="$emit('select', suggestion)">{{ suggestion }}<span>↗</span></button></div>
  </section>
</template>

<style scoped src="../styles/ai.css"></style>
