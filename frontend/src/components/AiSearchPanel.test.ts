// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import AiSearchPanel from "./AiSearchPanel.vue";
import type { AiSearchHit } from "../api/aiSearchTypes";

const hit: AiSearchHit = {
  fileId: "42",
  fileName: "Redis 笔记.md",
  snippets: ["缓存穿透可以通过布隆过滤器缓解。"],
};

describe("AI search page", () => {
  it("shows loading, matched snippets and an open-file action", async () => {
    let resolve!: (value: AiSearchHit[]) => void;
    const search = vi.fn(
      () =>
        new Promise<AiSearchHit[]>((done) => {
          resolve = done;
        }),
    );
    const wrapper = mount(AiSearchPanel, { props: { search } });
    await wrapper.find("input").setValue("缓存穿透");
    await wrapper.find("form").trigger("submit");
    expect(wrapper.get('[role="status"]').text()).toContain("正在搜索");
    expect(search).toHaveBeenCalledWith("缓存穿透", expect.any(AbortSignal));
    resolve([hit]);
    await flushPromises();
    expect(wrapper.text()).toContain(hit.fileName);
    expect(wrapper.text()).toContain(hit.snippets[0]);
    await wrapper.get(".ai-search-result button").trigger("click");
    expect(wrapper.emitted("open")?.[0]).toEqual([hit]);
    wrapper.unmount();
  });

  it("shows empty and failure states, then retries", async () => {
    const search = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockRejectedValueOnce(new Error("unavailable"))
      .mockResolvedValueOnce([hit]);
    const wrapper = mount(AiSearchPanel, { props: { search } });
    await wrapper.find("input").setValue("不存在的内容");
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("没有找到匹配文件");
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain("搜索失败");
    await wrapper.get('[role="alert"] button').trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain(hit.fileName);
    wrapper.unmount();
  });

  it("fetches fresh results after a file changes", async () => {
    const search = vi
      .fn()
      .mockResolvedValueOnce([hit])
      .mockResolvedValueOnce([]);
    const wrapper = mount(AiSearchPanel, { props: { search } });
    await wrapper.find("input").setValue("缓存穿透");
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain(hit.fileName);
    await wrapper.find("input").setValue("未提交的新查询");
    await wrapper.get(".ai-search-results-heading button").trigger("click");
    await flushPromises();
    expect(search).toHaveBeenCalledTimes(2);
    expect(search.mock.calls[1]![0]).toBe("缓存穿透");
    expect(wrapper.text()).toContain("没有找到匹配文件");
    wrapper.unmount();
  });

  it("ignores a previous request after a new search", async () => {
    let finishOld!: (value: AiSearchHit[]) => void;
    const search = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<AiSearchHit[]>((done) => {
            finishOld = done;
          }),
      )
      .mockResolvedValueOnce([hit]);
    const wrapper = mount(AiSearchPanel, { props: { search } });
    await wrapper.find("input").setValue("旧查询");
    await wrapper.find("form").trigger("submit");
    const oldSignal = search.mock.calls[0]![1] as AbortSignal;
    await wrapper.find("input").setValue("新查询");
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    finishOld([{ fileId: "old", fileName: "旧结果", snippets: [] }]);
    await flushPromises();
    expect(oldSignal.aborted).toBe(true);
    expect(wrapper.text()).toContain(hit.fileName);
    expect(wrapper.text()).not.toContain("旧结果");
    wrapper.unmount();
  });
});
