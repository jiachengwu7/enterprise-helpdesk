# 企业知识助手与工单协作平台

2026-10-26。用户已选择 Java + Python + React，并要求先完成整体项目、启动，且使用独立文件夹。云模型由用户在本机配置。此次不再按课停顿。

## 实现范围

独立目录 D:\agent_work\enterprise-helpdesk。原 GitHub checkout 不变。新增实现的主链路为浏览器 -> Java 身份/授权/业务 -> 内部 Python LangChain/LangGraph。

- 登录、退出、三种角色（ADMIN/AGENT/EMPLOYEE）、IT/HR 两部门；管理员建用户。
- 文档 TXT/MD/PDF/DOCX 上传、更新版本、删除、授权下载。RustFS 原件，Redis Streams 异步解析，PostgreSQL/pgvector 向量，关键词与向量融合。向量云端配置缺失时可用关键词检索，明确报告索引模式和错误。
- 部门隔离：公开或本部门文档；管理员全量；工单员工仅本人，处理员本部门，管理员全量。Java 给 Python 的授权文档 ID 白名单是最终检索边界。
- 问答会话历史、来源引用、LangGraph 检索/回答/工单草稿/人工确认节点、PostgreSQL checkpoint。原文当数据处理，不能授权或执行操作。
- 工单创建、分配、状态转换 OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED，补充评论、关闭时评价。建单唯一键保证人工确认重试不产生重复工单。Java 唯一业务写入者。
- 页面仪表盘、知识助手、知识库、工单、系统状态及管理员用户管理。中文、可用的错误/加载/空状态。
- 本机启动/停止脚本，不依赖 PowerShell .ps1 执行权限；健康检查、权限与业务冒烟、测试报告和教学技术地图。

## 工程选择（实现者决定）

独立 Spring Boot 4.1.2 / Java 25 / Maven 项目，避免复制上游面试业务与 Gradle 下载链路；保留上游来源说明，独立新代码不宣称已经移植上游全部能力。React/TypeScript/Vite，Python FastAPI/LangChain/LangGraph。Maven 在本机可用。新 Compose 项目 enterprise-helpdesk，独立卷与端口：PostgreSQL 55432、Redis 56379、S3 59000/59001，Java 8180、Python 8101、前端 5180，全部 loopback。SQLite 不代替 PostgreSQL；无 Elasticsearch/Reranker 的可选复杂度。

模型缺失时，业务照常启动，问答只给授权检索原文并明确“仅检索，未调用模型”；模型运行错误明确报错，不伪装成功。配置真实模型后支持生成回答。配置独立 ai-service/.env，服务间随机密钥及初始账户密码保存在根 .env 与本机被忽略文件，界面不提供任意 URL/密钥管理。

工单图中调用 LangChain 工具生成结构化草稿，interrupt 等待人工确认；Java 验证归属和草稿后在事务内幂等创建，随后将结果交给图 resume。确认失败可重试，不能仅凭模型输出建单。会话同一时刻串行，防止两个请求交叉恢复同一 checkpoint。

## 验收

三个服务启动并健康；浏览器真实登录、上传、查看来源、建单、处理、关闭；跨部门/跨用户访问拒绝；重复确认仅一张工单；文档替换/删除后旧内容不再检索；未配置/错误模型时如实显示；重启后历史和待确认可恢复。真实模型调用是否验证独立记录，不能用模拟测试冒充。
