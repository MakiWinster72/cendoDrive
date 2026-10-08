import axios from 'axios';
import http from './http';
import { fetchPreviewFile } from './preview';
import type { DriveItemResponse } from './drive';
export const sendFileMessage = async (room: string, fileId: string) => { await http.post(`/chat/rooms/${room}/files`, { fileId }); };
const chatFilePath = (room: string, messageId: number) => `/chat/rooms/${encodeURIComponent(room)}/files/${messageId}`;
export const saveChatFile = async (room: string, messageId: number, parentId: string | null = null) => (await http.post<DriveItemResponse>(`${chatFilePath(room,messageId)}/save`, {parentId}, {timeout:0})).data;
export const getChatFile = async (room: string, messageId: number, signal?: AbortSignal) => (await http.get<DriveItemResponse>(chatFilePath(room,messageId),{signal})).data;
export const previewChatFile = (room: string, messageId: number, file: DriveItemResponse, signal: AbortSignal) => fetchPreviewFile(file,signal,`${chatFilePath(room,messageId)}/download`);
export async function downloadChatFile(room: string, messageId: number, name: string): Promise<void> {
  const {data} = await http.get<Blob>(`${chatFilePath(room,messageId)}/download`, {responseType:'blob',timeout:0}).catch(async (error: unknown) => {
    if(axios.isAxiosError(error) && error.response?.data instanceof Blob && error.response.data.size <= 64*1024) {
      try { error.response.data = JSON.parse(await error.response.data.text()); } catch { /* Keep non-JSON failures. */ }
    }
    throw error;
  });
  const url=URL.createObjectURL(data), link=document.createElement('a');
  link.href=url; link.download=name; document.body.appendChild(link); link.click(); link.remove();
  window.setTimeout(()=>URL.revokeObjectURL(url),60000);
}
export interface Person { id: number; username: string; nickname: string }
export interface Room { id: string; name: string; description: string; group: boolean; searchable: boolean }
export interface Message { id: number; senderId: number; senderName: string; content: string; createdAt: string; attachment?: { name: string; size: number } | null }
export const searchUsers = async (q: string) => (await http.get<Person[]>('/chat/users', { params: { q } })).data;
export const searchGroups = async (q: string) => (await http.get<Room[]>('/chat/groups', { params: { q } })).data;
export const listRooms = async () => (await http.get<Room[]>('/chat/rooms')).data;
export const openDirect = async (id: number) => (await http.post<Room>(`/chat/direct/${id}`)).data;
export const createGroup = async (name: string, description: string, searchable: boolean) => (await http.post<Room>('/chat/groups', { name, description, searchable })).data;
export const joinGroup = async (id: string) => { await http.post(`/chat/groups/${id}/join`); };
export const getMessages = async (id: string, after = 0) => (await http.get<Message[]>(`/chat/rooms/${id}/messages`, { params: { after } })).data;
export const sendMessage = async (id: string, content: string) => { await http.post(`/chat/rooms/${id}/messages`, { content }); };
