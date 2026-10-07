import type { DriveItem } from "../stores/drive";
export type ToolAction = "menu" | "move" | "copy" | "favorite" | "hide" | "organize" | "details";
export function folderChoices(folders: DriveItem[], selected: string[]) {
  const byId = new Map(folders.map(folder => [folder.id, folder]));
  const selectedIds = new Set(selected);
  const choices = folders.map(folder => {
    const names: string[] = [], seen = new Set<string>();
    let current: DriveItem | undefined = folder, disabled = false;
    while (current) {
      if (seen.has(current.id)) { disabled = true; break; }
      seen.add(current.id); names.unshift(current.name);
      if (selectedIds.has(current.id)) disabled = true;
      current = current.parentId ? byId.get(current.parentId) : undefined;
    }
    return { id: folder.id as string | null, label: "/" + names.join("/"), disabled };
  }).sort((a, b) => a.label.localeCompare(b.label, "zh-CN"));
  return [{ id: null as string | null, label: "根目录 /", disabled: false }, ...choices];
}
