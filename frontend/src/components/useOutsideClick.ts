import { onBeforeUnmount, onMounted, type Ref } from "vue";

export function useOutsideClick(
  elements: Ref<HTMLElement | undefined>[],
  close: () => void,
) {
  function onClick(event: MouseEvent) {
    if (
      elements.some(
        (element) =>
          element.value && event.composedPath().includes(element.value),
      )
    )
      return;
    close();
  }
  onMounted(() => document.addEventListener("click", onClick, true));
  onBeforeUnmount(() => document.removeEventListener("click", onClick, true));
}
