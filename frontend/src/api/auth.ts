import http from './http'

export interface LoginPayload { account: string; password: string; remember: boolean }
export interface MockAccount { id: string; username: string; passwordHash: string }
async function hashPassword(password: string) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(password))
  return [...new Uint8Array(bytes)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

export interface LoginResult {
  token: string
  user: { id: string; name: string; avatar: string }
}

export async function loginRequest(payload: LoginPayload): Promise<LoginResult> {
  if (import.meta.env.VITE_API_BASE_URL) {
    const { data } = await http.post<LoginResult>('/auth/login', payload)
    return data
  }

  await new Promise((resolve) => setTimeout(resolve, 650))
  if (!payload.account.trim() || !payload.password.trim()) throw new Error('请输入账号和密码')
  const accounts = JSON.parse(localStorage.getItem('cendo-drive-accounts') || '[]') as MockAccount[]
  const registered = accounts.find((item) => item.username === payload.account.trim())
  if (registered && registered.passwordHash !== await hashPassword(payload.password)) throw new Error('用户名或密码错误')
  return {
    token: `demo-${Date.now()}`,
    user: { id: '10001', name: payload.account.includes('@') ? payload.account.split('@')[0] : payload.account, avatar: '' },
  }
}

export async function registerRequest(username: string, password: string) {
  await new Promise((resolve) => setTimeout(resolve, 500))
  if (!/^[\w\u4e00-\u9fa5]{3,20}$/.test(username)) throw new Error('用户名须为 3–20 位字母、数字、下划线或中文')
  if (password.length < 6) throw new Error('密码至少需要 6 位')
  const accounts = JSON.parse(localStorage.getItem('cendo-drive-accounts') || '[]') as MockAccount[]
  if (accounts.some((item) => item.username === username)) throw new Error('用户名已存在')
  accounts.push({ id: `${Date.now()}`, username, passwordHash: await hashPassword(password) })
  localStorage.setItem('cendo-drive-accounts', JSON.stringify(accounts))
}
