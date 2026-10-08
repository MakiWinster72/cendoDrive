import { beforeEach, describe, expect, it, vi } from 'vitest'
import http from './http'
import { uploadFileChunked } from './upload'

vi.mock('./http', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))

const UPLOAD_ID = 'a'.repeat(64)
const CHUNK_SIZE = 10
const baseInit = { uploadId: UPLOAD_ID, chunkSize: CHUNK_SIZE, totalChunks: 3, uploadedIndexes: [] as number[], status: 'UPLOADING' }

beforeEach(() => vi.clearAllMocks())

function fileOf(size: number, name = 'movie.bin') {
  return new File([new Uint8Array(size)], name)
}

function routePost(handler: (url: string, body: unknown) => unknown) {
  vi.mocked(http.post).mockImplementation((async (url: string, body: unknown) => handler(url, body)) as never)
}

function postedUrls() {
  return vi.mocked(http.post).mock.calls.map((call) => call[0])
}

function chunkForms() {
  return vi.mocked(http.post).mock.calls
    .filter((call) => call[0] === '/upload/chunk')
    .map((call) => call[1] as FormData)
}

describe('chunked upload orchestration', () => {
  it('splits the file, uploads every chunk and merges once', async () => {
    routePost((url) => {
      if (url === '/upload/init') return { data: baseInit }
      if (url === '/upload/chunk') return { data: { uploadId: UPLOAD_ID, receivedIndexes: [], received: 1, totalChunks: 3 } }
      return { data: { id: '9', name: 'movie.bin', kind: 'file', size: 26 } }
    })
    const progress: number[] = []

    const item = await uploadFileChunked({
      file: fileOf(26),
      parentId: '4',
      signal: new AbortController().signal,
      onProgress: (value) => progress.push(value),
    })

    expect(item.id).toBe('9')
    expect(postedUrls().filter((url) => url === '/upload/chunk')).toHaveLength(3)
    expect(postedUrls().filter((url) => url === '/upload/merge')).toHaveLength(1)
    expect(chunkForms().map((form) => form.get('index'))).toEqual(['0', '1', '2'])
    expect(chunkForms()[0].get('uploadId')).toBe(UPLOAD_ID)
    expect(progress[progress.length - 1]).toBe(100)
  })

  it('resumes by skipping chunks the server already holds', async () => {
    routePost((url) => {
      if (url === '/upload/init') return { data: { ...baseInit, uploadedIndexes: [0, 2] } }
      if (url === '/upload/chunk') return { data: { uploadId: UPLOAD_ID, receivedIndexes: [], received: 3, totalChunks: 3 } }
      return { data: { id: '9' } }
    })

    await uploadFileChunked({
      file: fileOf(26),
      parentId: null,
      signal: new AbortController().signal,
      onProgress: () => {},
    })

    expect(chunkForms().map((form) => form.get('index'))).toEqual(['1'])
  })

  it('sends a sha256 hash for the whole file and for every chunk', async () => {
    routePost((url) => {
      if (url === '/upload/init') return { data: baseInit }
      if (url === '/upload/chunk') return { data: { uploadId: UPLOAD_ID, receivedIndexes: [], received: 1, totalChunks: 3 } }
      return { data: { id: '9' } }
    })

    await uploadFileChunked({
      file: fileOf(26),
      parentId: null,
      signal: new AbortController().signal,
      onProgress: () => {},
    })

    const initBody = vi.mocked(http.post).mock.calls.find((call) => call[0] === '/upload/init')?.[1] as { fileHash: string }
    expect(initBody.fileHash).toMatch(/^[0-9a-f]{64}$/)
    for (const form of chunkForms()) expect(String(form.get('chunkHash'))).toMatch(/^[0-9a-f]{64}$/)
  })

  it('merges directly when the server reports a completed session', async () => {
    routePost((url) => {
      if (url === '/upload/init') return { data: { ...baseInit, status: 'COMPLETED' } }
      return { data: { id: '9' } }
    })

    const item = await uploadFileChunked({
      file: fileOf(26),
      parentId: null,
      signal: new AbortController().signal,
      onProgress: () => {},
    })

    expect(item.id).toBe('9')
    expect(postedUrls().filter((url) => url === '/upload/chunk')).toHaveLength(0)
    expect(postedUrls().filter((url) => url === '/upload/merge')).toHaveLength(1)
  })

  it('stops on the first chunk failure without merging', async () => {
    routePost((url) => {
      if (url === '/upload/init') return { data: baseInit }
      if (url === '/upload/chunk') throw new Error('chunk rejected')
      return { data: { id: '9' } }
    })

    await expect(uploadFileChunked({
      file: fileOf(26),
      parentId: null,
      signal: new AbortController().signal,
      onProgress: () => {},
    })).rejects.toThrow('chunk rejected')

    expect(postedUrls()).not.toContain('/upload/merge')
  })

  it('rejects immediately when the upload is already cancelled', async () => {
    const controller = new AbortController()
    controller.abort()

    await expect(uploadFileChunked({
      file: fileOf(26),
      parentId: null,
      signal: controller.signal,
      onProgress: () => {},
    })).rejects.toThrow()

    expect(http.post).not.toHaveBeenCalled()
  })
})
