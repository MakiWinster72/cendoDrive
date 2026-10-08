import axios from "axios";
import http from "./http";

export interface UserProfile { id: string; username: string; nickname: string; hasAvatar: boolean }
export interface AccountDeletion { deletedAt: string; purgeAfter: string }
export async function getProfile(): Promise<UserProfile> { return (await http.get<UserProfile>("/user/me/profile")).data; }
export async function updateProfile(nickname: string): Promise<UserProfile> { return (await http.put<UserProfile>("/user/me/profile", { nickname })).data; }
export async function getAvatar(id?: string): Promise<Blob> {
  return (await http.get<Blob>(id ? `/users/${encodeURIComponent(id)}/avatar` : "/user/me/avatar", { responseType: "blob" })).data;
}
export async function uploadAvatar(file: File): Promise<void> {
  const body=new FormData(); body.append("file",file);
  await http.put("/user/me/avatar",body,{ headers: { "Content-Type": "multipart/form-data" } });
}
export async function removeAvatar(): Promise<void> { await http.delete("/user/me/avatar"); }
export async function changePassword(currentPassword: string,newPassword: string): Promise<void> {
  await http.post("/user/me/password",{ currentPassword,newPassword });
}
export async function deleteAccount(password: string,confirmation: string): Promise<AccountDeletion> {
  return (await http.post<AccountDeletion>("/user/me/deletion",{ password,confirmation })).data;
}
export async function lookupUser(username: string,signal?: AbortSignal): Promise<UserProfile> {
  return (await http.get<UserProfile>("/users/lookup",{ params: { username },signal })).data;
}
export async function restoreAccount(username: string,password: string): Promise<void> {
  await http.post("/auth/restore",{ username,password },{ headers: { Authorization: undefined } });
}
export function accountError(error: unknown,fallback: string): string {
  if (!axios.isAxiosError<{code?:string}>(error)) return fallback;
  if (!error.response) return "网络连接失败，请稍后重试";
  const messages: Record<string,string>={ INVALID_PASSWORD: "当前密码不正确", INVALID_AVATAR: "请上传不超过 2 MiB、尺寸不超过 2048×2048 的 PNG 或 JPEG 图片", INVALID_PROFILE: "昵称不能为空或包含控制字符", PASSWORD_UNCHANGED: "新密码不能与当前密码相同", INVALID_NEW_PASSWORD: "新密码不能超过 72 个 UTF-8 字节", INVALID_CONFIRMATION: "请输入“注销账号”以确认", USER_NOT_FOUND: "未找到该用户或头像" };
  if (error.response.status===429) return "验证尝试过于频繁，请 15 分钟后重试";
  return messages[error.response.data?.code ?? ""] ?? fallback;
}
