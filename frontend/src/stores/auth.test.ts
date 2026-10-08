import { beforeEach, describe, expect, it, vi } from "vitest";
import { AxiosError } from "axios";

const api = vi.hoisted(() => ({
  getCurrentUser: vi.fn(),
  loginRequest: vi.fn(),
  logoutRequest: vi.fn(),
}));
vi.mock("../api/auth", () => api);

function storage() {
  const data = new Map<string, string>();
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => {
      data.set(key, value);
    },
    removeItem: (key: string) => {
      data.delete(key);
    },
  };
}

const user = {
  id: "9007199254740993",
  username: "tester",
  nickname: "Test",
  createdAt: "2026-09-28T00:00:00Z",
  vipLevel: "FREE",
  storageUsed: 0,
  storageLimit: 1024,
};
const key = "cendo-drive-auth";

beforeEach(() => {
  vi.resetModules();
  vi.clearAllMocks();
  vi.stubGlobal("localStorage", storage());
  vi.stubGlobal("sessionStorage", storage());
});

it.each([true,false])("updates the nickname without extending expiry or switching storage (remember=%s)",async remember=>{
  api.loginRequest.mockResolvedValue({token:"test-token",expiresInSeconds:86400,user});
  const {useAuth}=await import("./auth");const auth=useAuth();await auth.login({username:"tester",password:"password123"},remember);
  const target=remember ? localStorage : sessionStorage, other=remember ? sessionStorage : localStorage;
  const before=JSON.parse(target.getItem(key)!);auth.updateNickname("新昵称");const after=JSON.parse(target.getItem(key)!);
  expect(auth.user.value?.nickname).toBe("新昵称");expect(after.user.nickname).toBe("新昵称");expect(after.token).toBe(before.token);expect(after.expiresAt).toBe(before.expiresAt);expect(other.getItem(key)).toBeNull();
});
describe("auth session", () => {
  it("stores login only in sessionStorage unless remember is selected", async () => {
    api.loginRequest.mockResolvedValue({
      token: "test-token",
      expiresInSeconds: 86400,
      user,
    });
    const { useAuth, getToken } = await import("./auth");
    await useAuth().login(
      { username: "tester", password: "password123" },
      false,
    );
    expect(getToken()).toBe("test-token");
    expect(sessionStorage.getItem(key)).toContain("test-token");
    expect(localStorage.getItem(key)).toBeNull();
    await useAuth().login(
      { username: "tester", password: "password123" },
      true,
    );
    expect(localStorage.getItem(key)).toContain("test-token");
    expect(sessionStorage.getItem(key)).toBeNull();
  });

  it("checks a restored token with /me before trusting it", async () => {
    localStorage.setItem(
      key,
      JSON.stringify({
        token: "restored",
        user,
        expiresAt: Date.now() + 60000,
      }),
    );
    api.getCurrentUser.mockResolvedValue(user);
    const { useAuth } = await import("./auth");
    expect(useAuth().loggedIn.value).toBe(false);
    expect(await useAuth().ensureSession()).toBe(true);
    expect(useAuth().loggedIn.value).toBe(true);
    expect(api.getCurrentUser).toHaveBeenCalledTimes(1);
  });

  it("discards expired saved tokens without calling /me", async () => {
    localStorage.setItem(
      key,
      JSON.stringify({ token: "expired", user, expiresAt: Date.now() - 1 }),
    );
    const { useAuth } = await import("./auth");
    expect(await useAuth().ensureSession()).toBe(false);
    expect(localStorage.getItem(key)).toBeNull();
    expect(api.getCurrentUser).not.toHaveBeenCalled();
  });

  it("clears a rejected token but preserves it during a network failure", async () => {
    localStorage.setItem(
      key,
      JSON.stringify({
        token: "restored",
        user,
        expiresAt: Date.now() + 60000,
      }),
    );
    api.getCurrentUser.mockRejectedValueOnce(new Error("offline"));
    const { useAuth, getToken } = await import("./auth");
    expect(await useAuth().ensureSession()).toBe(false);
    expect(getToken()).toBe("restored");
    expect(useAuth().verificationError.value).toBe(true);
    api.getCurrentUser.mockRejectedValueOnce(
      new AxiosError("Unauthorized", "ERR_BAD_RESPONSE", undefined, undefined, {
        status: 401,
      } as never),
    );
    expect(await useAuth().ensureSession()).toBe(false);
    expect(getToken()).toBe("");
    expect(localStorage.getItem(key)).toBeNull();
  });

  it("ignores a late /me response after the user logs out", async () => {
    localStorage.setItem(
      key,
      JSON.stringify({
        token: "restored",
        user,
        expiresAt: Date.now() + 60000,
      }),
    );
    let finish!: (value: typeof user) => void;
    api.getCurrentUser.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    api.logoutRequest.mockResolvedValue(undefined);
    const { useAuth, getToken } = await import("./auth");
    const checking = useAuth().ensureSession();
    await useAuth().logout();
    finish(user);
    expect(await checking).toBe(false);
    expect(getToken()).toBe("");
    expect(useAuth().loggedIn.value).toBe(false);
  });

  it("removes the local session even if revocation fails", async () => {
    api.loginRequest.mockResolvedValue({
      token: "test-token",
      expiresInSeconds: 86400,
      user,
    });
    api.logoutRequest.mockRejectedValue(new Error("offline"));
    const { useAuth, getToken } = await import("./auth");
    await useAuth().login(
      { username: "tester", password: "password123" },
      true,
    );
    expect(await useAuth().logout()).toBe(false);
    expect(getToken()).toBe("");
    expect(localStorage.getItem(key)).toBeNull();
  });
});
