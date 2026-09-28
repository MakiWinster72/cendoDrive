import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import http from './http'
import { authErrorMessage, fieldErrors, loginRequest, registerRequest } from './auth'

vi.mock('./http', () => ({ default: { post: vi.fn(), get: vi.fn() } }))

beforeEach(() => vi.clearAllMocks())

describe('auth API contract', () => {
  it('sends the backend login fields without remember or demo fallback', async () => {
    vi.mocked(http.post).mockResolvedValue({ data: { token: 'opaque', expiresInSeconds: 86400, user: { id: '1' } } })
    const result = await loginRequest({ username: 'tester', password: 'password123' })
    expect(http.post).toHaveBeenCalledWith('/auth/login', { username: 'tester', password: 'password123' })
    expect(result.token).toBe('opaque')
  })

  it('sends registration without asking the client to create a token', async () => {
    vi.mocked(http.post).mockResolvedValue({ data: { id: '1', username: 'tester' } })
    const result = await registerRequest({ username: 'tester', password: 'password123' })
    expect(http.post).toHaveBeenCalledWith('/auth/register', { username: 'tester', password: 'password123' })
    expect(result).not.toHaveProperty('token')
  })

  it('distinguishes field validation, duplicate user, rate limits and network errors', () => {
    function response(status: number, fields: Record<string, string> = {}) {
      return new AxiosError('HTTP error', 'ERR_BAD_RESPONSE', undefined, undefined,
        { status, data: { code: 'TEST', message: 'internal details', fields } } as never)
    }
    expect(fieldErrors(response(400, { username: 'invalid' }))).toEqual({ username: 'invalid' })
    expect(authErrorMessage(response(409), '注册失败')).toBe('用户名已存在')
    expect(authErrorMessage(response(429), '登录失败')).toContain('过于频繁')
    expect(authErrorMessage(new AxiosError('offline'), '登录失败')).toContain('网络连接失败')
    expect(authErrorMessage(response(500), '登录失败')).toBe('登录失败')
  })
})
