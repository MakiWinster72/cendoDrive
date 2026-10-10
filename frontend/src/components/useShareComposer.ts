import { computed, nextTick, onMounted, onUnmounted, ref } from "vue";
import {
  shareClipboardText,
  shareErrorMessage,
  type ShareRecord,
} from "../api/shares";
import { useDrive, type DriveItem } from "../stores/drive";
import {
  buildSharePoster,
  copyShareText,
  downloadSharePoster,
  launchShareApp,
} from "./sharePoster";

/** Owns the selected-file share lifecycle; the view only renders and forwards user actions. */
export function useShareComposer(file: DriveItem) {
  const drive = useDrive();
  const dialog = ref<HTMLDialogElement>(),
    fileArtwork = ref<HTMLElement>();
  const visible = ref(false),
    closing = ref(false),
    busy = ref(false),
    friendBusy = ref(false);
  const view = ref<"link" | "qr" | "friends" | "expiry" | "code">("link");
  const days = ref(0),
    useCode = ref(true),
    autoFill = ref(true),
    extractionCode = ref(randomCode());
  const codeDraft = ref(""),
    codeError = ref(""),
    error = ref(""),
    toast = ref("");
  const posterUrl = ref(""),
    posterBlob = ref<Blob | null>(null);
  const actionsDisabled = computed(
    () => busy.value || friendBusy.value || closing.value,
  );
  const expiry = computed(() =>
    days.value === 0 ? "永久有效" : `${days.value}天有效`,
  );
  const customDays = ref(7);
  const channels = [
    { channel: "wechat", label: "微信" },
    { channel: "drive", label: "网盘" },
    { channel: "qq", label: "QQ" },
    { channel: "weibo", label: "微博" },
    { channel: "moments", label: "朋友圈" },
  ] as const;
  let alive = true,
    toastTimer: ReturnType<typeof setTimeout> | undefined;
  let cached: { key: string; share: ShareRecord } | null = null;
  let inFlight: { key: string; promise: Promise<ShareRecord> } | null = null;
  let previousOverflow = "";

  function randomCode(): string {
    const alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
    return Array.from(
      crypto.getRandomValues(new Uint8Array(4)),
      (value) => alphabet[value % alphabet.length],
    ).join("");
  }
  function notify(message: string) {
    if (!alive) return;
    clearTimeout(toastTimer);
    toast.value = message;
    toastTimer = setTimeout(
      () => {
        toast.value = "";
      },
      message.length > 16 ? 3800 : 1800,
    );
  }
  function placeholder() {
    notify("此功能暂未开放");
  }
  async function ensureShare(): Promise<ShareRecord> {
    const code = useCode.value ? extractionCode.value : undefined;
    const key = `${days.value}:${code ?? ""}`;
    if (code && !/^[A-Za-z0-9]{4,16}$/.test(code))
      throw new Error("提取码须为4–16位字母或数字");
    if (cached?.key === key) return cached.share;
    if (inFlight?.key === key) return inFlight.promise;
    const promise = drive
      .share(file.id, days.value * 86400, code)
      .then((share) => {
        if (alive) cached = { key, share };
        return share;
      });
    inFlight = { key, promise };
    try {
      return await promise;
    } finally {
      if (inFlight?.promise === promise) inFlight = null;
    }
  }
  async function run(action: () => Promise<void>) {
    if (actionsDisabled.value) return;
    busy.value = true;
    error.value = "";
    try {
      await action();
    } catch (reason) {
      if (alive) error.value = shareErrorMessage(reason, "操作失败，请重试");
    } finally {
      if (alive) busy.value = false;
    }
  }
  function clipboardText(): Promise<string> {
    return ensureShare().then((share) => {
      if (!alive) throw new DOMException("分享已关闭", "AbortError");
      return shareClipboardText(share, window.location.origin, autoFill.value);
    });
  }
  async function copy() {
    await run(async () => {
      // Start the clipboard operation in the click handler, not after link creation.
      await copyShareText(clipboardText());
      notify("链接已复制");
    });
  }
  async function openApp(app: "wechat" | "qq") {
    await run(async () => {
      await copyShareText(clipboardText());
      if (!alive) return;
      notify(`链接已复制，请在${app === "wechat" ? "微信" : "QQ"}中粘贴发送`);
      launchShareApp(app);
    });
  }
  async function showQr() {
    await run(async () => {
      const share = await ensureShare();
      if (!alive) return;
      const svg = fileArtwork.value?.querySelector("svg")?.cloneNode(true) as
        SVGElement | undefined;
      if (!svg) throw new Error("文件图标暂未就绪，请重试");
      svg.setAttribute("xmlns", "http://www.w3.org/2000/svg");
      svg.setAttribute("stroke", "#2798e8");
      const blob = await buildSharePoster(
        share,
        window.location.origin,
        autoFill.value,
        new XMLSerializer().serializeToString(svg),
      );
      if (!alive) return;
      if (posterUrl.value) URL.revokeObjectURL(posterUrl.value);
      posterBlob.value = blob;
      posterUrl.value = URL.createObjectURL(blob);
      view.value = "qr";
    });
    return view.value === "qr";
  }
  async function moments() {
    // The web has no supported protocol that targets WeChat Moments directly.
    if (view.value !== "qr") {
      if (await showQr())
        notify("二维码已生成，请点击下方朋友圈按钮或保存图片后发布");
      return; // Sharing files must start from a fresh user gesture, not after QR generation.
    }
    if (!posterBlob.value) return;
    const file = new File([posterBlob.value], "CendoDrive-分享二维码.png", {
      type: "image/png",
    });
    if (navigator.canShare?.({ files: [file] }) && navigator.share) {
      await run(async () => {
        try {
          await navigator.share({ files: [file], title: file.name });
        } catch (reason) {
          if (!(reason instanceof DOMException && reason.name === "AbortError"))
            throw reason;
        }
      });
    } else notify("请先保存二维码，再在微信朋友圈中选择图片发布");
  }
  async function savePoster() {
    if (!posterBlob.value || actionsDisabled.value) return;
    const file = new File([posterBlob.value], "CendoDrive-分享二维码.png", {
      type: "image/png",
    });
    if (navigator.canShare?.({ files: [file] }) && navigator.share) {
      await run(async () => {
        try {
          await navigator.share({ files: [file], title: "保存分享二维码" });
          notify("可在系统面板保存图片；也可长按上方图片保存");
        } catch (reason) {
          if (!(reason instanceof DOMException && reason.name === "AbortError"))
            throw reason;
        }
      });
    } else {
      downloadSharePoster(posterBlob.value);
      notify("已下载二维码图片；手机可长按图片保存到相册");
    }
  }
  function channelClick(channel: (typeof channels)[number]["channel"]) {
    if (actionsDisabled.value) return;
    if (channel === "wechat" || channel === "qq") void openApp(channel);
    else if (channel === "drive") view.value = "friends";
    else if (channel === "moments") void moments();
    else placeholder();
  }
  function close() {
    if (actionsDisabled.value) return;
    closing.value = true;
    visible.value = false;
  }
  function back() {
    if (!actionsDisabled.value) {
      error.value = "";
      view.value = "link";
    }
  }
  function cancel() {
    if (view.value === "link") close();
    else back();
  }
  function editCode() {
    codeDraft.value = extractionCode.value;
    codeError.value = "";
    view.value = "code";
  }
  function saveCode() {
    if (!/^[A-Za-z0-9]{4,16}$/.test(codeDraft.value)) {
      codeError.value = "请输入4–16位字母或数字";
      return;
    }
    extractionCode.value = codeDraft.value;
    back();
  }
  function selectExpiry(value: number) {
    days.value = value;
    back();
  }
  function confirmCustomDays() {
    if (
      !Number.isInteger(customDays.value) ||
      customDays.value < 1 ||
      customDays.value > 30
    ) {
      error.value = "自定义有效期为1–30天的整数";
      return;
    }
    selectExpiry(customDays.value);
  }
  function focusPage() {
    const page = dialog.value?.querySelector(".share-sheet, .qr-overlay");
    const target =
      page?.querySelector<HTMLInputElement>(
        'input:not([type="checkbox"]):not([disabled])',
      ) ?? page?.querySelector<HTMLButtonElement>("button:not([disabled])");
    target?.focus({ preventScroll: true });
  }
  onMounted(async () => {
    previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    dialog.value?.showModal();
    await nextTick();
    visible.value = true;
    await nextTick();
    focusPage();
  });
  onUnmounted(() => {
    alive = false;
    clearTimeout(toastTimer);
    document.body.style.overflow = previousOverflow;
    if (posterUrl.value) URL.revokeObjectURL(posterUrl.value);
  });
  return {
    dialog,
    fileArtwork,
    visible,
    view,
    busy,
    friendBusy,
    days,
    useCode,
    autoFill,
    extractionCode,
    codeDraft,
    codeError,
    error,
    toast,
    posterUrl,
    actionsDisabled,
    expiry,
    customDays,
    channels,
    randomCode,
    notify,
    placeholder,
    copy,
    openApp,
    showQr,
    moments,
    savePoster,
    channelClick,
    close,
    back,
    cancel,
    editCode,
    saveCode,
    selectExpiry,
    confirmCustomDays,
    focusPage,
  };
}
