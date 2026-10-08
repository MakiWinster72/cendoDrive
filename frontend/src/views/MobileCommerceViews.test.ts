// @vitest-environment jsdom
import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import MembershipView from "./MembershipView.vue";
import AiPointsView from "./AiPointsView.vue";
import MyAssetsView from "./MyAssetsView.vue";
import NovelHubView from "./NovelHubView.vue";
import GameCenterView from "./GameCenterView.vue";

const nav = vi.hoisted(() => ({ back: vi.fn(), push: vi.fn(), replace: vi.fn() }));
vi.mock("vue-router", () => ({ useRouter: () => nav }));
const wrappers: ReturnType<typeof mount>[] = [];
function mountTracked(component: Parameters<typeof mount>[0]) {
  const wrapper = mount(component);
  wrappers.push(wrapper);
  return wrapper;
}
function dialogText() {
  return document.body.querySelector(".unavailable-dialog")?.textContent ?? "";
}

beforeEach(() => vi.resetAllMocks());
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()));

describe("mobile membership page", () => {
  it("selects a membership plan and never pretends to process real payment", async () => {
    const wrapper = mountTracked(MembershipView);
    expect(wrapper.find(".demo-inline").text()).toContain("演示数据");
    const plans = wrapper.findAll(".plan-card");
    await plans[0]!.trigger("click");
    expect(plans[0]!.attributes("aria-pressed")).toBe("true");
    await wrapper.find(".primary-pay").trigger("click");
    expect(dialogText()).toContain("支付功能暂未接入");
    expect(dialogText()).toContain("不会产生扣款");
  });

  it("labels the unavailable privilege comparison", async () => {
    const wrapper = mountTracked(MembershipView);
    await wrapper.find(".section-heading button").trigger("click");
    expect(dialogText()).toContain("会员权益对比暂未提供");
  });

  it("shows a centered alert dialog that can be dismissed", async () => {
    const wrapper = mountTracked(MembershipView);
    await wrapper.find(".primary-pay").trigger("click");
    expect(document.body.querySelector('[role="alertdialog"]')).not.toBeNull();
    (document.body.querySelector(".unavailable-confirm") as HTMLButtonElement).click();
    await nextTick();
    expect(document.body.querySelector('[role="alertdialog"]')).toBeNull();
  });

  it("provides a route from membership to AI point recharge", async () => {
    const wrapper = mountTracked(MembershipView);
    await wrapper.find(".upsell-card > button").trigger("click");
    expect(nav.push).toHaveBeenCalledWith({ name: "ai-points" });
  });
});

describe("mobile AI points page", () => {
  it("updates the selected pack and shows a non-payment demo state", async () => {
    const wrapper = mountTracked(AiPointsView);
    await wrapper.findAll(".points-pack")[1]!.trigger("click");
    expect(wrapper.findAll(".points-pack")[1]!.attributes("aria-pressed")).toBe("true");
    expect(wrapper.find(".primary-pay").text()).toContain("¥ 19");
    await wrapper.find(".primary-pay").trigger("click");
    expect(dialogText()).toContain("充值支付暂未接入");
    expect(wrapper.find(".points-description").text()).toContain("演示数据");
  });

  it("opens the membership page from the SVIP offer", async () => {
    const wrapper = mountTracked(AiPointsView);
    await wrapper.find(".points-member-link").trigger("click");
    expect(nav.push).toHaveBeenCalledWith({ name: "membership" });
  });
});

describe("mobile assets page", () => {
  it("switches asset categories and status filters with matching empty states", async () => {
    const wrapper = mountTracked(MyAssetsView);
    expect(wrapper.find(".asset-empty").text()).toContain("暂无特权券");
    expect(wrapper.find(".asset-data-note").text()).toContain("接口尚未接入");
    await wrapper.findAll(".asset-categories button")[1]!.trigger("click");
    await wrapper.findAll(".asset-statuses button")[1]!.trigger("click");
    expect(wrapper.find(".asset-empty").text()).toContain("暂无可使用福利券");
  });
});

describe("mobile novel hub", () => {
  it("renders both bookshelf entry states and a novel discovery section", () => {
    const wrapper = mountTracked(NovelHubView);
    expect(wrapper.text()).toContain("网盘和本地小说");
    expect(wrapper.text()).toContain("书城书架");
    expect(wrapper.text()).toContain("你可能在找");
    expect(wrapper.text()).toContain("男生热读");
  });
});

describe("mobile game center", () => {
  it("shows task rewards and filters recommended game cards", async () => {
    const wrapper = mountTracked(GameCenterView);
    expect(wrapper.text()).toContain("做任务");
    expect(wrapper.text()).toContain("精品推荐");
    await wrapper.findAll(".game-category-strip button")[1]!.trigger("click");
    expect(wrapper.findAll(".more-game-row").length).toBeGreaterThan(0);
    expect(wrapper.findAll(".more-game-row").every(row => row.text().includes("休闲益智"))).toBe(true);
  });

  it("does not claim task rewards are connected", async () => {
    const wrapper = mountTracked(GameCenterView);
    await wrapper.find(".task-cta").trigger("click");
    expect(dialogText()).toContain("游戏任务服务暂未接入");
  });
});
