# 企业知识助手与工单协作平台

独立项目目录：`D:\agent_work\enterprise-helpdesk`。Java 管身份和业务，Python 用 LangChain/LangGraph 做知识问答与流程，React 提供操作页面。原 InterviewGuide 学习仓库不参与本项目运行。

完整学习文档：[协知实战手册](docs/实战手册.md)（17 章，包含技术原理、源码入口、实践任务、故障恢复和面试问答）；也可打开 [HTML 阅读版](docs/实战手册.html)。

[飞书在线版](https://vcn0vho30rkd.feishu.cn/wiki/NOc0wb3bfiEvICkkWzDc7XuQnrh) 已同步首版。后续修改本地文稿不会自动同步到飞书。

## 启动与登录

首次运行需要 Docker Desktop 已启动、Python 3.10+、Node 22+、Maven 3.9+ 和 Java 25。当前电脑已安装这些工具。启动器会优先选择 `JAVA_HOME` 中的 Java25，或相邻 `.tools` 目录中已安装的 Java25；不会更改系统设置。

双击 `start.cmd`，或在 PowerShell 中执行：

```powershell
cd D:\agent_work\enterprise-helpdesk
python -X utf8 scripts/manage.py start
```

页面地址：[http://127.0.0.1:5180](http://127.0.0.1:5180)。初始密码随机生成，保存在本机 `.runtime/accounts.txt`，没有通用默认密码。

| 用户名 | 身份 | 可体验的功能 |
| --- | --- | --- |
| admin | 管理员 | 文档与用户管理、全部门工单、系统状态 |
| agent | IT 处理员 | IT 文档管理、接单与处理、填写解决方案 |
| employee | IT 员工 | 公共/IT 知识问答、提交工单、评价关闭 |
| hr | HR 员工 | 公共/HR 知识问答，用来验证部门隔离 |

查看状态：`python -X utf8 scripts/manage.py status`。停止：双击 `stop.cmd`，或 `python -X utf8 scripts/manage.py stop`。停止保留数据卷。修改代码后使用 `python -X utf8 scripts/manage.py restart --build` 重新构建并启动。一般修改模型配置只需要 `restart`。

三个应用日志在 `.runtime/ai.log`、`.runtime/backend.log`、`.runtime/frontend.log`。不要将整个 `.runtime` 或 `.env` 发到聊天/提交 Git。

## 配置云模型

打开 `ai-service/.env`，本机填写：

```dotenv
AGENT_LLM_API_KEY=你自己的Key
AGENT_LLM_MODEL=你账号有权调用的模型名
AGENT_LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AGENT_EMBEDDING_MODEL=text-embedding-v3
AGENT_EMBEDDING_DIMENSIONS=1024
```

如果使用其他供应商，请同时修改兼容接口地址、模型名与 Embedding 配置，不能将任意 Key 配到默认地址。Embedding 默认复用聊天 Key/地址，也支持单独配置 `AGENT_EMBEDDING_API_KEY` 与 `AGENT_EMBEDDING_BASE_URL`。云端调用按供应商规则计费。

没有模型配置时，页面和业务功能仍可用；知识助手明确显示“仅检索”，返回授权文档片段，不伪造模型生成。配置后重启服务，观察系统状态。之前以关键词模式入库的文件可以通过知识库“更新版本”重新上传，重新计算向量。

## 操作顺序

1. 管理员进入知识库，上传 `samples/` 中的手册，选择公开或部门范围；等状态变为 READY。
2. 员工进入知识助手提问，如“VPN E809 连接失败怎么处理”，查看回答和引用来源。
3. 问题未解决时准备工单草稿，检查内容后确认。确认前不会创建工单。
4. 处理员在工单页接单、补充评论、填写解决方案并标记已解决。
5. 员工确认结果，评分并关闭。
6. 切换 HR 员工验证：不能读取 IT 部门文档、他人的会话或工单。

可通过脚本导入仓库自带的三个示例：`python -X utf8 scripts/seed_samples.py`。若已配置云 Embedding，需要加 `--allow-model` 明确允许本次索引产生模型请求。

## 工程结构与技术学习

```text
backend/       Java：登录权限、文档归属、会话、工单事务
ai-service/    Python：异步入库、检索、模型、图与checkpoint
frontend/      React：完整中文工作台
scripts/       启动、状态、样例导入、端到端验收
samples/       示例知识文档
docs/          设计、接口契约、技术地图和验证记录
```

先读 [技术地图](docs/technology-map.md)，再看 [接口契约](docs/contract.md) 和 [架构设计](docs/design.md)。登录令牌与授权由 Java 控制；Python 内部 API 需要独立服务密钥。模型输出不能改变部门权限。

## 验证

Java：在 `backend/` 使用 Java25，运行 `mvn -s maven-settings.xml test`。

Python：在 `ai-service/` 运行 `.\.venv\Scripts\python.exe -X utf8 -m pytest -q`。

前端：在 `frontend/` 运行 `npm test` 和 `npm run build`。

真实基础设施端到端验证：项目启动后，运行 `python -X utf8 scripts/smoke.py`。它覆盖权限、上传下载、引用、人工确认、重复确认幂等、完整工单状态、文档替换与删除。会创建一张带 Smoke 标记并关闭的验收工单；会清理本次创建的文档与会话。默认发现已配置云模型就停止，避免测试意外调用付费模型。确需验证真实模型时显式传 `--allow-model`。

真实浏览器：本机安装 Microsoft Edge 后运行 `node scripts/browser-smoke.cjs`。该脚本会在任何上传/提问前检查模型配置；依次登录管理员、员工、处理员，验证界面上的完整闭环，并保留验收工单/演示会话与 `.runtime/screenshots/` 截图。

小样本检索回归：导入示例后运行 `python -X utf8 scripts/evaluate_retrieval.py`。重启恢复：`python -X utf8 scripts/restart_smoke.py`。故障恢复验收：`python -X utf8 scripts/review_smoke.py`，会短暂停止并恢复本项目Python服务，避免在你正进行操作时运行。

所有端口绑定本机地址。此版本面向本机演示与学习；公网部署另需 TLS、部署级秘密管理、备份和运营配置。可选 Elasticsearch/Reranker 未引入。实际验证结果以 `docs/verification.md` 为准。
