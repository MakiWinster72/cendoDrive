import { onScopeDispose, ref } from "vue";
import { driveErrorMessage } from "../api/drive";
import { useDrive, type DriveItem } from "../stores/drive";

export type NameEdit =
  | { kind: "create"; parentId: string | null; name: string }
  | { kind: "rename"; item: DriveItem; name: string };

export function useNameEdit(
  onSaved: (item: DriveItem, kind: NameEdit["kind"]) => void | Promise<void> = () => {},
) {
  const drive = useDrive();
  const edit = ref<NameEdit | null>(null);
  const saving = ref(false);
  const error = ref("");
  let alive = true;
  onScopeDispose(() => { alive = false; });

  function cancel() {
    if (!saving.value) {
      edit.value = null;
      error.value = "";
    }
  }

  function beginCreate(parentId: string | null, names: string[] = []) {
    if (saving.value) return;
    const used = new Set(names.map(name => name.toLocaleLowerCase()));
    let name = "新建文件夹", suffix = 2;
    while (used.has(name.toLocaleLowerCase())) name = `新建文件夹 (${suffix++})`;
    error.value = "";
    edit.value = { kind: "create", parentId, name };
  }

  function beginRename(item: DriveItem) {
    if (saving.value) return;
    error.value = "";
    edit.value = { kind: "rename", item, name: item.name };
  }

  async function submit() {
    const draft = edit.value;
    if (!draft || saving.value) return;
    const value = draft.name.trim();
    if (!value || value === "." || value === ".." || value.includes("/") || value.includes("\\") || value.length > 255) {
      error.value = "名称需为 1–255 个字符，不能是 .、.. 或包含路径分隔符。";
      return;
    }
    if (draft.kind === "rename" && value === draft.item.name) {
      cancel();
      return;
    }
    saving.value = true;
    error.value = "";
    try {
      const item = draft.kind === "create"
        ? await drive.createFolder(value, draft.parentId)
        : await drive.rename(draft.item.id, value);
      if (alive) {
        edit.value = null;
        await onSaved(item, draft.kind);
      }
    } catch (cause) {
      if (alive) error.value = driveErrorMessage(cause, "保存失败，请修改名称后重试");
    } finally {
      if (alive) saving.value = false;
    }
  }

  return { edit, saving, error, beginCreate, beginRename, submit, cancel };
}
