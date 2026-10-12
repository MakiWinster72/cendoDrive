// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils";
import { computed, reactive } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import MembershipView from "./MembershipView.vue";
import { simulateMembershipPayment } from "../api/membership";
const account = reactive({
  id: "1",
  username: "test",
  nickname: "测试账号",
  vipLevel: "NORMAL",
});
vi.mock("vue-router", () => ({
  useRouter: () => ({ back: vi.fn(), replace: vi.fn(), push: vi.fn() }),
}));
vi.mock("../api/content", () => ({
  getMembershipContent: vi.fn().mockRejectedValue(new Error("demo")),
}));
vi.mock("../api/membership", () => ({ simulateMembershipPayment: vi.fn() }));
vi.mock("../stores/auth", () => ({
  useAuth: () => ({
    user: computed(() => account),
    updateUser: (user: typeof account) => Object.assign(account, user),
  }),
}));
let wrapper: ReturnType<typeof mount>;
beforeEach(() => {
  vi.clearAllMocks();
  account.vipLevel = "NORMAL";
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
  wrapper = mount(MembershipView);
});
afterEach(() => wrapper.unmount());
async function openPayment() {
  await flushPromises();
  await wrapper.find(".primary-pay").trigger("click");
}
describe("membership simulated payment", () => {
  it("shows the selected amount and updates membership only on confirmation", async () => {
    await openPayment();
    expect(wrapper.find(".simulated-payment").text()).toContain("¥152");
    expect(wrapper.find(".simulated-payment").text()).toContain("不扣款");
    expect(simulateMembershipPayment).not.toHaveBeenCalled();
    vi.mocked(simulateMembershipPayment).mockResolvedValue({
      amount: "152",
      paymentMethod: "wechat",
      simulated: true,
      user: {
        ...account,
        vipLevel: "SVIP",
        createdAt: "2026-10-01T00:00:00Z",
        storageUsed: 0,
        storageLimit: 1073741824,
      },
    });
    await wrapper
      .find(".simulated-payment .trash-confirm-submit")
      .trigger("click");
    await flushPromises();
    expect(simulateMembershipPayment).toHaveBeenCalledWith("year");
    expect(wrapper.find(".member-account").text()).toContain(
      "SVIP 会员 · 无到期时间",
    );
    expect(wrapper.find(".simulated-payment").exists()).toBe(false);
  });
  it("cancel does not activate membership", async () => {
    await openPayment();
    await wrapper.find(".simulated-payment footer button").trigger("click");
    expect(simulateMembershipPayment).not.toHaveBeenCalled();
    expect(account.vipLevel).toBe("NORMAL");
  });
  it("blocks repeated confirmation and permits retry after failure", async () => {
    let reject!: (reason: Error) => void;
    vi.mocked(simulateMembershipPayment).mockReturnValue(
      new Promise((_, fail) => {
        reject = fail;
      }),
    );
    await openPayment();
    const button = wrapper.find(".simulated-payment .trash-confirm-submit");
    await button.trigger("click");
    await button.trigger("click");
    expect(simulateMembershipPayment).toHaveBeenCalledTimes(1);
    reject(new Error("network"));
    await flushPromises();
    expect(wrapper.find(".simulated-payment [role=alert]").text()).toContain(
      "模拟支付失败",
    );
    expect(account.vipLevel).toBe("NORMAL");
    expect(button.attributes("disabled")).toBeUndefined();
  });
});
