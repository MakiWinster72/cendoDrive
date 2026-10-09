// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import ShareList from './ShareList.vue';
import MobileMyShares from './MobileMyShares.vue';
const share = { id: '1', token: 'abc', fileId: '42', fileName: 'test.md', kind: 'file' as const, size: 10, createdAt: '2026-01-01', expiresAt: '2030-01-01', status: 'ACTIVE' as const };
describe('my shares preview', () => {
  it('emits the owner file record from desktop preview', async () => {
    const page = mount(ShareList, { props: { shares: [share] } });
    await page.findAll('button').find(b => b.text() === '预览')!.trigger('click');
    expect(page.emitted('preview')).toEqual([[share]]); page.unmount();
  });
  it('keeps mobile preview separate from the link dialog and selection', async () => {
    const page = mount(MobileMyShares, { props: { shares: [share], loading: false, error: '' } });
    await page.get('.my-shares-preview').trigger('click');
    expect(page.emitted('preview')).toEqual([[share]]); expect(page.emitted('open')).toBeUndefined();
    await page.get('.my-shares-row').trigger('click'); expect(page.emitted('open')).toEqual([[share]]);
    await page.get('.my-shares-select').trigger('click'); expect(page.find('.my-shares-preview').exists()).toBe(false); page.unmount();
  });
});
