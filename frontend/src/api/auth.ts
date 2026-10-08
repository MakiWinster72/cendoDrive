import axios from "axios";
import http from "./http";

export interface UserResponse {
  id: string;
  username: string;
  nickname: string;
  createdAt: string;
  vipLevel: string;
  storageUsed: number;
  storageLimit: number;
}

export interface RegisterPayload {
  username: string;
  password: string;
  nickname?: string;
}

export interface LoginPayload {
  username: string;
  password: string;
}

export interface LoginResult {
  token: string;
  expiresInSeconds: number;
  user: UserResponse;
}

export interface ApiError {
  code: string;
  message: string;
  fields: Record<string, string>;
}

export async function registerRequest(
  payload: RegisterPayload,
): Promise<UserResponse> {
  const { data } = await http.post<UserResponse>("/auth/register", payload);
  return data;
}

export async function loginRequest(
  payload: LoginPayload,
): Promise<LoginResult> {
  const { data } = await http.post<LoginResult>("/auth/login", payload);
  return data;
}

export async function getCurrentUser(): Promise<UserResponse> {
  const { data } = await http.get<UserResponse>("/user/me");
  return data;
}

export async function logoutRequest(): Promise<void> {
  await http.post("/auth/logout");
}

export function authErrorMessage(error: unknown, fallback: string): string {
  if (!axios.isAxiosError<ApiError>(error)) return fallback;
  if (!error.response) return "网络连接失败，请检查后端服务";
  switch (error.response.status) {
    case 400:
      return "请检查填写的信息";
    case 401:
      return "用户名或密码错误";
    case 409:
      return error.response.data?.code === "CONFLICT"
        ? "用户名已存在"
        : fallback;
    case 429:
      return "登录尝试过于频繁，请稍后再试";
    default:
      return fallback;
  }
}

export function fieldErrors(error: unknown): Record<string, string> {
  return axios.isAxiosError<ApiError>(error) && error.response?.status === 400
    ? (error.response.data.fields ?? {})
    : {};
}
