# Cendo五天工作路径

按照“先搭骨架 → 核心文件功能 → 分享与扩展 → AI → 联调验收”



### 第 1 天：项目骨架和用户系统

上午先把基础环境搭起来：

```Plain Text
CendoDrive
├── frontend     Vue
├── backend      Spring Boot
└── services
    ├── MySQL
    ├── Redis
    ├── FastDFS
    └── Milvus
```

后端先完成基础框架：

```Plain Text
controller
service
repository / mapper
entity
dto
vo
config
exception
security
```

当天重点接口：

```Plain Text
POST /api/auth/register
POST /api/auth/login
POST /api/auth/logout
GET  /api/user/me
```

数据库至少完成：

```Plain Text
user
----------------
id
username
password_hash
nickname
role
vip_level
storage_used
storage_limit
is_active
created_at
updated_at
```

第 1 天结束必须达到：注册和登录真的能跑通，而不是只有页面。

### 第 2 天：文件管理核心功能

这是整个项目最重要的一天，优先级最高。

设计文件表，例如：

```Plain Text
file
----------------
id
user_id
parent_id
name
type
size
hash
storage_path
is_folder
status
created_at
updated_at
```

完成接口：

```Plain Text
GET    /api/files
POST   /api/files/folder
POST   /api/files/upload
GET    /api/files/{id}/download
PUT    /api/files/{id}/rename
PUT    /api/files/{id}/move
```

重点跑通：

```Plain Text
用户选择文件
      ↓
Spring Boot
      ↓
FastDFS
      ↓
获得 storage_path
      ↓
MySQL 保存文件元数据
```

第 2 天结束时应该已经像一个最基础的网盘了。

### 第 3 天：把云盘核心功能补完整

先做大文件上传：

```Plain Text
计算文件 Hash
     ↓
初始化上传任务
     ↓
文件切片
     ↓
chunk 0
chunk 1
chunk 2
...
     ↓
Redis 记录分片状态
     ↓
检查分片
     ↓
合并
     ↓
FastDFS
```

接口可以设计：

```Plain Text
POST /api/upload/init
POST /api/upload/chunk
GET  /api/upload/{uploadId}/status
POST /api/upload/merge
```

然后实现回收站：

```Plain Text
DELETE /api/files/{id}
GET    /api/recycle-bin
POST   /api/recycle-bin/{id}/restore
DELETE /api/recycle-bin/{id}
```

最后实现分享：

```Plain Text
POST /api/shares
GET  /api/shares/{token}
DELETE /api/shares/{id}
```

分享请求、列表、匿名访问与下载的字段和响应契约见 [`分享接口协议.md`](./分享接口协议.md)。

Redis：

```Plain Text
share:7bd813af...
        ↓
fileId = 1024
TTL = 86400
```

这样 Redis 的使用场景也比较明显，答辩时比较好解释。

### 第 4 天：AI 和商业扩展

AI 是项目比较有特色的部分，但不要在前三天就做，不然容易影响核心功能进度。

上传文档后的流程：

```Plain Text
PDF / Word / TXT / Markdown
          ↓
      提取文本
          ↓
       Chunk
          ↓
  Embedding Model
          ↓
       Vector
          ↓
       Milvus
```

AI 搜索：

```Plain Text
用户：
“我之前哪个文件提到了 Redis 缓存穿透？”

             ↓
        Embedding
             ↓
          Milvus
             ↓
   Top K 相似文档片段
             ↓
        返回文件
```

如果时间允许，再增加：

```Plain Text
问题
 ↓
Embedding
 ↓
Milvus
 ↓
相关 Chunk
 ↓
对话模型
 ↓
生成回答
```

也就是最简单的 RAG。

广告、SVIP、小说、视频和游戏中心不要投入过多时间，它们不是核心功能。做到“能展示、能点击、数据能从后端读取”即可。

例如广告表：

```Plain Text
advertisement
----------------
id
title
cover_url
target_url
start_time
end_time
duration
status
```

当天完成：

```Plain Text
开屏广告
我的
SVIP
游戏中心
视频入口
小说入口
AI 搜索
```

### 第 5 天：不要再加大功能，专门修项目

第 5 天重点是把完整业务流程全部走一遍：

```Plain Text
注册
 ↓
登录
 ↓
创建文件夹
 ↓
上传文件
 ↓
重命名
 ↓
移动文件
 ↓
下载
 ↓
分享
 ↓
打开分享链接
 ↓
删除
 ↓
回收站
 ↓
恢复
 ↓
AI 搜索
```

重点检查以下问题：

```Plain Text
A 用户能不能访问 B 用户的文件？

分享过期后还能不能下载？

删除文件后 AI 搜索还能不能搜到？

FastDFS 上传成功但 MySQL 写入失败怎么办？

重复上传分片怎么办？

文件夹删除后子文件怎么办？

Redis 重启是否会影响必要业务？

Milvus 不可用时普通文件功能是否还能使用？
```

最后一天再准备演示数据，比如提前上传：

```Plain Text
Java学习笔记.pdf
Redis学习笔记.pdf
Linux服务器配置.docx
Kubernetes笔记.md
项目需求分析.pdf
```

这样演示 AI 搜索时可以直接输入：

```Plain Text
帮我找讲 Redis 缓存穿透的文档
```

整体优先级建议固定成：

```Plain Text
第一优先级
用户系统
文件上传下载
文件夹
文件列表
删除/恢复

第二优先级
分片上传
文件分享
Redis

第三优先级
Milvus + AI 搜索

第四优先级
广告
SVIP
视频
小说
游戏中心
```



