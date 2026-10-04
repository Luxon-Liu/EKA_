package com.liu.eka.entity.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 对话事件：枚举常量即事件名（SSE 事件名直接取常量名），
 * 各事件的载荷由同名静态工厂组装；事件流经 SSE 推前端，日志只作服务端自查
 *
 * @author Luxon
 * @date 2026/09/04
 */
public enum AgentEvent {

    /** 模型流式文本增量，载荷为本片文本 */
    STREAM_TEXT,

    /** 工具调用请求，载荷为调用 ID、工具名与入参 */
    TOOL_CALL_REQUEST,

    /** 工具调用结果，载荷为调用 ID、工具名、入参与结果摘要 */
    TOOL_CALL_RESULT,

    /** 上下文压缩开始（本轮对话前历史用量超触发线），载荷为提示文案 */
    COMPACTION_START,

    /** 上下文压缩完成，载荷为被压缩的早期消息条数与新摘要 token 数 */
    COMPACTION_DONE,

    /** 上下文压缩失败，载荷为失败原因；本轮对话按原上下文继续 */
    COMPACTION_ERROR,

    /** 本轮对话结束，载荷为最终回复全文 */
    DONE,

    /** 本轮对话异常，载荷为错误信息 */
    ERROR;

    /**
     * 组装流式文本增量事件载荷
     *
     * @param text 本片增量文本
     * @return 单字段载荷
     */
    public static Map<String, Object> textData(String text) {
        return single("text", text);
    }

    /**
     * 组装工具调用请求事件载荷
     *
     * @param callId 调用 ID（框架生成，请求与结果事件同值，前端据此配对）
     * @param toolName 工具名
     * @param arguments 入参 JSON（已截断）
     * @return 调用 ID、工具名与入参载荷
     */
    public static Map<String, Object> toolRequestData(String callId, String toolName, String arguments) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callId", callId);
        data.put("toolName", toolName);
        data.put("arguments", arguments);
        return data;
    }

    /**
     * 组装工具调用结果事件载荷
     *
     * @param callId 调用 ID（框架生成，请求与结果事件同值，前端据此配对）
     * @param toolName 工具名
     * @param arguments 入参 JSON（已截断）
     * @param resultSummary 结果摘要（已截断）
     * @return 调用 ID、工具名、入参与结果摘要载荷
     */
    public static Map<String, Object> toolResultData(String callId, String toolName, String arguments, String resultSummary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callId", callId);
        data.put("toolName", toolName);
        data.put("arguments", arguments);
        data.put("resultSummary", resultSummary);
        return data;
    }

    /**
     * 组装结束事件载荷
     *
     * @param answer 最终回复全文
     * @return 单字段载荷
     */
    public static Map<String, Object> doneData(String answer) {
        return single("answer", answer);
    }

    /**
     * 组装异常事件载荷
     *
     * @param message 错误信息
     * @return 单字段载荷
     */
    public static Map<String, Object> errorData(String message) {
        return single("message", message);
    }

    /**
     * 组装上下文压缩开始事件载荷
     *
     * @return 单字段提示文案载荷
     */
    public static Map<String, Object> compactionStartData() {
        return single("message", "正在压缩对话上下文");
    }

    /**
     * 组装上下文压缩完成事件载荷
     *
     * @param compactedMessages 本次被压成摘要的早期消息条数
     * @param summaryTokens 新摘要占用的 token 数
     * @return 压缩条数与摘要 token 数载荷
     */
    public static Map<String, Object> compactionDoneData(int compactedMessages, int summaryTokens) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("compactedMessages", compactedMessages);
        data.put("summaryTokens", summaryTokens);
        return data;
    }

    /**
     * 组装上下文压缩失败事件载荷
     *
     * @param message 失败原因
     * @return 单字段载荷
     */
    public static Map<String, Object> compactionErrorData(String message) {
        return single("message", message);
    }

    /**
     * 组装单字段载荷
     *
     * @param key 字段名
     * @param value 字段值
     * @return 单字段有序载荷
     */
    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key, value);
        return data;
    }
}
