// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import TransferPage from './TransferPage.vue';
import { getFileDetails } from '../api/drive';

const tasks = vi.hoisted(() => [{ id: 'task-1', direction: 'upload' as const, name: 'photo.png', size: 2048, status: 'success' as const, progress: 100, createdAt: Date.now(), fileId: '42' as string | undefined }]);
vi.mock('../stores/transfers', () => ({
  useTransfers: () => ({ tasks, settings: { downloadLimit: 2 }, refresh: vi.fn(), clearFinished: vi.fn(), setDownloadLimit: vi.fn() }),
  isActive: () => false,
}));
vi.mock('../api/drive', () => ({
  getFileDetails: vi.fn(),
  driveErrorMessage: (_error: unknown, fallback: string) => fallback,
}));
vi.mock('./FilePreview.vue', () => ({ default: { props: ['file'], template: '<div class="preview">{{ file.name }}</div>' } }));

beforeEach(() => vi.clearAllMocks());

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

it('explains why old transfer records cannot preview a file', async () => {
  const wrapper = mount(TransferPage);
  await wrapper.get('.transfer-tabs button:nth-child(2)').trigger('click');
  tasks[0]!.fileId = undefined;
  await wrapper.get('button[aria-label="查看 photo.png 的文件内容"]').trigger('click');
  expect(wrapper.get('[role="alert"]').text()).toContain('没有关联文件');
  expect(getFileDetails).not.toHaveBeenCalled();
  tasks[0]!.fileId = '42';
  wrapper.unmount();
});
