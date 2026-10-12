export interface ChatFileActivity {
  roomId: string;
  messageId: number;
  name: string;
  size: number;
  changedAt: number;
}

const VIEWED_KEY = "cendo-chat-files-viewed";
const LIKED_KEY = "cendo-chat-files-liked";
const MAX_ITEMS = 100;

function read(key: string): ChatFileActivity[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key) || "[]");
    if (!Array.isArray(value)) return [];
    return value
      .filter(
        (item): item is ChatFileActivity =>
          !!item &&
          typeof item === "object" &&
          typeof item.roomId === "string" &&
          Number.isSafeInteger(item.messageId) &&
          typeof item.name === "string" &&
          Number.isFinite(item.size) &&
          Number.isFinite(item.changedAt),
      )
      .slice(0, MAX_ITEMS);
  } catch {
    return [];
  }
}

function write(key: string, items: ChatFileActivity[]) {
  try {
    localStorage.setItem(key, JSON.stringify(items.slice(0, MAX_ITEMS)));
  } catch {
    /* Browser storage may be unavailable; chat file actions still work. */
  }
}

function idOf(item: Pick<ChatFileActivity, "roomId" | "messageId">) {
  return `${item.roomId}/${item.messageId}`;
}

export function getViewedChatFiles() {
  return read(VIEWED_KEY).sort((a, b) => b.changedAt - a.changedAt);
}

export function recordChatFileViewed(
  item: Omit<ChatFileActivity, "changedAt">,
) {
  const next = { ...item, changedAt: Date.now() };
  write(VIEWED_KEY, [
    next,
    ...read(VIEWED_KEY).filter((old) => idOf(old) !== idOf(item)),
  ]);
}

export function getLikedChatFiles() {
  return read(LIKED_KEY).sort((a, b) => b.changedAt - a.changedAt);
}

export function setChatFileLiked(
  item: Omit<ChatFileActivity, "changedAt">,
  liked: boolean,
) {
  const old = read(LIKED_KEY).filter((entry) => idOf(entry) !== idOf(item));
  write(LIKED_KEY, liked ? [{ ...item, changedAt: Date.now() }, ...old] : old);
}

export function isChatFileLiked(
  item: Pick<ChatFileActivity, "roomId" | "messageId">,
) {
  return read(LIKED_KEY).some((entry) => idOf(entry) === idOf(item));
}
