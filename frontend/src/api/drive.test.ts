import { beforeEach, describe, expect, it, vi } from "vitest";
import http from "./http";
import {
  getUsage, listFavorites, listHidden, listFolders, getFileDetails, setFavorite, setHidden, moveFiles, copyFiles, organizeFiles,
  searchFiles,
  createFolder,
  deleteFilesForever,
  emptyTrash,
  listFiles,
  listTrash,
  moveFile,
  renameFile,
  restoreFiles,
  trashFiles,
} from "./drive";

vi.mock("./http", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

beforeEach(() => vi.clearAllMocks());

describe("drive API contract", () => {
  it("passes filename search scope pagination and abort signal without client owner identifiers", async () => {
    const data = { items: [], total: 0, page: 1, size: 20 };
    vi.mocked(http.get).mockResolvedValue({ data });
    const signal = new AbortController().signal;
    const params = { q: "100%_合同", scope: "folder" as const, parentId: "7", type: "doc" as const, sort: "time" as const, page: 1, size: 20 };
    expect(await searchFiles(params, signal)).toEqual(data);
    expect(http.get).toHaveBeenCalledWith("/files/search", { params, signal });
  });
  it("lists root and child directories with the expected query", async () => {
    vi.mocked(http.get).mockResolvedValue({ data: [] });
    await listFiles(null);
    await listFiles("12");
    expect(http.get).toHaveBeenNthCalledWith(1, "/files", { params: {} });
    expect(http.get).toHaveBeenNthCalledWith(2, "/files", {
      params: { parentId: "12" },
    });
  });

  it("uses dedicated folder, rename and move endpoints", async () => {
    vi.mocked(http.post).mockResolvedValue({ data: { id: "1" } });
    vi.mocked(http.put).mockResolvedValue({ data: { id: "1" } });
    await createFolder("资料", null);
    await renameFile("1", "文档");
    await moveFile("1", "2");
    expect(http.post).toHaveBeenCalledWith("/files/folder", {
      name: "资料",
      parentId: null,
    });
    expect(http.put).toHaveBeenNthCalledWith(1, "/files/1/rename", {
      name: "文档",
    });
    expect(http.put).toHaveBeenNthCalledWith(2, "/files/1/move", {
      parentId: "2",
    });
  });

  it("uses persistent trash endpoints for list, trash, restore, delete and empty", async () => {
    vi.mocked(http.get).mockResolvedValue({ data: [] });
    vi.mocked(http.post).mockResolvedValue({ data: [] });
    vi.mocked(http.delete).mockResolvedValue({ data: undefined });
    await listTrash();
    await trashFiles(["1"]);
    await restoreFiles(["1"]);
    await deleteFilesForever(["1"]);
    await emptyTrash();
    expect(http.get).toHaveBeenCalledWith("/files/trash");
    expect(http.post).toHaveBeenNthCalledWith(1, "/files/trash", {
      ids: ["1"],
    });
    expect(http.post).toHaveBeenNthCalledWith(2, "/files/trash/restore", {
      ids: ["1"],
    });
    expect(http.post).toHaveBeenNthCalledWith(3, "/files/trash/delete", {
      ids: ["1"],
    });
    expect(http.delete).toHaveBeenCalledWith("/files/trash");
  });
  it("uses the extended file-management routes and explicit flag values", async () => {
    vi.mocked(http.get).mockResolvedValue({ data: [] });
    vi.mocked(http.post).mockResolvedValue({ data: [] });
    await getUsage(); await listFavorites(); await listHidden(); await listFolders(); await getFileDetails("42");
    expect(vi.mocked(http.get).mock.calls.map(call => call[0])).toEqual(["/files/usage", "/files/favorites", "/files/hidden", "/files/folders", "/files/42/details"]);
    await setFavorite(["42"], false); await setHidden(["42"], true); await moveFiles(["42"], null); await copyFiles(["42"], "7"); await organizeFiles(["42"]);
    expect(vi.mocked(http.post).mock.calls).toEqual([["/files/favorite", { ids: ["42"], value: false }], ["/files/hidden", { ids: ["42"], value: true }], ["/files/move", { ids: ["42"], parentId: null }], ["/files/copy", { ids: ["42"], parentId: "7" }], ["/files/organize", { ids: ["42"] }]]);
  });
});
