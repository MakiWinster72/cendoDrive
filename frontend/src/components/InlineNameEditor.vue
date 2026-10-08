<script setup lang="ts">
import { nextTick, onMounted, ref, useId, watch } from "vue";
import { Check, X } from "@lucide/vue";
import "../styles/inline-name.css";

const props = defineProps<{
  modelValue: string;
  saving: boolean;
  error: string;
  creating?: boolean;
  selectStem?: boolean;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: string];
  save: [];
  cancel: [];
}>();
const input = ref<HTMLInputElement>();
const errorId = useId();

watch(() => props.saving, async saving => {
  if (!saving) {
    await nextTick();
    input.value?.focus();
  }
});
onMounted(async () => {
  await nextTick();
  input.value?.focus();
  const dot = props.selectStem ? props.modelValue.lastIndexOf(".") : -1;
  input.value?.setSelectionRange(0, dot > 0 ? dot : props.modelValue.length);
  input.value?.scrollIntoView?.({ block: "nearest" });
});
</script>

<template>
  <form
    class="inline-name-editor"
    :aria-busy="saving"
    :aria-label="creating ? '新建文件夹' : '重命名'"
    @submit.prevent.stop="emit('save')"
    @click.stop
    @dblclick.stop
    @keydown.stop
    @keydown.esc.prevent.stop="emit('cancel')"
  >
    <div class="inline-name-fields">
      <input
        ref="input"
        class="inline-name-input"
        :value="modelValue"
        :aria-label="creating ? '文件夹名称' : '文件名称'"
        :aria-invalid="!!error"
        :aria-describedby="error ? errorId : undefined"
        maxlength="255"
        :disabled="saving"
        autocomplete="off"
        @keydown.enter="$event.isComposing && $event.preventDefault()"
        @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      />
      <button type="submit" :disabled="saving" :aria-label="creating ? '创建文件夹' : '保存名称'" :title="saving ? '保存中…' : 'Enter 保存'">
        <Check :size="18" />
      </button>
      <button type="button" :disabled="saving" aria-label="取消编辑" title="Esc 取消" @click="emit('cancel')">
        <X :size="18" />
      </button>
    </div>
    <p v-if="error" :id="errorId" role="alert">{{ error }}</p>
    <small v-else-if="saving" role="status">保存中…</small>
  </form>
</template>
