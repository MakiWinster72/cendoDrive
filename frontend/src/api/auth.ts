import http from './http'

export interface LoginPayload { account: string; password: string; remember: boolean }

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
  return {
    token: `demo-${Date.now()}`,
    user: { id: '10001', name: payload.account.includes('@') ? payload.account.split('@')[0] : payload.account, avatar: '' },
  }
}
