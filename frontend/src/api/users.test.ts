import {beforeEach,expect,it,vi} from "vitest";
import * as api from "./users";
import http from "./http";
vi.mock("./http",()=>({default:{get:vi.fn(),post:vi.fn(),put:vi.fn(),delete:vi.fn()}}));
beforeEach(()=>{vi.clearAllMocks();vi.mocked(http.get).mockResolvedValue({data:{id:"1"}});vi.mocked(http.post).mockResolvedValue({data:{purgeAfter:"deadline"}});vi.mocked(http.put).mockResolvedValue({data:{nickname:"昵称"}});vi.mocked(http.delete).mockResolvedValue({});});
it("registers exact account endpoints, encodes IDs and sends multipart avatar",async()=>{
  await api.getProfile();await api.updateProfile("昵称");await api.getAvatar("1/2");await api.uploadAvatar(new File(["png"],"a.png",{type:"image/png"}));await api.removeAvatar();
  await api.changePassword("old","new");await api.deleteAccount("old","注销账号");const signal=new AbortController().signal;await api.lookupUser("maki",signal);await api.restoreAccount("maki","old");
  expect(http.get).toHaveBeenCalledWith("/user/me/profile");expect(http.put).toHaveBeenCalledWith("/user/me/profile",{nickname:"昵称"});expect(http.get).toHaveBeenCalledWith("/users/1%2F2/avatar",{responseType:"blob"});
  const upload=vi.mocked(http.put).mock.calls[1]!;expect(upload[0]).toBe("/user/me/avatar");expect((upload[1] as FormData).get("file")).toBeInstanceOf(File);
  expect(http.post).toHaveBeenCalledWith("/user/me/password",{currentPassword:"old",newPassword:"new"});expect(http.post).toHaveBeenCalledWith("/user/me/deletion",{password:"old",confirmation:"注销账号"});expect(http.delete).toHaveBeenCalledWith("/user/me/avatar");
  expect(http.get).toHaveBeenCalledWith("/users/lookup",{params:{username:"maki"},signal});expect(http.post).toHaveBeenCalledWith("/auth/restore",{username:"maki",password:"old"},{headers:{Authorization:undefined}});
});
