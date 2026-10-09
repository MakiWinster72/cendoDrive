import http from './http';
import type { TransferTask } from '../stores/transfers';

export async function listTransferRecords(): Promise<TransferTask[]> {
  return (await http.get<TransferTask[]>('/transfers')).data;
}
export async function saveTransferRecord(task: TransferTask): Promise<void> {
  await http.post('/transfers', task);
}
export async function deleteTransferRecord(id: string): Promise<void> {
  await http.delete(`/transfers/${encodeURIComponent(id)}`);
}
export async function clearTransferRecords(direction: TransferTask['direction']): Promise<void> {
  await http.delete('/transfers', { params: { direction } });
}
