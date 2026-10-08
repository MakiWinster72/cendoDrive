// @vitest-environment jsdom
import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AiSearchView from "./AiSearchView.vue";
import { searchAiFiles } from "../api/aiSearch";
import { getFileDetails } from "../api/drive";

vi.mock("vue-router", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("../api/aiSearch", () => ({ searchAiFiles: vi.fn() }));
vi.mock("../api/drive", () => ({ getFileDetails: vi.fn() }));

const result = { fileId: "42", fileName: "笔记.md", snippets: ["相关内容"] };
const file = { id: "42", name: "笔记.md", kind: "file" as const, size: 10, parentId: null, updatedAt: "", deletedAt: null };

beforeEach(() => vi.resetAllMocks());

async function search() {
  const wrapper = mount(AiSearchView, { global: { stubs: { FilePreview: true } } });
  await wrapper.find("input").setValue("相关内容");
  await wrapper.find("form").trigger("submit");
  await flushPromises();
  return wrapper;
}

describe("AI search file opening", () => {
  it("checks the live file before opening its preview", async () => {
    vi.mocked(searchAiFiles).mockResolvedValue([result]);
    vi.mocked(getFileDetails).mockResolvedValue({ file, createdAt: "", path: "/笔记.md", contentSize: 10, fileCount: 1, folderCount: 0 });
    const wrapper = await search();
    await wrapper.get(".ai-search-result button").trigger("click");
    await flushPromises();
    expect(getFileDetails).toHaveBeenCalledWith("42");
    expect(wrapper.find("file-preview-stub").exists()).toBe(true);
    wrapper.unmount();
  });

  it("reports a deleted file and lets the user refresh results", async () => {
    vi.mocked(searchAiFiles).mockResolvedValueOnce([result]).mockResolvedValueOnce([]);
    vi.mocked(getFileDetails).mockRejectedValue(new Error("not found"));
    const wrapper = await search();
    await wrapper.get(".ai-search-result button").trigger("click");
    await flushPromises();
    expect(wrapper.get('[role="alert"]').text()).toContain("文件已不可用");
    await wrapper.get(".ai-search-results-heading button").trigger("click");
    await flushPromises();
    expect(vi.mocked(searchAiFiles)).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("没有找到匹配文件");
    wrapper.unmount();
  });
});
