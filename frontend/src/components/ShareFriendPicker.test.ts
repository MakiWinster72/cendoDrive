// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import ShareFriendPicker from './ShareFriendPicker.vue';
import { listRooms, searchUsers, openDirect, sendFileMessage } from '../api/chat';
vi.mock('../api/chat', () => ({ listRooms: vi.fn(), searchUsers: vi.fn(), openDirect: vi.fn(), sendFileMessage: vi.fn() }));
const file = { id: '42', name: '报告.pdf', kind: 'file' as const, size: 500, parentId: null, updatedAt: '2026-01-01', deletedAt: null };
const room = { id: 'direct-1', name: '小林', description: '', group: false, searchable: false };
let wrapper: VueWrapper;
async function open() { wrapper = mount(ShareFriendPicker, { props: { file } }); await flushPromises(); }
async function search(value: string) { await wrapper.get('input').setValue(value); await vi.advanceTimersByTimeAsync(250); await flushPromises(); }
beforeEach(() => {
  vi.clearAllMocks(); vi.useFakeTimers(); vi.mocked(listRooms).mockResolvedValue([room, { ...room, id: 'group', group: true }]);
  vi.mocked(searchUsers).mockResolvedValue([{ id: 2, username: 'lin', nickname: '林同学' }]);
  vi.mocked(openDirect).mockResolvedValue(room); vi.mocked(sendFileMessage).mockResolvedValue(undefined);
});
afterEach(() => { wrapper?.unmount(); vi.useRealTimers(); });
describe('share friend picker', () => {
  it('lists direct contacts only and sends the selected file through the actual chat API', async () => {
    await open(); expect(wrapper.findAll('.friends-list > button')).toHaveLength(1);
    expect(wrapper.get('.friend-send-button').attributes('disabled')).toBeDefined();
    await wrapper.get('.friends-list > button').trigger('click'); await wrapper.get('.friend-send-button').trigger('click'); await flushPromises();
    expect(sendFileMessage).toHaveBeenCalledWith('direct-1', '42'); expect(openDirect).not.toHaveBeenCalled(); expect(wrapper.emitted('sent')).toEqual([['小林']]);
  });
  it('searches users, opens a direct conversation, and only confirms after file-message success', async () => {
    await open(); await search('林'); expect(searchUsers).toHaveBeenCalledWith('林');
    await wrapper.get('.friends-list > button').trigger('click'); await wrapper.get('.friend-send-button').trigger('click'); await flushPromises();
    expect(openDirect).toHaveBeenCalledWith(2); expect(sendFileMessage).toHaveBeenCalledWith('direct-1', '42'); expect(wrapper.emitted('sent')).toEqual([['林同学']]);
  });
  it('ignores stale search responses and resets selections when the search changes', async () => {
    let resolve!: (value: Awaited<ReturnType<typeof searchUsers>>) => void;
    vi.mocked(searchUsers).mockImplementationOnce(() => new Promise(r => { resolve = r; }));
    await open(); await search('旧'); await search('新');
    resolve([{ id: 99, username: 'stale', nickname: '过期结果' }]); await flushPromises();
    expect(wrapper.text()).not.toContain('过期结果'); expect(wrapper.text()).toContain('林同学');
    await wrapper.get('.friends-list > button').trigger('click'); await wrapper.get('input').setValue('再搜索');
    expect(wrapper.get('.friend-send-button').attributes('disabled')).toBeDefined();
  });
  it('prevents duplicate sends and leaves a failed send retryable without pretending success', async () => {
    let reject!: (reason: unknown) => void; vi.mocked(sendFileMessage).mockImplementationOnce(() => new Promise((_resolve,r) => { reject = r; }));
    await open(); await wrapper.get('.friends-list > button').trigger('click'); await wrapper.get('.friend-send-button').trigger('click');
    await wrapper.get('.friend-send-button').trigger('click'); expect(sendFileMessage).toHaveBeenCalledTimes(1);
    reject(new Error('offline')); await flushPromises(); expect(wrapper.emitted('sent')).toBeUndefined(); expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    await wrapper.get('.friend-send-button').trigger('click'); await flushPromises(); expect(wrapper.emitted('sent')).toEqual([['小林']]);
  });
  it('shows empty and failed lookup states with retry', async () => {
    vi.mocked(listRooms).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([]);
    await open(); expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    await wrapper.get('.friends-state button').trigger('click'); await flushPromises(); expect(wrapper.text()).toContain('暂无最近联系');
    vi.mocked(searchUsers).mockResolvedValue([]); await search('不存在'); expect(wrapper.text()).toContain('没有找到好友');
  });
});
