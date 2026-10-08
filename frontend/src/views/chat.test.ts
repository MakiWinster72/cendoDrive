// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import Discovery from './ChatDiscoveryView.vue';
import NewGroup from './NewGroupView.vue';
import Chat from './ChatView.vue';
import Select from './ChatFileSelectView.vue';
import * as api from '../api/chat';
import * as drive from '../api/drive';
import * as uploads from '../api/files';
vi.mock('../api/drive',()=>({listFiles:vi.fn(),driveErrorMessage:(_e:unknown,f:string)=>f}));
vi.mock('../api/files',()=>({uploadFile:vi.fn(),uploadErrorMessage:()=> '上传失败'}));
const { push } = vi.hoisted(()=>({push:vi.fn()}));
vi.mock('vue-router',()=>({useRouter:()=>({push}),useRoute:()=>({params:{id:'room'}})}));
vi.mock('../stores/auth',()=>({useAuth:()=>({user:{value:{id:'1'}}})}));
vi.mock('../api/chat',()=>({searchUsers:vi.fn(),searchGroups:vi.fn(),openDirect:vi.fn(),joinGroup:vi.fn(),createGroup:vi.fn(),listRooms:vi.fn(),getMessages:vi.fn(),sendMessage:vi.fn(),sendFileMessage:vi.fn(),saveChatFile:vi.fn()}));
const room={id:'room',name:'测试群',description:'简介',group:true,searchable:true};
beforeEach(()=>{vi.clearAllMocks();vi.mocked(api.searchGroups).mockResolvedValue([]);vi.mocked(api.listRooms).mockResolvedValue([room]);vi.mocked(api.getMessages).mockResolvedValue([]);});
afterEach(()=>vi.useRealTimers());
describe('聊天页面',()=>{
  it('文件卡片转存失败可重试，成功后阻止重复点击',async()=>{
    vi.mocked(api.getMessages).mockResolvedValue([{id:7,senderId:2,senderName:'Bob',content:'',createdAt:new Date().toISOString(),attachment:{name:'报告.pdf',size:1024}}]);
    const wrapper=mount(Chat); await flushPromises();
    expect(wrapper.text()).toContain('报告.pdf');
    vi.mocked(api.saveChatFile).mockRejectedValueOnce(new Error('offline'));
    await wrapper.find('.chat-file-card').trigger('click'); await flushPromises();
    expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    vi.mocked(api.saveChatFile).mockResolvedValue({id:'9',name:'报告.pdf',kind:'file',size:1024,parentId:null,updatedAt:'',deletedAt:null});
    await wrapper.find('.chat-file-card').trigger('click'); await flushPromises();
    expect(api.saveChatFile).toHaveBeenCalledWith('room',7); expect(wrapper.text()).toContain('已转存');
    expect(wrapper.find('.chat-file-card').attributes('disabled')).toBeDefined(); wrapper.unmount();
  });
  it('云盘文件选择支持目录并发送文件ID',async()=>{
    const file={id:'42',name:'附件.txt',kind:'file' as const,size:5,parentId:'8',updatedAt:'',deletedAt:null};
    vi.mocked(drive.listFiles).mockResolvedValueOnce([{...file,id:'8',name:'目录',kind:'folder',parentId:null}]).mockResolvedValueOnce([file]);
    vi.mocked(api.sendFileMessage).mockResolvedValue();
    const chat=mount(Chat); await flushPromises();
    await chat.find('.chat-file-actions button').trigger('click'); expect(push).toHaveBeenCalledWith('/chat/room/files/select'); chat.unmount();
    const wrapper=mount(Select); await flushPromises();
    await wrapper.find('.cloud-file-row').trigger('click'); await flushPromises();
    expect(drive.listFiles).toHaveBeenLastCalledWith('8');
    await wrapper.find('.cloud-file-row').trigger('click'); await flushPromises();
    expect(api.sendFileMessage).not.toHaveBeenCalled();
    await wrapper.find('.chat-files-primary').trigger('click'); await flushPromises();
    expect(api.sendFileMessage).toHaveBeenCalledWith('room','42'); wrapper.unmount();
  });
  it('本地文件上传后发送失败可重试而不重复上传',async()=>{
    const file={id:'42',name:'附件.txt',kind:'file' as const,size:5,parentId:null,updatedAt:'',deletedAt:null};
    vi.mocked(uploads.uploadFile).mockResolvedValue(file); vi.mocked(api.sendFileMessage).mockRejectedValueOnce(new Error('offline'));
    const wrapper=mount(Chat); await flushPromises(); const input=wrapper.find('input[type="file"]');
    Object.defineProperty(input.element,'files',{value:[new File(['hello'],'附件.txt')],configurable:true});
    await input.trigger('change'); await flushPromises(); expect(wrapper.text()).toContain('重试发送已上传文件');
    vi.mocked(api.sendFileMessage).mockResolvedValue(); await wrapper.findAll('.chat-file-actions button').at(-1)!.trigger('click'); await flushPromises();
    expect(uploads.uploadFile).toHaveBeenCalledTimes(1); expect(api.sendFileMessage).toHaveBeenCalledTimes(2); wrapper.unmount();
  });
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
