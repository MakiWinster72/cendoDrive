<script setup lang="ts">
defineProps<{ light?: boolean }>()
const brandIconUrl = `${import.meta.env.BASE_URL}cendo_logo_light.svg`
</script>

<template>
  <div class="brand" :class="{ light }">
    <img class="brand-mark" :src="brandIconUrl" alt="" aria-hidden="true" />
    <strong>CendoDrive</strong>
  </div>
</template>

<style scoped>
.brand{display:flex;align-items:center;gap:10px;color:#1d2129}.brand strong{font-size:20px;letter-spacing:.5px}.brand.light{color:#fff}.brand-mark{display:block;width:32px;height:32px;object-fit:contain}
</style>
