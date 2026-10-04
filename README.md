# 政务档案利用与分析智能体（EKA）

> 一个面向政务档案工作人员的档案利用与分析智能体。用户在前端提问，后端 Agent 自主调用 **RAG 检索**、**只读 SQL 查询**、**文件读写** 等工具，并以 **SSE 流式**返回答案。

EKA 把「政务档案原文 / 政策文件 / 业务数据」作为知识来源，帮助档案工作人员查阅档案原文、理解政策依据、统计分析档案数据，并在此基础上完成公文、材料等文档的撰写与加工；让大模型在受控的工具边界内作答，而不是凭空生成。后端基于 Spring Boot + langchain4j ，前端基于 Next.js 提供类 ChatGPT 的交互体验。

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

## 🙏 致谢与许可证

- 前端 `EKA-UI` 基于 [langchain-ai/agent-chat-ui](https://github.com/langchain-ai/agent-chat-ui) 二次开发，沿用其 MIT 协议（见 `EKA-UI/LICENSE`）。
- RAG 与 Agent 能力由 [langchain4j](https://github.com/langchain4j/langchain4j)、[Docling](https://github.com/docling-project/docling) 等开源项目驱动。
