// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ShareLinkDialog from './ShareLinkDialog.vue';
const share = { id: '1', token: 'abc', fileId: '42', fileName: 'long.md', kind: 'file' as const, size: 10, createdAt: '2026-01-01', expiresAt: '2030-01-01', status: 'ACTIVE' as const, hasExtractionCode: true, extractionCode: 'Ab12' };
afterEach(() => vi.unstubAllGlobals());
describe('share link dialog', () => {
  it('teleports outside constrained parents and copies only the code', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined); vi.stubGlobal('navigator', { clipboard: { writeText } });
    const page: VueWrapper = mount(ShareLinkDialog, { props: { share } });
    const dialog = document.body.querySelector('.share-dialog')!;
    expect(dialog).not.toBeNull();
    expect((dialog.querySelector('#share-code-value') as HTMLInputElement).value).toBe('Ab12');
    (Array.from(dialog.querySelectorAll('button')).find(b => b.textContent?.includes('复制提取码')) as HTMLButtonElement).click(); await flushPromises();
    expect(writeText).toHaveBeenCalledWith('Ab12'); expect(dialog.textContent).toContain('已复制提取码');
    await page.setProps({ share: { ...share, id: '2', extractionCode: undefined } });
    expect(document.body.querySelector('#share-code-value')).toBeNull(); expect(dialog.textContent).toContain('不再回显'); page.unmount();
  });
  it('reports clipboard errors and blocks inactive links', async () => {
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } });
    const page: VueWrapper = mount(ShareLinkDialog, { props: { share }, global: { stubs: { teleport: true } } });
    await page.findAll('button').find(b => b.text() === '复制提取码')!.trigger('click'); await flushPromises();
    expect(page.get('[role=alert]').text()).toContain('手动复制');
    await page.setProps({ share: { ...share, status: 'CANCELLED' } });
    expect(page.findAll('.share-link-value button').every(b => b.attributes('disabled') !== undefined)).toBe(true); page.unmount();
  });
});
