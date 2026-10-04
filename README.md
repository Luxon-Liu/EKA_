# 政务档案利用与分析智能体（EKA）

> 一个面向政务档案工作人员的档案利用与分析智能体。用户在前端提问，后端 Agent 自主调用 **RAG 检索**、**只读 SQL 查询**、**文件读写** 等工具，并以 **SSE 流式**返回答案。

EKA 把「政务档案原文 / 政策文件 / 业务数据」作为知识来源，帮助档案工作人员查阅档案原文、理解政策依据、统计分析档案数据，并在此基础上完成公文、材料等文档的撰写与加工；让大模型在受控的工具边界内作答，而不是凭空生成。后端基于 Spring Boot + langchain4j 自研 Agent 循环，前端基于 Next.js 提供类 ChatGPT 的交互体验。

---

## ✨ 功能特性

- **流式对话**：`POST /agent/chat/stream` 以 SSE 推送，前端边生成边渲染；事件类型精简为 `STREAM_TEXT` / `TOOL_CALL_REQUEST` / `TOOL_CALL_RESULT` / `DONE` / `ERROR`。
- **RAG 混合检索**：向量召回（DashScope `text-embedding-v3` + Elasticsearch）与 BM25 关键词召回并行，再经 `qwen3-vl-rerank` 重排序后返回，兼顾语义与精确匹配。
- **文档解析与切块**：借助 Docling Serve 将 PDF 结构化，配合自定义切块器（token 预算、表格处理、页眉页脚过滤）入库。
- **只读 SQL 查询**：Agent 通过 `SqlExecuteTool` 查询业务库，仅允许 `SELECT`，带库名白名单、行数上限与超时保护；表结构语义来自每表一份的 MD 数据字典。
- **文件工具集**：列出 / 读取 / 写入 / 编辑会话文件，支持将结果导出为 PDF。
- **多会话记忆**：会话与消息持久化到 MySQL；长会话超窗口时自动「摘要 + 保留最近原文」压缩；支持标题自动生成、改名、分页、模糊搜索与批量删除。
- **登录鉴权**：JWT 自包含 token + Cookie，过滤器验签还原用户身份，用户与会话数据相互隔离。
- **主备模型**：对话模型支持 DeepSeek 与通义千问（DashScope），一份主用、一份兜底。
- **工具韧性**：工具调用具备超时、预算、错误分类与降级策略，避免单次调用拖垮整轮对话。

---

## 🧱 技术栈

| 分层 | 技术 |
|---|---|
| 后端语言 / 框架 | Java 17、Spring Boot 3.4.5、Spring MVC |
| Agent 编排 | langchain4j 1.11.11（AiServices、工具调用、流式） |
| 持久化 | MySQL 8 + MyBatis-Plus 3.5.7 |
| 向量检索 | Elasticsearch（`langchain4j-elasticsearch`，索引 `eka-rag`） |
| 模型服务 | 阿里云 DashScope（嵌入 `text-embedding-v3`、重排 `qwen3-vl-rerank`、通义千问）、DeepSeek |
| 文档处理 | Docling Serve（解析）、Apache POI（docx/xlsx）、PDFBox、openhtmltopdf、jsoup |
| 鉴权 | jjwt（HS256） |
| 前端 | Next.js 16、React 19、TypeScript、Tailwind CSS 4、pnpm 10 |

---

## 📁 目录结构

```
EKA_/
├── EKA/                    # 后端：Spring Boot + langchain4j（端口 8080）
│   └── src/main/java/com/liu/eka/
│       ├── agent/          # Agent 工厂与流式执行
│       ├── controller/     # REST / SSE 接口层
│       ├── service/        # 业务逻辑（会话、文件、RAG 入库、压缩）
│       ├── tool/           # Agent 工具：RAG、SQL、文件读写 + 韧性治理
│       ├── rag/            # 切块器、关键词检索、重排序
│       ├── memory/         # 会话记忆存储与上下文压缩
│       ├── model/          # 主备模型封装
│       ├── config/         # 各类配置绑定
│       └── util/           # 工具类（JWT、分词估算等）
└── EKA-UI/                 # 前端：Next.js 对话界面（端口 5173）
    └── src/
        ├── providers/      # 对接自研后端的数据层（Eka / Thread）
        ├── lib/            # 接口封装、历史消息解析
        ├── components/     # 聊天、会话侧边栏、消息气泡等组件
        └── app/            # 页面与登录
```

> 说明：**数据字典目录（`MD/`）、档案原文目录（`File/`）、会话文件目录（`Edit-File/`）与 `application.yml` 均未纳入版本库**，需按下方说明在本地自行准备。

---

## 🚀 快速开始

### 1. 环境要求

| 组件 | 版本 / 说明 |
|---|---|
| JDK | 17+ |
| Maven | 3.8+ |
| Node.js | 20+ |
| pnpm | 10+（`corepack enable` 后可自动获取） |
| MySQL | 8.x |
| Elasticsearch | 8.x，需安装 **IK 中文分词器** |
| Docling Serve | 本地启动，默认 `http://127.0.0.1:5001`，用于解析 PDF |
| 模型 Key | 阿里云 DashScope API Key、DeepSeek API Key |

### 2. 准备基础设施

1. **建库建表**：创建数据库 `enterprise_knowledge_agent`（`utf8mb4`），并建立核心表：
   - 用户与鉴权：`ek_user`
   - 会话记忆：`ai_chat_session`、`ai_chat_message`、`ai_chat_memory`（会话记忆快照，`messages` 列为 langchain4j 消息 JSON 数组）、`ai_chat_file`
   - 档案业务：`ek_ar_project`、`ek_ar_main`、`ek_ar_side`、`ek_ar_file`
2. **启动 Elasticsearch** 并安装 IK 分词器，索引由程序在入库时创建（默认名 `eka-rag`）。
3. **启动 Docling Serve**：
   ```bash
   pip install docling-serve
   docling-serve run --port 5001
   ```
4. **准备运行时目录**（与后端工作目录相对）：
   - `MD/`：数据字典，每张业务表一份「表名.md」语义说明，供 SQL 工具理解表结构；
   - `File/`：档案原文存放根目录；
   - `Edit-File/`：会话文件落盘根目录。

### 3. 配置后端

在 `EKA/src/main/resources/` 下新建 `application.yml`（该文件不入库），填入自己的连接信息与密钥。**敏感值建议通过环境变量注入**：

```yaml
server:
  port: 8080

spring:
  application:
    name: EKA
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 50MB
  datasource:
    url: jdbc:mysql://localhost:3306/enterprise_knowledge_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USERNAME:root}
    password: ${MYSQL_PASSWORD:}
    driver-class-name: com.mysql.cj.jdbc.Driver

es:
  url: http://127.0.0.1:9200
  index: eka-rag
  connect-timeout-seconds: 3
  socket-timeout-seconds: 3

rag:
  docling:
    base-url: http://127.0.0.1:5001
  embedding:
    provider: dashscope
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1/
    api-key: ${DASHSCOPE_API_KEY:}
    model: text-embedding-v3
  search:
    vector-top-k: 20
    keyword-top-k: 20
  rerank:
    base-url: https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
    model: qwen3-vl-rerank
    api-key: ${DASHSCOPE_API_KEY:}
    top-n: 10
  chunker:
    max-tokens: 512
    merge-peers: true
    repeat-table-header: true
    use-markdown-tables: true
    filter-page-chrome: true

llm:
  deepseek:
    base-url: https://api.deepseek.com/v1
    api-key: ${DEEPSEEK_API_KEY:}
    model: deepseek-v4-flash
  qwen:
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1/
    api-key: ${DASHSCOPE_API_KEY:}
    model: qwen3.7-flash

compaction:
  window-size: 1000000
  trigger-ratio: 0.90
  keep-ratio: 0.15
  summary-max-tokens: 16000
  single-message-max-chars: 2000
  keep-recent-tool-output-tokens: 8000
  summary-soft-limit-ratio: 0.75
  summary-timeout-seconds: 180

auth:
  secret: ${JWT_SECRET:}        # HS256，至少 32 字节的随机长串
  expire-hours: 12
  cookie-name: eka_token
  cookie-secure: false           # 本地 HTTP 用 false，HTTPS 部署改 true

agent:
  max-tool-invocations: 20

sql_tool:
  md-root: ../MD                 # 数据字典根目录，按实际路径调整
  database: enterprise_knowledge_agent
  max-rows: 100
  timeout-seconds: 4

file:
  session-root: ../Edit-File     # 会话文件根目录
  archive-root: ../File          # 档案原文根目录
  pdf:
    font-path: C:/Windows/Fonts/simhei.ttf   # 导出 PDF 的中文字体，Linux 请换成 /usr/share/fonts 下的 ttf

logging:
  level:
    ai.docling.serve.client.DoclingServeClient: WARN
```

建议在运行环境设置以下环境变量，避免密钥落盘：

```bash
DASHSCOPE_API_KEY=你的 DashScope Key
DEEPSEEK_API_KEY=你的 DeepSeek Key
MYSQL_PASSWORD=你的数据库密码
JWT_SECRET=至少32字节的随机字符串
```

### 4. 启动后端

```bash
cd EKA
mvn spring-boot:run          # 开发运行
# 或
mvn clean package            # 打包为可执行 jar
java -jar target/EKA-1.0-SNAPSHOT.jar
```

服务启动后监听 `http://localhost:8080`。

### 5. 启动前端

```bash
cd EKA-UI
pnpm install

# 配置后端地址：复制 .env.example 为 .env.local，按需修改
# NEXT_PUBLIC_API_URL=http://localhost:8080

pnpm dev          # 打开 http://localhost:5173
# 或 pnpm dev:open（自动打开浏览器）
```

---

## 🔌 接口一览

后端无统一 context-path，接口如下（`Result` 统一包裹，SSE 除外）：

| 模块 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 鉴权 | POST | `/auth/register` | 注册 |
| 鉴权 | POST | `/auth/login` | 登录（写入 Cookie） |
| 鉴权 | POST | `/auth/logout` | 登出 |
| 鉴权 | GET | `/auth/me` | 当前登录用户 |
| Agent | POST | `/agent/chat/stream` | 流式问答（SSE），body `{message, memoryId}` |
| 会话 | GET | `/chat-memory/page` | 会话分页 |
| 会话 | GET | `/chat-memory/content` | 会话消息内容 |
| 会话 | GET | `/chat-memory/search` | 标题模糊搜索 |
| 会话 | PUT | `/chat-memory/title` | 会话改名 |
| 会话 | POST | `/chat-memory/title/generate` | 生成标题 |
| 会话 | DELETE | `/chat-memory/batch` | 批量删除（body 为 ID 数组） |
| 文件 | POST | `/file/upload` | 上传会话文件 |
| 文件 | GET | `/file/list` | 会话文件列表 |
| 文件 | GET | `/file/preview` | 文件预览 |
| 文件 | GET | `/file/export` | 导出（PDF 等） |
| RAG | POST | `/rag/ingest` | 文档入库（解析、切块、向量化） |

---

## 🔐 安全说明

- 仓库中**不包含**任何真实密钥、数据库口令或配置文件；`application.yml`、`MD/`、`File/`、`Edit-File/` 均已在 `.gitignore` 中排除。
- 所有敏感配置请通过环境变量或本地 `application.yml` 注入，切勿提交到版本库。
- SQL 工具仅执行只读查询，并受库名白名单、返回行数与超时限制。

---

## 🙏 致谢与许可证

- 前端 `EKA-UI` 基于 [langchain-ai/agent-chat-ui](https://github.com/langchain-ai/agent-chat-ui) 二次开发，沿用其 MIT 协议（见 `EKA-UI/LICENSE`）。
- RAG 与 Agent 能力由 [langchain4j](https://github.com/langchain4j/langchain4j)、[Docling](https://github.com/docling-project/docling) 等开源项目驱动。
