import http from './http';
import type { DriveItemResponse } from './drive';
export const sendFileMessage = async (room: string, fileId: string) => { await http.post(`/chat/rooms/${room}/files`, { fileId }); };
export const saveChatFile = async (room: string, messageId: number) => (await http.post<DriveItemResponse>(`/chat/rooms/${room}/files/${messageId}/save`)).data;
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
