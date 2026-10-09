import { computed, ref, watch, onBeforeUnmount } from 'vue';
import { useRoute } from 'vue-router';
import { getChatFile } from '../api/chat';
import { driveErrorMessage, type DriveItemResponse } from '../api/drive';
import { recordChatFileViewed } from '../api/chatFileActivity';
export function useChatFile() {
  const route=useRoute();
  const room=computed(()=>String(route.params.id)), messageId=computed(()=>Number(route.params.messageId));
  const file=ref<DriveItemResponse>(), loading=ref(false), error=ref('');
  let controller: AbortController | undefined;
  async function load() {
    controller?.abort(); const request=new AbortController(); controller=request;
    file.value=undefined; loading.value=true; error.value='';
    try {
       const result=await getChatFile(room.value,messageId.value,request.signal);
       if(!request.signal.aborted) {
         file.value=result;
         recordChatFileViewed({ roomId: room.value, messageId: messageId.value, name: result.name, size: result.size });
       }
    } catch(cause) { if(!request.signal.aborted) error.value=driveErrorMessage(cause,'文件不可用，可能已删除、隐藏，或你已不在该聊天中'); }
    finally { if(controller===request) loading.value=false; }
  }
  watch([room,messageId],()=>void load(),{immediate:true}); onBeforeUnmount(()=>controller?.abort());
  return {room,messageId,file,loading,error,load};
}
