# EKA-UI（政务档案利用与分析智能体 · 前端）

基于 [agent-chat-ui](https://github.com/langchain-ai/agent-chat-ui) 二次开发，已替换数据层以对接 EKA 自研后端，UI 全中文。

- 技术栈：Next.js 16 + React 19 + TypeScript + Tailwind CSS 4 + pnpm
- 开发端口：`5173`

## 快速开始

```bash
# 1. 安装依赖
pnpm install

# 2. 配置后端地址（复制示例文件并按需修改）
#    .env.example -> .env.local
#    NEXT_PUBLIC_API_URL=http://localhost:8080

# 3. 启动开发服务器
pnpm dev
```

完整的环境依赖、后端启动方式和项目说明请见仓库根目录的 [README.md](../README.md)。

## 常用命令

| 命令 | 说明 |
|---|---|
| `pnpm dev` | 启动开发服务器（5173） |
| `pnpm dev:open` | 启动并自动打开浏览器 |
| `pnpm build` | 生产构建 |
| `pnpm lint` | 代码检查 |
| `pnpm format` | 代码格式化 |

## 许可证

沿用上游 [agent-chat-ui](https://github.com/langchain-ai/agent-chat-ui) 的 MIT 协议，详见 [LICENSE](./LICENSE)。
