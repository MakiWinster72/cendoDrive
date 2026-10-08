// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Detail from './ChatFileDetailView.vue';
import Save from './ChatFileSaveView.vue';
import Select from './ChatFileSelectView.vue';
import * as api from '../api/chat';
import * as drive from '../api/drive';
const {push}=vi.hoisted(()=>({push:vi.fn()}));
vi.mock('vue-router',()=>({useRouter:()=>({push}),useRoute:()=>({params:{id:'room',messageId:'7'}})}));
vi.mock('../api/chat',()=>({getChatFile:vi.fn(),previewChatFile:vi.fn(),downloadChatFile:vi.fn(),saveChatFile:vi.fn(),sendFileMessage:vi.fn()}));
vi.mock('../api/drive',()=>({listFiles:vi.fn(),driveErrorMessage:(_e:unknown,f:string)=>f}));
const file={id:'42',name:'项目归档.zip',kind:'file' as const,size:2048,parentId:null,updatedAt:'2026-01-01',deletedAt:null};
beforeEach(()=>{vi.resetAllMocks();vi.mocked(api.getChatFile).mockResolvedValue(file);vi.mocked(drive.listFiles).mockResolvedValue([]);});
describe('聊天文件二级页面',()=>{
  it('不支持预览时明确提示，仍可下载与选择转存',async()=>{
    const wrapper=mount(Detail); await flushPromises();
    const buttons=wrapper.findAll('.chat-file-menu button');
    await buttons[0].trigger('click'); expect(wrapper.text()).toContain('不支持预览此文件'); expect(api.previewChatFile).not.toHaveBeenCalled();
    await buttons[1].trigger('click'); await flushPromises(); expect(api.downloadChatFile).toHaveBeenCalledWith('room',7,'项目归档.zip');
    await buttons[2].trigger('click'); expect(push).toHaveBeenCalledWith('/chat/room/files/7/save'); wrapper.unmount();
  });
  it('支持的文件使用聊天端点预览，而非读取发送者私有文件路径',async()=>{
    const textFile={...file,name:'说明.txt'}; vi.mocked(api.getChatFile).mockResolvedValue(textFile);
    const Preview={props:['file','fetchContent','downloadContent'],template:'<div class="preview-stub"/>',mounted(this:{fetchContent:(signal:AbortSignal)=>Promise<Blob>}){void this.fetchContent(new AbortController().signal);}};
    vi.mocked(api.previewChatFile).mockResolvedValue(new Blob(['hello']));
    const wrapper=mount(Detail,{global:{stubs:{FilePreview:Preview}}}); await flushPromises();
    await wrapper.find('.chat-file-menu button').trigger('click'); await flushPromises();
    expect(api.previewChatFile).toHaveBeenCalledWith('room',7,textFile,expect.any(AbortSignal)); wrapper.unmount();
  });
  it('文件不可用可重新加载，下载失败显示错误并可重试',async()=>{
    vi.mocked(api.getChatFile).mockRejectedValueOnce(new Error('gone'));
    const wrapper=mount(Detail); await flushPromises(); expect(wrapper.text()).toContain('文件暂时不可用');
    await wrapper.find('.cloud-retry').trigger('click'); await flushPromises();
    vi.mocked(api.downloadChatFile).mockRejectedValueOnce(new Error('offline'));
    await wrapper.findAll('.chat-file-menu button')[1].trigger('click'); await flushPromises(); expect(wrapper.find('[role="alert"]').text()).toContain('下载失败');
    await wrapper.findAll('.chat-file-menu button')[1].trigger('click'); await flushPromises(); expect(api.downloadChatFile).toHaveBeenCalledTimes(2); wrapper.unmount();
  });
  it('只显示文件夹，转存失败保留目标目录，成功阻止重复提交',async()=>{
    const folder={...file,id:'8',name:'工作资料',kind:'folder' as const,size:0};
    vi.mocked(drive.listFiles).mockResolvedValueOnce([folder,file]).mockResolvedValueOnce([]);
    const wrapper=mount(Save); await flushPromises(); expect(wrapper.findAll('.cloud-file-row')).toHaveLength(1);
    await wrapper.find('.cloud-file-row').trigger('click'); await flushPromises();
    vi.mocked(api.saveChatFile).mockRejectedValueOnce(new Error('quota'));
    await wrapper.find('.chat-files-primary').trigger('click'); await flushPromises(); expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    await wrapper.find('.chat-files-primary').trigger('click'); await flushPromises(); expect(api.saveChatFile).toHaveBeenLastCalledWith('room',7,'8');
    expect(wrapper.text()).toContain('转存成功'); expect(wrapper.text()).toContain('独立副本'); expect(wrapper.find('.cloud-browser').exists()).toBe(false); wrapper.unmount();
  });
  it('目录加载失败禁止转存，重试后允许保存到根目录',async()=>{
    vi.mocked(drive.listFiles).mockRejectedValueOnce(new Error('offline'));
    const wrapper=mount(Save); await flushPromises(); expect(wrapper.find('.chat-files-primary').attributes('disabled')).toBeDefined();
    await wrapper.find('.cloud-retry').trigger('click'); await flushPromises(); await wrapper.find('.chat-files-primary').trigger('click'); await flushPromises();
    expect(api.saveChatFile).toHaveBeenCalledWith('room',7,null); wrapper.unmount();
  });
  it('选择文件支持搜索与清除选择，发送失败后可确认重试',async()=>{
    vi.mocked(drive.listFiles).mockResolvedValue([file,{...file,id:'43',name:'隐藏.txt',hidden:true}]);
    const wrapper=mount(Select); await flushPromises(); expect(wrapper.findAll('.cloud-file-row')).toHaveLength(1);
    await wrapper.find('input').setValue('无匹配'); expect(wrapper.text()).toContain('没有找到匹配的项目');
    await wrapper.find('input').setValue('项目'); await wrapper.find('.cloud-file-row').trigger('click'); await wrapper.find('.cloud-file-row').trigger('click'); expect(wrapper.find('.chat-files-primary').attributes('disabled')).toBeDefined();
    await wrapper.find('.cloud-file-row').trigger('click'); vi.mocked(api.sendFileMessage).mockRejectedValueOnce(new Error('offline'));
    await wrapper.find('.chat-files-primary').trigger('click'); await flushPromises(); expect(wrapper.find('[role="alert"]').exists()).toBe(true);
    await wrapper.find('.chat-files-primary').trigger('click'); await flushPromises(); expect(api.sendFileMessage).toHaveBeenCalledTimes(2); expect(push).toHaveBeenCalledWith('/chat/room'); wrapper.unmount();
  });
});
