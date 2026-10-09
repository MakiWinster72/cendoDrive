// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useDrive } from './drive';
import { listShares } from '../api/shares';
vi.mock('../api/shares', async original => ({ ...await original<typeof import('../api/shares')>(), listShares: vi.fn() }));
const record = { id:'1', token:'abc', fileId:'42', fileName:'test.md', kind:'file' as const, size:10, createdAt:'2026-01-01', expiresAt:'2030-01-01', status:'ACTIVE' as const, hasExtractionCode:true };
afterEach(() => { useDrive().reset(); vi.clearAllMocks(); });
describe('share extraction code memory', () => {
  it('preserves the known code when reopening my shares, without persisting it', async () => {
    const drive = useDrive(); drive.state.shares = [{ ...record, extractionCode:'Ab12' }];
    vi.mocked(listShares).mockResolvedValue([record]); await drive.loadShares();
    expect(drive.state.shares[0]?.extractionCode).toBe('Ab12');
    drive.reset(); await drive.loadShares(); expect(drive.state.shares[0]?.extractionCode).toBeUndefined();
  });
  it('does not attach a code to a different share or an unprotected share', async () => {
    const drive = useDrive(); drive.state.shares = [{ ...record, extractionCode:'Ab12' }];
    vi.mocked(listShares).mockResolvedValue([{ ...record, hasExtractionCode:false }, { ...record, id:'2' }]);
    await drive.loadShares(); expect(drive.state.shares.every(s => !s.extractionCode)).toBe(true);
  });
});
