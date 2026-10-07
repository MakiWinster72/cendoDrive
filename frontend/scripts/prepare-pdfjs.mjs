import { cpSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
const require = createRequire(import.meta.url);
const source = dirname(require.resolve("pdfjs-dist/package.json"));
const destination = fileURLToPath(new URL("../public/pdfjs/", import.meta.url));
mkdirSync(destination, { recursive: true });
for (const directory of ["cmaps", "standard_fonts", "wasm"]) {
  cpSync(resolve(source, directory), resolve(destination, directory), { recursive: true, force: true });
}
console.log("PDF 本地字体与解码资源已准备");
