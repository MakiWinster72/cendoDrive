import axios from "axios";
import { getToken, invalidateSession } from "../stores/auth";

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api",
  timeout: 10000,
  headers: { "Content-Type": "application/json" },
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token && config.url !== "/auth/restore") config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const request = error.config;
    if (
      error.response?.status === 401 &&
      request?.headers?.Authorization &&
      request.url !== "/user/me" &&
      request.url !== "/auth/login" &&
      request.url !== "/auth/restore"
    ) {
      invalidateSession();
      if (location.pathname !== "/login" && location.pathname !== "/register") {
        void import("../router").then(({ default: router }) =>
          router.replace({
            name: "login",
            query: { redirect: location.pathname },
          }),
        );
      }
    }
    return Promise.reject(error);
  },
);

export default http;
