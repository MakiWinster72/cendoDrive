// @vitest-environment jsdom
import { mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import MembershipView from "./MembershipView.vue";
import AiPointsView from "./AiPointsView.vue";
import MyAssetsView from "./MyAssetsView.vue";
import NovelHubView from "./NovelHubView.vue";
import GameCenterView from "./GameCenterView.vue";

const nav = vi.hoisted(() => ({ back: vi.fn(), push: vi.fn(), replace: vi.fn() }));
vi.mock("vue-router", () => ({ useRouter: () => nav }));

beforeEach(() => vi.resetAllMocks());

describe("mobile membership page", () => {
  it("selects a membership plan and never pretends to process real payment", async () => {
    const wrapper = mount(MembershipView);
    const plans = wrapper.findAll(".plan-card");
    await plans[0]!.trigger("click");
    expect(plans[0]!.attributes("aria-pressed")).toBe("true");
    await wrapper.find(".primary-pay").trigger("click");
    expect(wrapper.find('[role="status"]').text()).toContain("支付功能暂未接入");
    expect(wrapper.find('[role="status"]').text()).toContain("不会产生扣款");
  });

  it("provides a route from membership to AI point recharge", async () => {
    const wrapper = mount(MembershipView);
    await wrapper.find(".upsell-card > button").trigger("click");
    expect(nav.push).toHaveBeenCalledWith({ name: "ai-points" });
  });
});

describe("mobile AI points page", () => {
  it("updates the selected pack and shows a non-payment demo state", async () => {
    const wrapper = mount(AiPointsView);
    await wrapper.findAll(".points-pack")[1]!.trigger("click");
    expect(wrapper.findAll(".points-pack")[1]!.attributes("aria-pressed")).toBe("true");
    expect(wrapper.find(".primary-pay").text()).toContain("¥ 19");
    await wrapper.find(".primary-pay").trigger("click");
    expect(wrapper.find('[role="status"]').text()).toContain("充值支付暂未接入");
  });

  it("opens the membership page from the SVIP offer", async () => {
    const wrapper = mount(AiPointsView);
    await wrapper.find(".points-member-link").trigger("click");
    expect(nav.push).toHaveBeenCalledWith({ name: "membership" });
  });
});

describe("mobile assets page", () => {
  it("switches asset categories and status filters with matching empty states", async () => {
    const wrapper = mount(MyAssetsView);
    expect(wrapper.find(".asset-empty").text()).toContain("暂无特权券");
    await wrapper.findAll(".asset-categories button")[1]!.trigger("click");
    await wrapper.findAll(".asset-statuses button")[1]!.trigger("click");
    expect(wrapper.find(".asset-empty").text()).toContain("暂无可使用福利券");
  });
});

describe("mobile novel hub", () => {
  it("renders both bookshelf entry states and a novel discovery section", () => {
    const wrapper = mount(NovelHubView);
    expect(wrapper.text()).toContain("网盘和本地小说");
    expect(wrapper.text()).toContain("书城书架");
    expect(wrapper.text()).toContain("你可能在找");
    expect(wrapper.text()).toContain("男生热读");
  });
});

describe("mobile game center", () => {
  it("shows task rewards and filters recommended game cards", async () => {
    const wrapper = mount(GameCenterView);
    expect(wrapper.text()).toContain("做任务");
    expect(wrapper.text()).toContain("精品推荐");
    await wrapper.findAll(".game-category-strip button")[1]!.trigger("click");
    expect(wrapper.findAll(".more-game-row").length).toBeGreaterThan(0);
    expect(wrapper.findAll(".more-game-row").every(row => row.text().includes("休闲益智"))).toBe(true);
  });

  it("does not claim task rewards are connected", async () => {
    const wrapper = mount(GameCenterView);
    await wrapper.find(".task-cta").trigger("click");
    expect(wrapper.find('[role="status"]').text()).toContain("游戏任务服务暂未接入");
  });
});
