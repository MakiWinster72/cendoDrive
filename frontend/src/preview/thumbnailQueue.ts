// Bound concurrent downloads and document parsing when a large grid becomes visible.
let active = 0;
const waiting: (() => void)[] = [];
export async function thumbnailSlot(): Promise<() => void> {
  if (active >= 3) await new Promise<void>((resolve) => waiting.push(resolve));
  else active++;
  return () => {
    const next = waiting.shift();
    if (next) next();
    else active--;
  };
}
