import DOMPurify from "dompurify";

/** Strict local-only document markup: no scripts, forms, remote resources or navigable links. */
export function sanitizeDocument(html: string): string {
  const clean = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ["p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i", "u", "s", "del", "sup", "sub", "ul", "ol", "li", "blockquote", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "img", "span", "a"],
    ALLOWED_ATTR: ["alt", "src", "colspan", "rowspan", "start"],
    ALLOW_DATA_ATTR: false,
  });
  const template = document.createElement("template");
  template.innerHTML = clean;
  template.content.querySelectorAll("img").forEach((image) => {
    if (!/^data:image\/(png|jpeg|gif|webp|bmp);base64,/i.test(image.getAttribute("src") || "")) image.remove();
  });
  return template.innerHTML;
}
export async function renderMarkdown(text: string): Promise<string> {
  const { marked } = await import("marked");
  return sanitizeDocument(marked.parse(text, { async: false }));
}
export async function renderDocx(buffer: ArrayBuffer): Promise<string> {
  const mammoth = await import("mammoth");
  const result = await mammoth.convertToHtml({ arrayBuffer: buffer });
  return sanitizeDocument(result.value);
}
