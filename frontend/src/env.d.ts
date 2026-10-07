/// <reference types="vite/client" />

// Support standalone TypeScript language servers; vue-tsc supplies precise SFC types.
declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent;
  export default component;
}
