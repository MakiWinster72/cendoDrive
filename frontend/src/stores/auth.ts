import { computed, reactive } from 'vue'
import { loginRequest, type LoginPayload } from '../api/auth'

const STORAGE_KEY = 'cendo-drive-auth'

export interface User {
  id: string
  name: string
  avatar: string
}

interface AuthState {
  token: string
  user: User | null
}

function readState(): AuthState {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null') as AuthState | null
    if (saved?.token && saved.user) return saved
  } catch {
    localStorage.removeItem(STORAGE_KEY)
  }
  return { token: '', user: null }
}

const state = reactive<AuthState>(readState())

function persist() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({ token: state.token, user: state.user }))
}

export function getToken() {
  return state.token || JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null')?.token || ''
}

export function isAuthenticated() {
  return Boolean(getToken())
}

export function useAuth() {
  const login = async (payload: LoginPayload) => {
    const result = await loginRequest(payload)
    state.token = result.token
    state.user = result.user
    persist()
  }

  const logout = () => {
    state.token = ''
    state.user = null
    localStorage.removeItem(STORAGE_KEY)
  }

  return {
    user: computed(() => state.user),
    loggedIn: computed(() => Boolean(state.token)),
    login,
    logout,
  }
}
