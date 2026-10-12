// @vitest-environment jsdom
import { Blob as NodeBlob } from "node:buffer";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import { fetchPreviewFile } from "../api/preview";
import { useAuth } from "../stores/auth";
import {
  cachedThumbnail,
  loadThumbnail,
  clearThumbnailCache,
} from "./thumbnails";
import { renderDocx } from "./render";
vi.mock("../api/preview", () => ({ fetchPreviewFile: vi.fn() }));
vi.mock("../stores/auth", async () => {
  const { reactive } = await import("vue");
  const session = reactive({ token: "session" });
  return {
    getToken: () => session.token,
    useAuth: () => ({
      logout: async () => {
        session.token = "";
        return true;
      },
      login: async () => {
        session.token = "session";
      },
    }),
  };
});
vi.mock("./render", () => ({
  renderDocx: vi.fn().mockResolvedValue("<h1>Document</h1>"),
  renderMarkdown: vi.fn().mockResolvedValue("<h1>Markdown</h1>"),
}));
const pdf = vi.hoisted(() => ({
  getDocument: vi.fn(),
  render: vi.fn(),
  getPage: vi.fn(),
  destroy: vi.fn(),
}));
vi.mock("pdfjs-dist", () => ({
  getDocument: pdf.getDocument,
  GlobalWorkerOptions: {},
}));
const file = {
  id: "1",
  name: "a.png",
  size: 10,
  updatedAt: "1",
  kind: "file" as const,
  deletedAt: null,
  parentId: null,
};
const revoke = vi.fn();
beforeEach(async () => {
  clearThumbnailCache();
  vi.clearAllMocks();
  await useAuth().login({ username: "user", password: "password" }, false);
  vi.stubGlobal("URL", {
    createObjectURL: () => "blob:cached",
    revokeObjectURL: revoke,
  });
  vi.mocked(fetchPreviewFile).mockResolvedValue(
    new NodeBlob(["file"]) as unknown as Blob,
  );
});
afterEach(() => {
  clearThumbnailCache();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
it("shares DOCX conversion and keeps its result for subsequent views", async () => {
  const document = { ...file, name: "report.docx" };
  const [a, b] = await Promise.all([
    loadThumbnail(document),
    loadThumbnail(document),
  ]);
  expect(a?.html).toBe("<h1>Document</h1>");
  expect(a).toBe(b);
  expect(await loadThumbnail(document)).toBe(a);
  expect(renderDocx).toHaveBeenCalledTimes(1);
  expect(fetchPreviewFile).toHaveBeenCalledTimes(1);
});
it("caches a PDF first page as an image instead of reparsing on every mount", async () => {
  pdf.render.mockReturnValue({ promise: Promise.resolve() });
  pdf.destroy.mockResolvedValue(undefined);
  pdf.getPage.mockResolvedValue({
    getViewport: ({ scale }: { scale: number }) => ({
      width: 600 * scale,
      height: 800 * scale,
    }),
    render: pdf.render,
  });
  pdf.getDocument.mockReturnValue({
    promise: Promise.resolve({ getPage: pdf.getPage }),
    destroy: pdf.destroy,
  });
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(
    {} as CanvasRenderingContext2D,
  );
  vi.spyOn(HTMLCanvasElement.prototype, "toBlob").mockImplementation(
    (callback) => callback(new NodeBlob(["png"]) as unknown as Blob),
  );
  const document = { ...file, name: "report.pdf" };
  const thumbnail = await loadThumbnail(document);
  expect(thumbnail?.url).toBe("blob:cached");
  expect(await loadThumbnail(document)).toBe(thumbnail);
  expect(pdf.getDocument).toHaveBeenCalledTimes(1);
  expect(pdf.getPage).toHaveBeenCalledWith(1);
  expect(pdf.destroy).toHaveBeenCalledTimes(1);
});
it("revokes private thumbnail URLs when the user logs out", async () => {
  await loadThumbnail(file);
  expect(cachedThumbnail(file)?.url).toBe("blob:cached");
  await useAuth().logout();
  expect(cachedThumbnail(file)).toBeNull();
  expect(revoke).toHaveBeenCalledWith("blob:cached");
});
it("evicts the least recently used thumbnail when the cache fills", async () => {
  await loadThumbnail(file);
  for (let id = 2; id <= 128; id++)
    await loadThumbnail({ ...file, id: String(id) });
  cachedThumbnail(file);
  await loadThumbnail({ ...file, id: "129" });
  expect(cachedThumbnail(file)).not.toBeNull();
  expect(cachedThumbnail({ ...file, id: "2" })).toBeNull();
  expect(revoke).toHaveBeenCalledTimes(1);
});
it("backs off failed thumbnail requests during repeated view switches", async () => {
  vi.mocked(fetchPreviewFile).mockRejectedValue(new Error("offline"));
  expect(await loadThumbnail(file)).toBeNull();
  expect(await loadThumbnail(file)).toBeNull();
  expect(fetchPreviewFile).toHaveBeenCalledTimes(1);
});
