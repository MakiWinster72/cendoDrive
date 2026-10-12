// @vitest-environment jsdom
import { mount } from "@vue/test-utils";
import { expect, it, vi } from "vitest";
import LoginView from "./LoginView.vue";
const login = vi.hoisted(() => vi.fn());
vi.mock("vue-router", () => ({
  useRoute: () => ({ query: { accountDeleted: "1" } }),
  useRouter: () => ({ replace: vi.fn() }),
}));
vi.mock("../stores/auth", () => ({
  useAuth: () => ({ login, verificationError: { value: false } }),
}));
it("does not show the account recovery entry on the login page", () => {
  const w = mount(LoginView, { global: { stubs: { RouterLink: true } } });
  expect(w.text()).not.toContain("恢复 7 天内注销的账号");
  expect(
    w.findAll("button").some((b) => b.text() === "恢复 7 天内注销的账号"),
  ).toBe(false);
  w.unmount();
});
