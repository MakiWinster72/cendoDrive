// @vitest-environment jsdom
import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import wechat from "@iconify-icons/ri/wechat-fill";
import qq from "@iconify-icons/ri/qq-fill";
import weibo from "@iconify-icons/ri/weibo-fill";
import ShareChannelIcon from "./ShareChannelIcon.vue";

describe("share channel icons", () => {
  it.each([{ channel: "wechat", icon: wechat }, { channel: "qq", icon: qq }, { channel: "weibo", icon: weibo }] as const)("renders the bundled $channel brand SVG immediately", ({ channel, icon }) => {
    const wrapper = mount(ShareChannelIcon, { props: { channel } });
    const svg = wrapper.get("svg");
    expect(svg.attributes("viewBox")).toBe("0 0 24 24");
    expect(svg.attributes("aria-hidden")).toBe("true");
    expect(svg.classes()).toContain(`channel-${channel}`);
    expect(svg.classes()).not.toContain(channel);
    const libraryPaths = new DOMParser().parseFromString(`<svg>${icon.body}</svg>`, "image/svg+xml").querySelectorAll("path");
    expect(svg.findAll("path").map(path => path.attributes("d"))).toEqual(Array.from(libraryPaths, path => path.getAttribute("d")));
    expect(wrapper.find("img").exists()).toBe(false);
    wrapper.unmount();
  });
  it("keeps our own drive brand image", () => {
    const wrapper = mount(ShareChannelIcon, { props: { channel: "drive" } });
    expect(wrapper.get("img").attributes("src")).toBe("/cendo_logo_light.svg");
    expect(wrapper.get("img").attributes("alt")).toBe("");
    expect(wrapper.find("svg").exists()).toBe(false);
    wrapper.unmount();
  });
  it("uses the library aperture for Moments instead of hand-drawn polygons", () => {
    const wrapper = mount(ShareChannelIcon, { props: { channel: "moments" } });
    expect(wrapper.get("svg").classes()).toContain("lucide-aperture");
    expect(wrapper.get("svg").attributes("aria-hidden")).toBe("true");
    expect(wrapper.find("g").exists()).toBe(false);
    wrapper.unmount();
  });
});
