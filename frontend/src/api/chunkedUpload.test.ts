import { beforeEach, describe, expect, it, vi } from 'vitest'
import http from './http'
import { CHUNK_SIZE, uploadFileInChunks } from './chunkedUpload'
import type { DriveItem } from '../stores/drive'

vi.mock('./http', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

beforeEach(() => vi.clearAllMocks())

describe('chunked upload API contract', () => {
  it('resumes from server-reported chunks, sends only missing chunks, then merges', async () => {
    const file = Object.assign(new Blob([new Uint8Array(CHUNK_SIZE + 13)]), {
      name: 'archive.zip',
    }) as File
    const signal = new AbortController().signal
    const onPhase = vi.fn()
    const onProgress = vi.fn()
    const uploadedItem = {
      id: '42', name: file.name, kind: 'file', size: file.size,
      parentId: '7', updatedAt: '2026-09-30T00:00:00Z', deletedAt: null,
    } satisfies DriveItem

    vi.mocked(http.post)
      .mockResolvedValueOnce({ data: { uploadId: 'upload-1', chunkSize: CHUNK_SIZE, totalChunks: 2 } })
      .mockResolvedValueOnce({ data: { uploadId: 'upload-1', chunkNumber: 1, received: true } })
      .mockResolvedValueOnce({ data: uploadedItem })
    vi.mocked(http.get).mockResolvedValueOnce({
      data: { uploadId: 'upload-1', totalChunks: 2, uploadedChunks: [0] },
    })

    const result = await uploadFileInChunks({
      file,
      parentId: '7',
      signal,
      onPhase,
      onProgress,
    })

    expect(result).toEqual(uploadedItem)
    expect(http.post).toHaveBeenCalledTimes(3)
    expect(http.post).toHaveBeenNthCalledWith(1, '/upload/init', expect.objectContaining({
      fileName: 'archive.zip',
      fileSize: file.size,
      parentId: '7',
      chunkSize: CHUNK_SIZE,
      totalChunks: 2,
      fileHash: expect.stringMatching(/^[a-f0-9]{32}$/),
    }), { signal })

    expect(http.get).toHaveBeenCalledWith('/upload/upload-1/status', { signal })
    const chunkForm = vi.mocked(http.post).mock.calls[1][1] as FormData
    expect(vi.mocked(http.post).mock.calls[1][0]).toBe('/upload/chunk')
    expect(chunkForm.get('chunkNumber')).toBe('1')
    expect(chunkForm.get('totalChunks')).toBe('2')
    expect((chunkForm.get('chunk') as Blob).size).toBe(13)
    expect(vi.mocked(http.post).mock.calls[2][0]).toBe('/upload/merge')
    expect(onPhase).toHaveBeenNthCalledWith(1, 'hashing')
    expect(onPhase).toHaveBeenNthCalledWith(2, 'uploading')
    expect(onProgress).toHaveBeenLastCalledWith(100)
  })
})
