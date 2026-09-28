<script setup lang="ts">
defineProps<{ light?: boolean }>()
</script>

<template>
  <div class="brand" :class="{ light }">
    <span class="brand-mark" aria-hidden="true">
      <i></i><i></i><i></i>
    </span>
    <strong>CendoDrive</strong>
  </div>
</template>

<style scoped>
.brand{display:flex;align-items:center;gap:10px;color:#1d2129}.brand strong{font-size:20px;letter-spacing:.5px}.brand.light{color:#fff}.brand-mark{position:relative;width:34px;height:28px;display:block}.brand-mark i{position:absolute;display:block;background:#306cff;border-radius:14px 14px 7px 14px;transform:rotate(45deg)}.brand-mark i:first-child{width:17px;height:17px;left:8px;top:0}.brand-mark i:nth-child(2){width:15px;height:15px;left:0;top:11px;transform:rotate(-32deg);background:#45a5ff}.brand-mark i:last-child{width:16px;height:16px;right:0;top:11px;transform:rotate(122deg);background:#5b7cff}.light .brand-mark i{background:#fff;opacity:.98}
</style>
