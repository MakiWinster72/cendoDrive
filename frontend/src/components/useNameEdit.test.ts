// @vitest-environment jsdom
import { effectScope, type EffectScope } from "vue";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { useNameEdit } from "./useNameEdit";
import { useDrive, type DriveItem } from "../stores/drive";
import * as api from "../api/drive";
vi.mock("../api/drive", async importOriginal => ({ ...(await importOriginal<typeof api>()), createFolder: vi.fn(), renameFile: vi.fn() }));
const file: DriveItem = { id: "42", name: "说明.txt", kind: "file", size: 3, parentId: null, updatedAt: "", deletedAt: null };
const scopes: EffectScope[] = [];
function open(onSaved = vi.fn()) {
  const scope = effectScope(); scopes.push(scope);
  const editor = scope.run(() => useNameEdit(onSaved))!;
  return { ...editor, onSaved, scope };
}
beforeEach(() => { vi.resetAllMocks(); useDrive().reset(); useDrive().state.files.push({ ...file }); });
afterEach(() => scopes.splice(0).forEach(scope => scope.stop()));
describe("inline name edit lifecycle", () => {
  it("suggests an unused folder name without making a request", () => {
    const editor = open(); editor.beginCreate("7", ["新建文件夹", "新建文件夹 (2)"]);
    expect(editor.edit.value).toEqual({ kind: "create", parentId: "7", name: "新建文件夹 (3)" });
    expect(api.createFolder).not.toHaveBeenCalled(); editor.cancel(); expect(editor.edit.value).toBeNull();
  });
  it.each(["", " ", ".", "..", "bad/name", "bad\\name", "a".repeat(256)])("rejects invalid name %s without contacting the server", async name => {
    const editor = open(); editor.beginRename(file); editor.edit.value!.name = name; await editor.submit();
    expect(editor.error.value).not.toBe(""); expect(editor.edit.value).not.toBeNull(); expect(api.renameFile).not.toHaveBeenCalled();
  });
  it("cancels unchanged names without a rename request", async () => {
    const editor = open(); editor.beginRename(file); editor.edit.value!.name = "  说明.txt  "; await editor.submit();
    expect(editor.edit.value).toBeNull(); expect(api.renameFile).not.toHaveBeenCalled(); expect(editor.onSaved).not.toHaveBeenCalled();
  });
  it("renames the same ID and updates the existing item only after success", async () => {
    const editor = open(); vi.mocked(api.renameFile).mockResolvedValue({ ...file, name: "新说明.txt" });
    editor.beginRename(file); editor.edit.value!.name = "  新说明.txt  "; await editor.submit();
    expect(api.renameFile).toHaveBeenCalledWith("42", "新说明.txt"); expect(useDrive().get("42")?.name).toBe("新说明.txt");
    expect(editor.edit.value).toBeNull(); expect(editor.onSaved).toHaveBeenCalledWith(expect.objectContaining({ id: "42" }), "rename");
  });
  it("keeps a failed draft and the original item for explicit retry", async () => {
    const editor = open(); vi.mocked(api.renameFile).mockRejectedValueOnce(new Error("同名冲突"));
    editor.beginRename(file); editor.edit.value!.name = "冲突.txt"; await editor.submit();
    expect(editor.error.value).toBe("同名冲突"); expect(editor.edit.value?.name).toBe("冲突.txt"); expect(useDrive().get("42")?.name).toBe("说明.txt");
    editor.edit.value!.name = "另一个.txt"; vi.mocked(api.renameFile).mockResolvedValue({ ...file, name: "另一个.txt" }); await editor.submit();
    expect(editor.error.value).toBe(""); expect(editor.edit.value).toBeNull(); expect(editor.onSaved).toHaveBeenCalledOnce();
  });
  it("guards duplicate submits, cancel and replacement while a request is pending", async () => {
    let resolve!: (item: DriveItem) => void;
    vi.mocked(api.createFolder).mockImplementation(() => new Promise(done => { resolve = done; }));
    const editor = open(); editor.beginCreate("7"); const pending = editor.submit();
    await editor.submit(); editor.cancel(); editor.beginRename(file); editor.beginCreate(null);
    expect(editor.saving.value).toBe(true); expect(api.createFolder).toHaveBeenCalledOnce(); expect(editor.edit.value?.kind).toBe("create");
    resolve({ ...file, id: "99", kind: "folder", parentId: "7" }); await pending;
    expect(editor.saving.value).toBe(false); expect(editor.edit.value).toBeNull(); expect(editor.onSaved).toHaveBeenCalledOnce();
  });
  it("does not call a disposed view's callback after a late request completes", async () => {
    let resolve!: (item: DriveItem) => void;
    vi.mocked(api.createFolder).mockImplementation(() => new Promise(done => { resolve = done; }));
    const editor = open(); editor.beginCreate(null); const pending = editor.submit(); editor.scope.stop();
    resolve({ ...file, id: "99", kind: "folder" }); await pending; expect(editor.onSaved).not.toHaveBeenCalled();
  });
});
