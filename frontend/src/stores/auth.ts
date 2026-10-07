import axios from "axios";
import { computed, reactive } from "vue";
import {
  getCurrentUser,
  loginRequest,
  logoutRequest,
  type LoginPayload,
  type UserResponse,
} from "../api/auth";

const STORAGE_KEY = "cendo-drive-auth";

type SavedSession = { token: string; user: UserResponse; expiresAt: number };
function readStorage(storage: Storage): SavedSession | null {
  try {
    const value = storage.getItem(STORAGE_KEY);
    if (!value) return null;
    const saved: unknown = JSON.parse(value);
    if (
      typeof saved === "object" &&
      saved !== null &&
      "token" in saved &&
      typeof saved.token === "string" &&
      saved.token &&
      "expiresAt" in saved &&
      typeof saved.expiresAt === "number" &&
      saved.expiresAt > Date.now()
    ) {
      return saved as SavedSession;
    }
  } catch {
    /* corrupt or unavailable storage */
  }
  try {
    storage.removeItem(STORAGE_KEY);
  } catch {
    /* storage unavailable */
  }
  return null;
}

const session = readStorage(sessionStorage);
const local = session ? null : readStorage(localStorage);
const initial = session ?? local;
let verified = false;
let verifying: Promise<boolean> | null = null;

const state = reactive({
  token: initial?.token ?? "",
  user: initial?.user ?? (null as UserResponse | null),
  expiresAt: initial?.expiresAt ?? 0,
  verificationError: false,
});

function clearSession() {
  state.token = "";
  state.user = null;
  state.expiresAt = 0;
  state.verificationError = false;
  verified = false;
  for (const storage of [localStorage, sessionStorage]) {
    try {
      storage.removeItem(STORAGE_KEY);
    } catch {
      /* storage unavailable */
    }
  }
}

function saveSession(
  token: string,
  user: UserResponse,
  expiresInSeconds: number,
  remember: boolean,
) {
  clearSession();
  state.token = token;
  state.user = user;
  state.expiresAt = Date.now() + expiresInSeconds * 1000;
  verified = true;
  const storage = remember ? localStorage : sessionStorage;
  try {
    storage.setItem(
      STORAGE_KEY,
      JSON.stringify({ token, user, expiresAt: state.expiresAt }),
    );
  } catch {
    /* session stays in memory when storage is blocked */
  }
}

async function ensureSession(): Promise<boolean> {
  if (!state.token) return false;
  if (state.expiresAt <= Date.now()) {
    clearSession();
    return false;
  }
  if (verified) return true;
  if (verifying) return verifying;
  const tokenBeingChecked = state.token;
  verifying = (async () => {
    try {
      const user = await getCurrentUser();
      if (state.token !== tokenBeingChecked) return false;
      state.user = user;
      state.verificationError = false;
      verified = true;
      return true;
    } catch (error) {
      if (state.token !== tokenBeingChecked) return false;
      if (axios.isAxiosError(error) && error.response?.status === 401)
        clearSession();
      else state.verificationError = true;
      return false;
    } finally {
      verifying = null;
    }
  })();
  return verifying;
}

export function getToken(): string {
  return state.token;
}
export function invalidateSession(): void {
  clearSession();
}

export function useAuth() {
  async function login(payload: LoginPayload, remember: boolean) {
    const result = await loginRequest(payload);
    saveSession(result.token, result.user, result.expiresInSeconds, remember);
  }

  async function logout(): Promise<boolean> {
    let revoked = false;
    try {
      await logoutRequest();
      revoked = true;
    } catch {
      /* local session must still be removed */
    } finally {
      clearSession();
    }
    return revoked;
  }

  return {
    user: computed(() => state.user),
    loggedIn: computed(() => Boolean(state.token) && verified),
    verificationError: computed(() => state.verificationError),
    ensureSession,
    login,
    logout,
  };
}
