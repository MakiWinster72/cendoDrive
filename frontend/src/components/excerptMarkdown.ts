/** Excerpts can start/end mid-fence; do not let an unmatched delimiter consume their Markdown. */
export function normalizeExcerptMarkdown(content: string): string {
  const lines = content.split(/\r?\n/);
  let opening: { index: number; marker: string; length: number } | undefined;
  for (let index = 0; index < lines.length; index++) {
    const match = /^ {0,3}(`{3,}|~{3,})(.*)$/.exec(lines[index]!);
    if (!match) continue;
    const fence = match[1]!;
    if (opening) {
      if (fence[0] === opening.marker && fence.length >= opening.length && !match[2]!.trim()) opening = undefined;
    } else if (fence[0] !== "`" || !match[2]!.includes("`")) {
      opening = { index, marker: fence[0]!, length: fence.length };
    }
  }
  if (opening) lines[opening.index] = lines[opening.index]!.replace(/[`~]/g, character => `\\${character}`);
  return lines.join("\n");
}
