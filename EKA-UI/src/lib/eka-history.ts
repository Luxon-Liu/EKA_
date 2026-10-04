// 历史映射：后端 /content 的「消息 ID + langchain4j ChatMessage JSON」列表转前端 Message。
// Jackson FIELD 序列化格式：USER{contents:[{text,type:TEXT}]} / AI{text,toolExecutionRequests:[{id,name,arguments}]} /
// TOOL_EXECUTION_RESULT{id,toolName,text}，1:1 映射，不合并（展示层本就支持 ai+tool 交错）。
// Message.id 直接用后端消息 ID（字符串化），供文件卡片锚点等场景前后端对齐。
import type { Message } from "@langchain/langgraph-sdk";
import type { ChatMessageVO } from "./eka-api";

interface RawMessage {
  type?: string;
  contents?: { text?: string; type?: string }[];
  text?: string;
  toolExecutionRequests?: { id?: string; name?: string; arguments?: string }[];
  id?: string;
  toolName?: string;
}

/** arguments 字符串转对象：解析失败回落空对象，不阻断渲染 */
export function parseArgs(args: string | undefined): Record<string, unknown> {
  if (!args) return {};
  try {
    return JSON.parse(args);
  } catch {
    return { _raw: args };
  }
}

/** 后端消息列表转 Message 数组：SYSTEM/CUSTOM 忽略，Message.id 取后端消息 ID */
export function toMessages(rows: ChatMessageVO[]): Message[] {
  if (!Array.isArray(rows)) return [];
  const messages: Message[] = [];
  for (const row of rows) {
    let raw: RawMessage;
    try {
      raw = JSON.parse(row.message) as RawMessage;
    } catch {
      continue;
    }
    // 消息稳定标识：用后端自增 ID 字符串化，保证与文件卡片 anchorMessageId 可对齐
    const id = String(row.id);
    if (raw.type === "USER") {
      // 用户提问：contents 取 TEXT 段拼接
      const text = (raw.contents ?? [])
        .filter((c) => c.type === "TEXT")
        .map((c) => c.text ?? "")
        .join("");
      messages.push({ id, type: "human", content: text } as Message);
    } else if (raw.type === "AI") {
      // AI 回复：文本 + 工具调用（含纯工具调用轮，text 可能为空）
      messages.push({
        id,
        type: "ai",
        content: raw.text ?? "",
        tool_calls: (raw.toolExecutionRequests ?? []).map((r) => ({
          id: r.id ?? "",
          name: r.name ?? "",
          args: parseArgs(r.arguments),
        })),
      } as unknown as Message);
    } else if (raw.type === "TOOL_EXECUTION_RESULT") {
      // 工具结果：按调用 ID 配对展示
      messages.push({
        id,
        type: "tool",
        tool_call_id: raw.id ?? "",
        name: raw.toolName ?? "",
        content: raw.text ?? "",
      } as unknown as Message);
    }
  }
  return messages;
}
