// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import InlineNameEditor from "./InlineNameEditor.vue";
import type { DefineComponent } from "vue";
type Props = { modelValue: string; saving: boolean; error: string; creating?: boolean; selectStem?: boolean };
const wrappers: ReturnType<typeof mount>[] = [];
async function open(props: Partial<Props> = {}) {
  const wrapper = mount(InlineNameEditor as DefineComponent<Props>, { props: { modelValue: "说明.txt", saving: false, error: "", ...props }, attachTo: document.body });
  wrappers.push(wrapper); await flushPromises(); return wrapper;
}
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()));
describe("inline name field", () => {
  it.each([["说明.txt", 2], ["archive.tar.gz", 11], [".env", 4], ["README", 6]])("focuses %s and selects the filename stem", async (name, end) => {
    const wrapper = await open({ modelValue: name, selectStem: true }); const input = wrapper.find("input").element as HTMLInputElement;
    expect(document.activeElement).toBe(input); expect(input.selectionStart).toBe(0); expect(input.selectionEnd).toBe(end);
  });
  it("selects the entire new folder name including dots", async () => {
    const wrapper = await open({ creating: true, modelValue: "folder.v1" }); const input = wrapper.find("input").element as HTMLInputElement;
    expect(input.selectionEnd).toBe(9); expect(wrapper.find("input").attributes("aria-label")).toBe("文件夹名称");
  });
  it("emits explicit save/cancel without bubbling into the file's row", async () => {
    const wrapper = await open(), listener = vi.fn(); wrapper.element.parentElement!.addEventListener("click", listener);
    await wrapper.find("input").trigger("click"); await wrapper.find("input").setValue("新名称.txt"); await wrapper.trigger("submit");
    expect(listener).not.toHaveBeenCalled(); expect(wrapper.emitted("update:modelValue")?.[0]).toEqual(["新名称.txt"]); expect(wrapper.emitted("save")).toHaveLength(1);
    await wrapper.find("input").trigger("keydown", { key: "Escape" }); expect(wrapper.emitted("cancel")).toHaveLength(1);
    wrapper.element.parentElement!.removeEventListener("click", listener);
  });
  it("does not submit Enter used to confirm IME composition", async () => {
    const wrapper = await open(); const event = new KeyboardEvent("keydown", { key: "Enter", isComposing: true, bubbles: true, cancelable: true });
    wrapper.find("input").element.dispatchEvent(event); expect(event.defaultPrevented).toBe(true); expect(wrapper.emitted("save")).toBeUndefined();
  });
  it("disables controls while saving, labels errors and restores focus for retry", async () => {
    const wrapper = await open(); await wrapper.setProps({ saving: true }); expect(wrapper.findAll("button").every(button => button.attributes("disabled") !== undefined)).toBe(true);
    await wrapper.setProps({ saving: false, error: "同名冲突" }); await flushPromises();
    const input = wrapper.find("input"); expect(document.activeElement).toBe(input.element); expect(input.attributes("aria-invalid")).toBe("true");
    expect(input.attributes("aria-describedby")).toBe(wrapper.find('[role="alert"]').attributes("id")); expect(wrapper.text()).toContain("同名冲突");
  });
});
