<script setup lang="ts">
import { Icon } from "@iconify/vue/offline";
import wechat from "@iconify-icons/ri/wechat-fill";
import qq from "@iconify-icons/ri/qq-fill";
import weibo from "@iconify-icons/ri/weibo-fill";
import { Aperture } from "@lucide/vue";

defineProps<{ channel: "wechat" | "drive" | "qq" | "weibo" | "moments" }>();
const brandIcons = { wechat, qq, weibo };
</script>

<template>
  <img
    v-if="channel === 'drive'"
    class="channel-brand"
    src="/cendo_logo_light.svg"
    alt=""
  />
  <Aperture
    v-else-if="channel === 'moments'"
    class="channel-icon channel-moments"
    aria-hidden="true"
    :stroke-width="1.8"
  />
  <Icon
    v-else
    class="channel-icon"
    :class="`channel-${channel}`"
    :icon="brandIcons[channel]"
    aria-hidden="true"
  />
</template>

<style scoped>
.channel-icon,
.channel-brand {
  width: 32px;
  height: 32px;
  display: block;
}
.channel-brand {
  width: 36px;
  height: 36px;
  border-radius: 10px;
}
.channel-wechat {
  color: var(--share-channel-color, #07c160);
}
.channel-qq {
  color: var(--share-channel-color, #111);
}
.channel-weibo {
  color: var(--share-channel-color, #e6162d);
}
.channel-moments {
  color: var(--share-channel-color, #18c887);
}
</style>
