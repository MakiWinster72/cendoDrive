// @vitest-environment jsdom
import { mount } from "@vue/test-utils";
import { expect, it } from "vitest";
import AiMessage from "./AiMessage.vue";
it("renders markdown but strips script, event handlers, dangerous links and tracking images", () => {
  const w = mount(AiMessage, { props: { content: '**safe** <script>alert(1)</script><img src="https://tracker.test" onerror="alert(1)"><a href="javascript:alert(1)">unsafe</a><form><input></form>' } });
  expect(w.get("strong").text()).toBe("safe"); expect(w.find("script").exists()).toBe(false); expect(w.find("img").exists()).toBe(false);
  expect(w.find("input").exists()).toBe(false); expect(w.get("a").attributes("href")).toBeUndefined(); w.unmount();
});
