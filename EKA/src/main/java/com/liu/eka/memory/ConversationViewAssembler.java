package com.liu.eka.memory;

import com.liu.eka.config.CompactionProperties;
import com.liu.eka.util.TokenEstimator;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话视图组装器：把数据库里的原始消息序列加工成「发给模型看的视图」。
 * <p>
 * 三项加工都只作用于视图、不改数据库（原文永远全量存档，前端仍能读到完整内容）：
 * <ol>
 *   <li>工具调用协议修复：悬空的 tool_calls 补占位结果、无主的孤儿结果丢弃，
 *       保证序列始终满足 OpenAI 协议的配对要求；</li>
 *   <li>工具输出占位符化：从最新往回连续保留预算内的工具输出，一旦放不下，
 *       本条及更老的输出全部替换为「[旧工具输出已被清理]」。
 *       不删除消息，只换文本——OpenAI 协议要求 tool_calls 后必须跟对应数量的 tool 消息；</li>
 *   <li>单条超长截断：保留头尾、中间折叠。工具调用的 arguments 不截断，
 *       因为截断会破坏 JSON 合法性导致厂商侧解析报错</li>
 * </ol>
 *
 * @author Luxon
 * @date 2026/09/17
 */
@Component
@RequiredArgsConstructor
public class ConversationViewAssembler {

    /** 超长文本截断处的标记：表明中间内容被折叠 */
    private static final String TRUNCATED_MARK = "\n…[内容过长已截断]…\n";

    /** 旧工具输出的占位符文本：标记这里曾有一次工具调用，其原文已被清理 */
    private static final String TOOL_PLACEHOLDER_TEXT = "[旧工具输出已被清理]";

    /** 缺失工具结果的占位文本：标记这次工具调用没跑完，结果不可用 */
    private static final String MISSING_TOOL_OUTPUT_TEXT = "[工具调用未完成，无输出]";

    /** token 估算器：判断工具输出是否超出保留预算 */
    private final TokenEstimator tokenEstimator;

    /** 压缩配置：读取工具输出保留预算与单条字符上限 */
    private final CompactionProperties properties;

    /**
     * 组装视图：先修复工具调用的协议完整性，再做工具输出占位符化，
     * 最后做单条超长截断，各步都返回新列表，不修改入参
     *
     * @param messages 原始消息序列（来自存储层），允许为空
     * @return 加工后的视图消息序列，入参为空时返回空列表
     */
    public List<ChatMessage> assemble(List<ChatMessage> messages) {
        // 步骤 1：空序列直接返回，避免无意义遍历
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        // 步骤 2：修复工具调用协议完整性（补齐缺失结果、丢弃无主孤儿结果）。
        //         必须最先执行——它会增删消息，放在前面后面的预算统计才基于最终序列
        List<ChatMessage> repaired = repairToolCallPairs(messages);
        // 步骤 3：老的工具输出占位符化（保留最近预算内的原文）
        List<ChatMessage> result = placeholderStaleToolOutputs(repaired);
        // 步骤 4：单条超长消息截断（头尾保留）
        return truncateOversized(result);
    }

    /**
     * 修复工具调用协议的完整性：OpenAI 兼容接口要求每个 tool_calls 请求都被
     * 对应 tool_call_id 的结果一一响应，结果也不能没有对应的请求。
     * 会话中途中断（用户停止、服务重启、异常）会留下「请求了却没有结果」的悬空消息，
     * 摘要水位线截取也可能让结果失去前置请求，两者都会让后续每一轮请求被厂商拒绝。
     * 这里只修视图：悬空的请求补一条占位结果，无主的孤儿结果直接丢弃，数据库原文一律不动
     *
     * @param messages 原始消息序列
     * @return 修复后的新序列
     */
    private List<ChatMessage> repairToolCallPairs(List<ChatMessage> messages) {
        // 步骤 1：顺序扫描，工具结果只负责销账，其它消息一律先补平欠下的结果再放行
        List<ChatMessage> result = new ArrayList<>(messages.size());
        List<ToolExecutionRequest> pending = new ArrayList<>();
        for (ChatMessage message : messages) {
            // 步骤 1.1：工具结果——能对应上待响应请求才保留，无主的孤儿结果丢弃以免同样违约
            if (message instanceof ToolExecutionResultMessage toolResult) {
                boolean matched = pending.removeIf(request -> request.id().equals(toolResult.id()));
                if (matched) {
                    result.add(toolResult);
                }
                continue;
            }

            // 步骤 1.2：其它消息都意味着上一批结果序列到此为止——先把仍未响应的请求补成占位结果，
            //          再看本消息是否带来新一批工具调用（是则登记），最后原样放行
            flushMissingResults(result, pending);
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                pending.addAll(aiMessage.toolExecutionRequests());
            }
            result.add(message);
        }

        // 步骤 2：序列收尾，兜住悬在末尾的请求（会话中断的典型形态）
        flushMissingResults(result, pending);
        return result;
    }

    /**
     * 把仍未收到结果的工具调用补成占位结果，并清空待响应列表
     *
     * @param result  目标结果序列，占位消息按请求原顺序追加到末尾
     * @param pending 待响应的工具调用列表，处理后清空
     */
    private void flushMissingResults(List<ChatMessage> result, List<ToolExecutionRequest> pending) {
        for (ToolExecutionRequest request : pending) {
            result.add(ToolExecutionResultMessage.from(request, MISSING_TOOL_OUTPUT_TEXT));
        }
        pending.clear();
    }

    /**
     * 工具输出占位符化：从消息序列末尾往前走，连续保留最近的一段工具输出原文，
     * 预算一旦放不下就停止，本条及更老的输出整条替换为「[旧工具输出已被清理]」。
     * 保证保留的是「最近的连续一段」而非零散几条，避免出现
     * 「最新的被占位、更老的反而留原文」的错位。
     * 消息条数与顺序完全不变，保证 tool_calls 与工具结果一一对应
     *
     * @param messages 原始消息序列
     * @return 替换后的新序列
     */
    private List<ChatMessage> placeholderStaleToolOutputs(List<ChatMessage> messages) {
        // 步骤 1：拷贝一份待改写列表，避免修改调用方数据
        List<ChatMessage> result = new ArrayList<>(messages);
        int budget = properties.getKeepRecentToolOutputTokens();
        int keptTokens = 0;

        // 步骤 2：从最新往回遍历，只保留最近且放得进预算的连续一段工具输出，其余替换为占位符
        boolean budgetExhausted = false;
        for (int i = result.size() - 1; i >= 0; i--) {
            if (!(result.get(i) instanceof ToolExecutionResultMessage toolResult)) {
                continue;
            }
            int tokens = tokenEstimator.estimateTokenCountInText(toolResult.text());

            // 步骤 2.1：预算尚未耗尽且本条仍放得下，保留原文并继续累加
            if (!budgetExhausted && keptTokens + tokens <= budget) {
                keptTokens += tokens;
                continue;
            }

            // 步骤 2.2：预算已耗尽（或本条放不下），本条及更老的输出一律占位
            budgetExhausted = true;
            result.set(i, ToolExecutionResultMessage.builder()
                    .id(toolResult.id())
                    .toolName(toolResult.toolName())
                    .text(TOOL_PLACEHOLDER_TEXT)
                    .isError(toolResult.isError())
                    .build());
        }
        return result;
    }

    /**
     * 单条超长截断：逐条检查文本长度，超过上限的保留头尾、中间折叠；
     * 工具调用的 arguments 一律不动，避免破坏 JSON
     *
     * @param messages 消息序列
     * @return 截断后的新序列
     */
    private List<ChatMessage> truncateOversized(List<ChatMessage> messages) {
        // 步骤 1：逐条按类型分别处理文本字段
        int maxChars = properties.getSingleMessageMaxChars();
        List<ChatMessage> result = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            result.add(truncateOne(message, maxChars));
        }
        return result;
    }

    /**
     * 截断单条消息：按消息类型分别重建，保留各自除文本外的其余字段
     * （AI 消息的 thinking/tool_calls、用户消息的 name 等都不受影响）
     *
     * @param message  待处理消息
     * @param maxChars 单条字符上限
     * @return 处理后的消息，未超长时原样返回
     */
    private ChatMessage truncateOne(ChatMessage message, int maxChars) {
        // 步骤 1：AI 回复——只截断正文，工具调用请求与思考内容原样保留
        if (message instanceof AiMessage aiMessage) {
            String text = aiMessage.text();
            if (text == null || text.length() <= maxChars) {
                return aiMessage;
            }
            return aiMessage.withText(abbreviateMiddle(text, maxChars));
        }

        // 步骤 2：用户消息——仅处理单段纯文本，多模态内容不做截断以免破坏结构
        if (message instanceof UserMessage userMessage) {
            if (!userMessage.hasSingleText()) {
                return userMessage;
            }
            String text = userMessage.singleText();
            if (text == null || text.length() <= maxChars) {
                return userMessage;
            }
            return userMessage.toBuilder()
                    .contents(List.of(TextContent.from(abbreviateMiddle(text, maxChars))))
                    .build();
        }

        // 步骤 3：工具结果——截断输出文本，工具名与错误标记保留（本步骤针对预算内仍超长的少数结果）
        if (message instanceof ToolExecutionResultMessage toolResult) {
            String text = toolResult.text();
            if (text == null || text.length() <= maxChars) {
                return toolResult;
            }
            return ToolExecutionResultMessage.builder()
                    .id(toolResult.id())
                    .toolName(toolResult.toolName())
                    .text(abbreviateMiddle(text, maxChars))
                    .isError(toolResult.isError())
                    .build();
        }

        // 步骤 4：系统提示词——通常很短，超长时同样折叠处理
        if (message instanceof SystemMessage systemMessage) {
            String text = systemMessage.text();
            if (text == null || text.length() <= maxChars) {
                return systemMessage;
            }
            return SystemMessage.from(abbreviateMiddle(text, maxChars));
        }

        // 步骤 5：其余类型原样返回
        return message;
    }

    /**
     * 文本头尾保留式截断：取头部一半、尾部一半，中间插入截断标记，
     * 让模型既能读到开头语境也能读到结尾结论
     *
     * @param text     原始文本
     * @param maxChars 字符上限
     * @return 截断后的文本
     */
    private String abbreviateMiddle(String text, int maxChars) {
        // 步骤 1：头尾各取 maxChars 的一半，中间插入截断标记
        int halfLength = maxChars / 2;
        return text.substring(0, halfLength) + TRUNCATED_MARK + text.substring(text.length() - halfLength);
    }
}
