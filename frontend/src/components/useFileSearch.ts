import { onScopeDispose, ref, shallowRef, watch, type ComputedRef } from "vue";
import { driveErrorMessage, searchFiles, type FileSearchParams, type FileSearchResponse } from "../api/drive";

/** Search results are isolated from the directory cache. Invalidate even transports that ignore abort. */
export function useFileSearch(params: ComputedRef<FileSearchParams>) {
  const result = shallowRef<FileSearchResponse | null>(null);
  const loading = ref(false), error = ref(""), page = ref(0);
  let timer: ReturnType<typeof setTimeout> | undefined;
  let controller: AbortController | undefined;
  let generation = 0;
  function invalidate() {
    generation++;
    clearTimeout(timer);
    controller?.abort();
  }
  function request(delay = 0) {
    invalidate();
    const ticket = generation;
    const query = { ...params.value, q: params.value.q.trim(), page: page.value, size: 20 };
    result.value = null; error.value = "";
    if (!query.q || query.q.length > 100) {
      loading.value = false;
      if (query.q.length > 100) error.value = "文件名搜索最多输入 100 个字符";
      return;
    }
    loading.value = true;
    timer = setTimeout(async () => {
      controller = new AbortController();
      try {
        const response = await searchFiles(query, controller.signal);
        if (ticket === generation) result.value = response;
      } catch (cause) {
        if (ticket === generation) error.value = "搜索失败：" + driveErrorMessage(cause, "请稍后重试");
      } finally {
        if (ticket === generation) loading.value = false;
      }
    }, delay);
  }
  watch(params, () => { page.value = 0; request(250); }, { immediate: true, flush: "sync" });
  function goPage(next: number) {
    if (loading.value || next < 0 || !result.value || next * 20 >= result.value.total) return;
    page.value = next; request();
  }
  onScopeDispose(invalidate);
  return { result, loading, error, page, goPage, retry: () => request() };
}
