// @vitest-environment jsdom
import {mount,flushPromises} from "@vue/test-utils";
import {beforeEach,expect,it,vi} from "vitest";
import LoginView from "./LoginView.vue";
import * as users from "../api/users";
const login=vi.hoisted(()=>vi.fn());
vi.mock("vue-router",()=>({useRoute:()=>({query:{accountDeleted:"1"}}),useRouter:()=>({replace:vi.fn()})}));
vi.mock("../stores/auth",()=>({useAuth:()=>({login,verificationError:{value:false}})}));
vi.mock("../api/users",()=>({restoreAccount:vi.fn()}));
beforeEach(()=>vi.clearAllMocks());
it("restores pending account without issuing a login session, then requires fresh login",async()=>{
  const w=mount(LoginView,{global:{stubs:{RouterLink:true}}});await w.findAll("button").find(b=>b.text()==="恢复 7 天内注销的账号")!.trigger("click");
  await w.get('input[aria-label="用户名"]').setValue("maki");await w.get('input[aria-label="密码"]').setValue("password123");vi.mocked(users.restoreAccount).mockResolvedValue();await w.get("form").trigger("submit");await flushPromises();
  expect(users.restoreAccount).toHaveBeenCalledWith("maki","password123");expect(login).not.toHaveBeenCalled();expect(w.text()).toContain("账号已恢复，请重新登录");expect(w.text()).not.toContain("账号已标记注销");expect((w.get('input[aria-label="密码"]').element as HTMLInputElement).value).toBe("");w.unmount();
});
it("keeps credentials on recovery error and supports retry",async()=>{
  const w=mount(LoginView,{global:{stubs:{RouterLink:true}}});await w.findAll("button").find(b=>b.text()==="恢复 7 天内注销的账号")!.trigger("click");await w.get('input[aria-label="用户名"]').setValue("maki");await w.get('input[aria-label="密码"]').setValue("bad");
  vi.mocked(users.restoreAccount).mockRejectedValue({isAxiosError:true,response:{status:401}});await w.get("form").trigger("submit");await flushPromises();expect(w.get('[role="alert"]').text()).toContain("已超过 7 天恢复期");expect(login).not.toHaveBeenCalled();w.unmount();
});
