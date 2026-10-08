// @vitest-environment jsdom
import {AxiosError,AxiosHeaders} from "axios";
import {beforeEach,expect,it,vi} from "vitest";
const invalidateSession=vi.hoisted(()=>vi.fn());
vi.mock("../stores/auth",()=>({getToken:()=>"stale-session",invalidateSession}));
import http from "./http";
beforeEach(()=>vi.clearAllMocks());
it("never attaches a stale bearer to anonymous account recovery and does not clear sessions on its 401",async()=>{
  await expect(http.post("/auth/restore",{username:"tester",password:"password123"},{adapter:async config=>{
    expect(config.headers.get("Authorization")).toBeUndefined();
    throw new AxiosError("Unauthorized","ERR_BAD_RESPONSE",config,undefined,{data:{},status:401,statusText:"Unauthorized",headers:new AxiosHeaders(),config});
  }})).rejects.toThrow("Unauthorized");expect(invalidateSession).not.toHaveBeenCalled();
});
it("sends bearer for password changes but never logs out on an incorrect current password (400)",async()=>{
  await expect(http.post("/user/me/password",{currentPassword:"wrong",newPassword:"new-password"},{adapter:async config=>{
    expect(config.headers.get("Authorization")).toBe("Bearer stale-session");
    throw new AxiosError("Invalid password","ERR_BAD_RESPONSE",config,undefined,{data:{code:"INVALID_PASSWORD"},status:400,statusText:"Bad Request",headers:new AxiosHeaders(),config});
  }})).rejects.toThrow("Invalid password");expect(invalidateSession).not.toHaveBeenCalled();
});
