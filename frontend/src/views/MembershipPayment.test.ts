// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils";
import { computed, reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
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
  vi.mocked(simulateMembershipPayment).mockReset();
  account.vipLevel = "NORMAL";
  wrapper = mount(MembershipView, { global: { stubs: { teleport: true } } });
});
afterEach(() => wrapper.unmount());
it("pays immediately for selected plan and only shows success", async () => {
  vi.mocked(simulateMembershipPayment).mockResolvedValue({
    amount: "5.8",
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
  await flushPromises();
  await wrapper.findAll(".plan-card")[0]!.trigger("click");
  await wrapper.find(".primary-pay").trigger("click");
  await flushPromises();
  expect(simulateMembershipPayment).toHaveBeenCalledWith("trial");
  expect(account.vipLevel).toBe("SVIP");
  expect(wrapper.find("[role=status]").text()).toBe("开通成功");
  expect(wrapper.find(".simulated-payment").exists()).toBe(false);
  expect(wrapper.find(".radio-dot").exists()).toBe(false);
  expect(wrapper.find("[role=alertdialog]").exists()).toBe(false);
});
it("blocks repeated payment and allows retry after failure", async () => {
  let reject!: (reason: Error) => void;
  vi.mocked(simulateMembershipPayment).mockReturnValue(
    new Promise((_, fail) => {
      reject = fail;
    }),
  );
  await flushPromises();
  const button = wrapper.find(".primary-pay");
  await button.trigger("click");
  await button.trigger("click");
  expect(simulateMembershipPayment).toHaveBeenCalledTimes(1);
  reject(new Error("network"));
  await flushPromises();
  expect(account.vipLevel).toBe("NORMAL");
  expect(button.attributes("disabled")).toBeUndefined();
  expect(wrapper.text()).toContain("支付失败");
  await button.trigger("click");
  expect(simulateMembershipPayment).toHaveBeenCalledTimes(2);
});
