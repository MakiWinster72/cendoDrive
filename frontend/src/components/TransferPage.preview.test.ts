// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import TransferPage from './TransferPage.vue';
import { getFileDetails, searchFiles } from '../api/drive';

const tasks = vi.hoisted(() => [{ id: 'task-1', direction: 'upload' as const, name: 'photo.png', size: 2048, status: 'success' as const, progress: 100, createdAt: Date.now(), fileId: '42' as string | undefined }]);
const linkFile = vi.hoisted(() => vi.fn());
const setDownloadLimit = vi.hoisted(() => vi.fn());
vi.mock('../stores/transfers', () => ({
  useTransfers: () => ({ tasks, settings: { downloadLimit: 2 }, refresh: vi.fn(), clearFinished: vi.fn(), setDownloadLimit, linkFile }),
  isActive: () => false,
}));
vi.mock('../api/drive', () => ({
  getFileDetails: vi.fn(),
  searchFiles: vi.fn(),
  driveErrorMessage: (_error: unknown, fallback: string) => fallback,
}));
vi.mock('./FilePreview.vue', () => ({ default: { props: ['file'], template: '<div class="preview">{{ file.name }}</div>' } }));

beforeEach(() => { vi.clearAllMocks(); tasks[0]!.fileId = '42'; });

it('opens an in-page download limit menu and applies the selected value', async () => {
  const wrapper = mount(TransferPage);
  expect(wrapper.find('select[aria-label="同时下载数"]').exists()).toBe(false);
  await wrapper.get('button[aria-label="同时下载数"]').trigger('click');
  expect(wrapper.get('button[aria-label="同时下载数"]').attributes('aria-expanded')).toBe('true');
  await wrapper.get('button[aria-label="同时下载 3 个"]').trigger('click');
  expect(setDownloadLimit).toHaveBeenCalledWith(3);
  expect(wrapper.find('.download-limit-options').exists()).toBe(false);
  wrapper.unmount();
});

it('opens the actual uploaded file from its transfer record', async () => {
  vi.mocked(getFileDetails).mockResolvedValue({ file: { id: '42', name: 'photo.png', kind: 'file', size: 2048, parentId: null, updatedAt: '', deletedAt: null }, createdAt: '', path: '', contentSize: 2048, fileCount: 0, folderCount: 0 });
  const wrapper = mount(TransferPage);
  await wrapper.get('.transfer-tabs button:nth-child(2)').trigger('click');
  await wrapper.get('button[aria-label="查看 photo.png 的文件内容"]').trigger('click');
  await flushPromises();
  expect(getFileDetails).toHaveBeenCalledWith('42');
  expect(wrapper.get('.preview').text()).toBe('photo.png');
  wrapper.unmount();
});

it('finds and links a uniquely matching file for a legacy upload', async () => {
  tasks[0]!.fileId = undefined;
  vi.mocked(searchFiles).mockResolvedValue({ items: [{ file: { id: '42', name: 'photo.png', kind: 'file', size: 2048, parentId: null, updatedAt: '', deletedAt: null }, ancestors: [], path: '/' }], total: 1, page: 0, size: 100 });
  vi.mocked(getFileDetails).mockResolvedValue({ file: { id: '42', name: 'photo.png', kind: 'file', size: 2048, parentId: null, updatedAt: '', deletedAt: null }, createdAt: '', path: '', contentSize: 2048, fileCount: 0, folderCount: 0 });
  const wrapper = mount(TransferPage);
  await wrapper.get('.transfer-tabs button:nth-child(2)').trigger('click');
  await wrapper.get('button[aria-label="查看 photo.png 的文件内容"]').trigger('click');
  await flushPromises();
  expect(searchFiles).toHaveBeenCalledWith(expect.objectContaining({ q: 'photo.png', scope: 'all' }));
  expect(wrapper.get('.preview').text()).toBe('photo.png');
  expect(linkFile).toHaveBeenCalledWith('task-1', '42');
  wrapper.unmount();
});

it('does not guess when multiple files match a legacy upload', async () => {
  tasks[0]!.fileId = undefined;
  const file = { id: '42', name: 'photo.png', kind: 'file' as const, size: 2048, parentId: null, updatedAt: '', deletedAt: null };
  vi.mocked(searchFiles).mockResolvedValue({ items: [{ file, ancestors: [], path: '/' }, { file: { ...file, id: '43' }, ancestors: [], path: '/copies' }], total: 2, page: 0, size: 100 });
  const wrapper = mount(TransferPage);
  await wrapper.get('.transfer-tabs button:nth-child(2)').trigger('click');
  await wrapper.get('button[aria-label="查看 photo.png 的文件内容"]').trigger('click');
  await flushPromises();
  expect(wrapper.get('[role="alert"]').text()).toContain('多个同名同大小文件');
  expect(getFileDetails).not.toHaveBeenCalled();
  expect(linkFile).not.toHaveBeenCalled();
  wrapper.unmount();
});
