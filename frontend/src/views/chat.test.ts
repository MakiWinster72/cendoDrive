// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import Discovery from './ChatDiscoveryView.vue';
import NewGroup from './NewGroupView.vue';
import Chat from './ChatView.vue';
import * as api from '../api/chat';
const { push } = vi.hoisted(()=>({push:vi.fn()}));
vi.mock('vue-router',()=>({useRouter:()=>({push}),useRoute:()=>({params:{id:'room'}})}));
vi.mock('../stores/auth',()=>({useAuth:()=>({user:{value:{id:'1'}}})}));
vi.mock('../api/chat',()=>({searchUsers:vi.fn(),searchGroups:vi.fn(),openDirect:vi.fn(),joinGroup:vi.fn(),createGroup:vi.fn(),listRooms:vi.fn(),getMessages:vi.fn(),sendMessage:vi.fn()}));
const room={id:'room',name:'测试群',description:'简介',group:true,searchable:true};
beforeEach(()=>{vi.clearAllMocks();vi.mocked(api.searchGroups).mockResolvedValue([]);vi.mocked(api.listRooms).mockResolvedValue([room]);vi.mocked(api.getMessages).mockResolvedValue([]);});
afterEach(()=>vi.useRealTimers());
describe('聊天页面',()=>{
  it('按用户名搜索并进入真实私聊，展示入口不调用接口',async()=>{
    vi.mocked(api.searchUsers).mockResolvedValue([{id:2,username:'bob',nickname:'Bob'}]);
    vi.mocked(api.openDirect).mockResolvedValue({...room,group:false});
    const wrapper=mount(Discovery);
    await wrapper.find('.discovery-cards button').trigger('click');
    expect(wrapper.text()).toContain('仅作展示');expect(api.searchUsers).not.toHaveBeenCalled();
    await wrapper.find('input').setValue('bob');await wrapper.find('form').trigger('submit');await flushPromises();
    expect(api.searchUsers).toHaveBeenCalledWith('bob');expect(wrapper.text()).toContain('ID 2');
    await wrapper.find('.chat-result button').trigger('click');await flushPromises();
    expect(api.openDirect).toHaveBeenCalledWith(2);expect(push).toHaveBeenCalledWith('/chat/room');wrapper.unmount();
  });
  it('创建群聊受必填限制，计数正确且展示开关不传后端',async()=>{
    vi.mocked(api.createGroup).mockResolvedValue(room);
    const wrapper=mount(NewGroup);
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined();
    await wrapper.find('#group-name').setValue('测试群');await wrapper.find('#group-description').setValue('简介');
    expect(wrapper.text()).toContain('3/20');
    await wrapper.findAll('input[type="checkbox"]')[0]!.setValue(true);
    await wrapper.find('form').trigger('submit');await flushPromises();
    expect(api.createGroup).toHaveBeenCalledWith('测试群','简介',true);expect(push).toHaveBeenCalledWith('/chat/room');wrapper.unmount();
  });
  it('搜索失败可重试且空输入不发起请求',async()=>{
    const wrapper=mount(Discovery);await wrapper.find('form').trigger('submit');expect(api.searchUsers).not.toHaveBeenCalled();
    vi.mocked(api.searchUsers).mockRejectedValueOnce(new Error('offline'));
    await wrapper.find('input').setValue('bob');await wrapper.find('form').trigger('submit');await flushPromises();expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    vi.mocked(api.searchUsers).mockResolvedValue([]);await wrapper.find('form').trigger('submit');await flushPromises();expect(wrapper.text()).toContain('没有找到');wrapper.unmount();
  });
  it('发送失败保留消息，成功后清空',async()=>{
    const wrapper=mount(Chat);await flushPromises();
    await wrapper.find('textarea').setValue('你好');vi.mocked(api.sendMessage).mockRejectedValueOnce(new Error('offline'));
    await wrapper.find('form').trigger('submit');await flushPromises();expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('你好');
    vi.mocked(api.sendMessage).mockResolvedValue();await wrapper.find('form').trigger('submit');await flushPromises();expect(api.sendMessage).toHaveBeenCalledWith('room','你好');expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('');wrapper.unmount();
  });
});
