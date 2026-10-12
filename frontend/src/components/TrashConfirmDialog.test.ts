// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import TrashConfirmDialog from "./TrashConfirmDialog.vue";
const trash = vi.hoisted(() => vi.fn());
vi.mock("../stores/drive", () => ({ useDrive: () => ({ trash }) }));
const wrappers: ReturnType<typeof mount>[] = [];
function open(ids = ["42"], name = "项目说明.pdf") {
  const wrapper = mount(TrashConfirmDialog, {
    props: { ids, name },
    attachTo: document.body,
  });
  wrappers.push(wrapper);
  return wrapper;
}
beforeEach(() => {
  trash.mockReset().mockResolvedValue(undefined);
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
});
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
});
describe("trash confirmation", () => {
  it("shows the target and recovery hint without deleting on open", () => {
    const wrapper = open();
    expect(wrapper.get("dialog").attributes("open")).toBe("");
    expect(wrapper.text()).toContain("项目说明.pdf");
    expect(wrapper.text()).toContain("移入后可在回收站恢复");
    expect(wrapper.get("button[autofocus]").text()).toBe("取消");
    expect(trash).not.toHaveBeenCalled();
  });
  it.each(["button[autofocus]", "button[aria-label]", "dialog"])(
    "cancels using %s without deleting",
    async (selector) => {
      const wrapper = open();
      await wrapper.get(selector).trigger("click");
      expect(wrapper.emitted("close")).toHaveLength(1);
      expect(trash).not.toHaveBeenCalled();
    },
  );
  it("cancels Escape while preventing native close", async () => {
    const wrapper = open();
    const event = new Event("cancel", { cancelable: true });
    wrapper.get("dialog").element.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(wrapper.emitted("close")).toHaveLength(1);
    expect(trash).not.toHaveBeenCalled();
  });
  it("confirms a batch once and blocks cancellation while saving", async () => {
    let finish!: () => void;
    trash.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    const wrapper = open(["42", "43"]);
    expect(wrapper.text()).toContain("已选 2 项");
    await wrapper.get(".trash-confirm-submit").trigger("click");
    await wrapper.get(".trash-confirm-submit").trigger("click");
    await wrapper.get("dialog").trigger("cancel");
    await wrapper.get("dialog").trigger("click");
    expect(wrapper.text()).toContain("处理中");
    expect(
      wrapper
        .findAll("button")
        .every((button) => button.attributes("disabled") !== undefined),
    ).toBe(true);
    expect(wrapper.emitted("close")).toBeUndefined();
    expect(trash).toHaveBeenCalledOnce();
    expect(trash).toHaveBeenCalledWith(["42", "43"]);
    finish();
    await flushPromises();
    expect(wrapper.emitted("trashed")).toHaveLength(1);
  });
  it("keeps errors in the dialog and allows retry", async () => {
    trash.mockRejectedValueOnce(new Error("移入失败，请重试"));
    const wrapper = open();
    await wrapper.get(".trash-confirm-submit").trigger("click");
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain("移入失败");
    expect(wrapper.emitted("trashed")).toBeUndefined();
    await wrapper.get(".trash-confirm-submit").trigger("click");
    await flushPromises();
    expect(trash).toHaveBeenCalledTimes(2);
    expect(wrapper.emitted("trashed")).toHaveLength(1);
  });
  it("restores body scrolling and ignores late completion after unmount", async () => {
    let finish!: () => void;
    trash.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    document.body.style.overflow = "auto";
    const wrapper = open();
    expect(document.body.style.overflow).toBe("hidden");
    await wrapper.get(".trash-confirm-submit").trigger("click");
    wrappers.pop();
    wrapper.unmount();
    finish();
    await flushPromises();
    expect(document.body.style.overflow).toBe("auto");
    expect(wrapper.emitted("trashed")).toBeUndefined();
    document.body.style.overflow = "";
  });
});
