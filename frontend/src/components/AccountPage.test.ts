// @vitest-environment jsdom
import {mount,flushPromises} from "@vue/test-utils";
import {afterEach,beforeEach,describe,expect,it,vi} from "vitest";
import AccountPage from "./AccountPage.vue";
import * as api from "../api/users";
const updateNickname=vi.hoisted(()=>vi.fn());
vi.mock("../stores/auth",()=>({useAuth:()=>({user:{value:{id:"1"}},updateNickname})}));
vi.mock("../api/users",async original=>({...await original<typeof api>(),getProfile:vi.fn(),updateProfile:vi.fn(),getAvatar:vi.fn(),uploadAvatar:vi.fn(),removeAvatar:vi.fn(),changePassword:vi.fn(),deleteAccount:vi.fn(),lookupUser:vi.fn()}));
const profile:api.UserProfile={id:"1",username:"maki",nickname:"原昵称",hasAvatar:false};
const wrappers:ReturnType<typeof mount>[]=[];
async function open() { const wrapper=mount(AccountPage,{attachTo:document.body}); wrappers.push(wrapper); await flushPromises(); return wrapper; }
beforeEach(()=>{vi.clearAllMocks();document.body.style.overflow="scroll";vi.mocked(api.getProfile).mockResolvedValue({...profile});vi.mocked(api.getAvatar).mockResolvedValue(new Blob(["png"],{type:"image/png"}));URL.createObjectURL=vi.fn(()=>"blob:avatar");URL.revokeObjectURL=vi.fn();});
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());document.body.innerHTML="";document.body.style.overflow="";});
describe("账号管理",()=>{
  it("loads identity, saves trimmed nickname and updates session display",async()=>{
    const w=await open();expect(w.text()).toContain("用户 ID：1");expect(document.body.style.overflow).toBe("hidden");
    vi.mocked(api.updateProfile).mockResolvedValue({...profile,nickname:"新昵称"});
    await w.get("#nickname").setValue(" 新昵称 ");await w.get("#nickname").element.closest("form")?.dispatchEvent(new Event("submit",{bubbles:true,cancelable:true}));await flushPromises();
    expect(api.updateProfile).toHaveBeenCalledWith("新昵称");expect(updateNickname).toHaveBeenCalledWith("新昵称");expect(w.text()).toContain("个人资料已保存");expect(w.emitted("changed")).toHaveLength(1);
    w.unmount();expect(document.body.style.overflow).toBe("scroll");
  });
  it("retains nickname and supports retry after failure without treating text as HTML",async()=>{
    const w=await open();vi.mocked(api.updateProfile).mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce({...profile,nickname:"<img src=x onerror=evil()>"});
    await w.get("#nickname").setValue("<img src=x onerror=evil()>");await w.get(".account-form").trigger("submit");await flushPromises();expect(w.get('[role="alert"]').text()).toContain("操作失败");
    expect((w.get("#nickname").element as HTMLInputElement).value).toContain("<img");await w.get(".account-form").trigger("submit");await flushPromises();expect(w.find("img[src=x]").exists()).toBe(false);
  });
  it("blocks duplicate writes and escape during pending save",async()=>{
    const w=await open();let finish!:(value:api.UserProfile)=>void;vi.mocked(api.updateProfile).mockImplementation(()=>new Promise(resolve=>finish=resolve));
    await w.get(".account-form").trigger("submit");await w.get(".account-form").trigger("submit");await w.get("main").trigger("keydown",{key:"Escape"});expect(api.updateProfile).toHaveBeenCalledTimes(1);expect(w.emitted("back")).toBeUndefined();
    finish(profile);await flushPromises();await w.get("main").trigger("keydown",{key:"Escape"});expect(w.emitted("back")).toHaveLength(1);
  });
  it("rejects invalid avatars, displays saved image and revokes URLs",async()=>{
    const w=await open(),input=w.get('input[type="file"]');
    Object.defineProperty(input.element,"files",{configurable:true,value:[new File(["svg"],"bad.svg",{type:"image/svg+xml"})]});await input.trigger("change");expect(api.uploadAvatar).not.toHaveBeenCalled();
    vi.mocked(api.uploadAvatar).mockResolvedValue();Object.defineProperty(input.element,"files",{configurable:true,value:[new File(["png"],"good.png",{type:"image/png"})]});await input.trigger("change");await flushPromises();
    expect(api.uploadAvatar).toHaveBeenCalledTimes(1);expect(w.get('img[alt="个人头像"]').attributes("src")).toBe("blob:avatar");
    vi.mocked(api.removeAvatar).mockResolvedValue();await w.findAll("button").find(b=>b.text()==="移除头像")!.trigger("click");await flushPromises();expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:avatar");expect(w.find("img").exists()).toBe(false);
  });
  it("confirms new password and only signs out after successful change",async()=>{
    const w=await open();await w.get("#current-password").setValue("password123");await w.get("#new-password").setValue("new-password");await w.get("#repeat-password").setValue("different");
    await w.findAll("form")[1]!.trigger("submit");expect(api.changePassword).not.toHaveBeenCalled();
    await w.get("#repeat-password").setValue("new-password");vi.mocked(api.changePassword).mockRejectedValueOnce({isAxiosError:true,response:{status:400,data:{code:"INVALID_PASSWORD"}}}).mockResolvedValueOnce();
    await w.findAll("form")[1]!.trigger("submit");await flushPromises();expect(w.text()).toContain("当前密码不正确");expect(w.emitted("signedOut")).toBeUndefined();
    await w.findAll("form")[1]!.trigger("submit");await flushPromises();expect(w.emitted("signedOut")).toEqual([["password"]]);expect((w.get("#current-password").element as HTMLInputElement).value).toBe("");
  });
  it("requires separate deletion confirmation and exposes seven-day deadline",async()=>{
    const w=await open();expect(api.deleteAccount).not.toHaveBeenCalled();await w.findAll("button").find(b=>b.text()==="申请注销账号")!.trigger("click");
    await w.get("#delete-password").setValue("password123");await w.get("#delete-confirmation").setValue("确定");await w.findAll("form")[3]!.trigger("submit");expect(api.deleteAccount).not.toHaveBeenCalled();
    await w.get("#delete-confirmation").setValue("注销账号");vi.mocked(api.deleteAccount).mockResolvedValue({deletedAt:"2026-10-01T12:00:00Z",purgeAfter:"2026-10-08T12:00:00Z"});
    await w.findAll("form")[3]!.trigger("submit");await flushPromises();expect(api.deleteAccount).toHaveBeenCalledWith("password123","注销账号");expect(w.emitted("signedOut")).toEqual([["deletion","2026-10-08T12:00:00Z"]]);expect(w.text()).toContain("7 天");
  });
  it("cancels stale username lookups and does not display the previous user",async()=>{
    const w=await open();let finish!:(value:api.UserProfile)=>void;vi.mocked(api.lookupUser).mockImplementationOnce(()=>new Promise(resolve=>finish=resolve));
    await w.get("#lookup-username").setValue("other");await w.findAll("form")[2]!.trigger("submit");const signal=vi.mocked(api.lookupUser).mock.calls[0]![1]!;
    await w.get("#lookup-username").setValue("new-user");expect(signal.aborted).toBe(true);finish({...profile,id:"2",nickname:"过期用户"});await flushPromises();expect(w.text()).not.toContain("过期用户");
  });
  it("retries profile loading and ignores late completion after unmount",async()=>{
    vi.mocked(api.getProfile).mockRejectedValueOnce(new Error("offline"));const w=await open();expect(w.text()).toContain("个人资料加载失败");await w.findAll("button").find(b=>b.text()==="重新加载")!.trigger("click");await flushPromises();expect(w.text()).toContain("原昵称");
    let finish!:(value:api.UserProfile)=>void;vi.mocked(api.updateProfile).mockImplementation(()=>new Promise(resolve=>finish=resolve));await w.get(".account-form").trigger("submit");w.unmount();finish({...profile,nickname:"延迟"});await flushPromises();expect(updateNickname).not.toHaveBeenCalled();
  });
});
