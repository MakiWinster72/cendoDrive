import { beforeEach, describe, expect, it, vi } from 'vitest'
import http from './http'
import { createFolder, listFiles, moveFile, renameFile } from './drive'

vi.mock('./http', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }))

beforeEach(() => vi.clearAllMocks())

describe('drive API contract', () => {
  it('lists root and child directories with the expected query', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: [] })
    await listFiles(null)
    await listFiles('12')
    expect(http.get).toHaveBeenNthCalledWith(1, '/files', { params: {} })
    expect(http.get).toHaveBeenNthCalledWith(2, '/files', { params: { parentId: '12' } })
  })

  it('uses dedicated folder, rename and move endpoints', async () => {
    vi.mocked(http.post).mockResolvedValue({ data: { id: '1' } })
    vi.mocked(http.put).mockResolvedValue({ data: { id: '1' } })
    await createFolder('资料', null)
    await renameFile('1', '文档')
    await moveFile('1', '2')
    expect(http.post).toHaveBeenCalledWith('/files/folder', { name: '资料', parentId: null })
    expect(http.put).toHaveBeenNthCalledWith(1, '/files/1/rename', { name: '文档' })
    expect(http.put).toHaveBeenNthCalledWith(2, '/files/1/move', { parentId: '2' })
  })
})
