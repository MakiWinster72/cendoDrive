// @vitest-environment jsdom
import { mount } from "@vue/test-utils";
import { expect, it } from "vitest";
import AiMessage from "./AiMessage.vue";
it("renders markdown but strips script, event handlers, dangerous links and tracking images", () => {
  const w = mount(AiMessage, { props: { content: '**safe** <script>alert(1)</script><img src="https://tracker.test" onerror="alert(1)"><a href="javascript:alert(1)">unsafe</a><form><input></form>' } });
  expect(w.get("strong").text()).toBe("safe"); expect(w.find("script").exists()).toBe(false); expect(w.find("img").exists()).toBe(false);
  expect(w.find("input").exists()).toBe(false); expect(w.get("a").attributes("href")).toBeUndefined(); w.unmount();
});

it("renders headings and lists after an unmatched excerpt fence", () => {
  for (const fence of ["```", "```md", "~~~"]) {
    const w = mount(AiMessage, { props: { excerpt: true, content: `${fence}\n## 后续标题\n\n- 项目\n\n**重点**` } });
    expect(w.get("h2").text()).toBe("后续标题");
    expect(w.get("li").text()).toBe("项目");
    expect(w.get("strong").text()).toBe("重点");
    w.unmount();
  }
});
it("preserves complete fences and the regular answer renderer", () => {
  const w = mount(AiMessage, { props: { excerpt: true, content: "```js\nconst value = 1;\n```\n\n## 后续标题" } });
  expect(w.get("pre code").text()).toContain("const value = 1;");
  expect(w.get("h2").text()).toBe("后续标题"); w.unmount();
  const answer = mount(AiMessage, { props: { content: "```js\nconst value = 1;" } });
  expect(answer.get("pre code").text()).toContain("const value = 1;"); answer.unmount();
});