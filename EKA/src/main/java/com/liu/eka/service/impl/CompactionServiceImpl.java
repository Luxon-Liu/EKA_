package com.liu.eka.service.impl;

import com.liu.eka.config.CompactionProperties;
import com.liu.eka.entity.conversation.ChatMessageRecord;
import com.liu.eka.entity.conversation.ChatMessageTypeCode;
import com.liu.eka.entity.conversation.ChatSession;
import com.liu.eka.memory.ConversationViewAssembler;
import com.liu.eka.service.ChatMessageRecordService;
import com.liu.eka.service.ChatSessionService;
import com.liu.eka.service.CompactionProgressListener;
import com.liu.eka.service.CompactionService;
import com.liu.eka.util.TokenEstimator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 会话上下文压缩服务的实现说明：两段式处理——
 * 先按保留区预算切出「原样保留的最近消息」，再把更早的消息连同旧摘要交给模型生成新摘要，
 * 最后用 CAS 把摘要与水位线一次写回。
 * <p>
 * 估算口径：优先采信模型返回的真实 input token（上次调用值，已含工具定义等 messages 之外的开销）；
 * 仅在基线缺失时（新会话、刚压缩成功、上次调用失败被置空）退回按当前视图估算
 * <p>
 * 失败处理：任何一步异常都只记日志、不改数据，由调用方按原样继续对话
 *
 * @author Luxon
 * @date 2026/09/17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompactionServiceImpl implements CompactionService {

    /** 会话服务：读取会话元信息、CAS 写回摘要与水位线 */
    private final ChatSessionService chatSessionService;

    /** 会话消息服务：读取水位线之后的消息行 */
    private final ChatMessageRecordService chatMessageRecordService;

    /** 视图组装器：与运行期同口径地加工视图，保证估算贴近实际发送内容 */
    private final ConversationViewAssembler viewAssembler;

    /** token 估算器：计算上下文用量与保留区预算 */
    private final TokenEstimator tokenEstimator;

    /** 压缩配置：窗口大小、触发比例、保留比例、摘要上限等 */
    private final CompactionProperties properties;

    /** 流式对话模型（带主备兜底）：摘要生成复用它，收齐流式片段拼成完整摘要文本 */
    private final StreamingChatModel streamingChatModel;

    /**
     * 按需压缩指定会话：整体流程为「读会话 → 估算 → 切保留区 → 生成摘要 → CAS 写回」，
     * 任一步失败都只记日志并放弃本次压缩；真正进入压缩流程的关键节点经 listener 通知调用方
     *
     * @param memoryId 会话 ID
     * @param listener 压缩进度回调（开始 / 成功 / 失败），未触发压缩时不回调
     */
    @Override
    public void compactIfNeeded(Long memoryId, CompactionProgressListener listener) {
        // 步骤 1：会话不存在直接返回（如新建会话尚未落库完成）
        if (memoryId == null) {
            return;
        }
        ChatSession session = chatSessionService.getById(memoryId);
        if (session == null) {
            return;
        }

        try {
            // 步骤 2：加载水位线之后的消息行；为空说明没有可压缩的内容，直接返回
            long watermark = session.getSummaryUptoMessageId() == null ? 0L : session.getSummaryUptoMessageId();
            List<ChatMessageRecord> rows = chatMessageRecordService.listAfterId(memoryId, watermark);
            if (rows.isEmpty()) {
                return;
            }

            // 步骤 3：估算当前上下文用量，未超触发线则无需压缩
            long estimate = estimateContextTokens(session, rows);
            long triggerLine = (long) (properties.getWindowSize() * properties.getTriggerRatio());
            if (estimate <= triggerLine) {
                return;
            }

            // 步骤 4：从最新往回累加到保留区预算，得到保留区起点；没有可摘要的内容则放弃
            int keepBudget = (int) (properties.getWindowSize() * properties.getKeepRatio());
            int keepStart = findKeepStart(rows, keepBudget);
            if (keepStart <= 0) {
                log.warn("上下文已超触发线，但没有可摘要的历史消息，放弃本次压缩：memoryId={}，估算={}，触发线={}",
                        memoryId, estimate, triggerLine);
                return;
            }

            // 步骤 5：把保留区起点前移到工具调用组的边界，避免水位线切在
            //         AiMessage(tool_calls) 与对应工具结果之间而产生孤儿工具消息
            keepStart = alignToolGroupBoundary(rows, keepStart);

            // 步骤 6：走到这里已确定要真正调用摘要模型，先通知前端进入"压缩中"——
            //         该阶段可能耗时数分钟，不通知用户会以为服务卡死
            listener.onStart();

            // 步骤 7：生成新摘要（输入 = 旧摘要 + 保留区之前的消息），失败则放弃本次压缩
            List<ChatMessageRecord> toCompact = new ArrayList<>(rows.subList(0, keepStart));
            String newSummary = summarize(session.getSummary(), toCompact);
            if (newSummary == null || newSummary.isBlank()) {
                log.warn("摘要结果为空，放弃本次压缩：memoryId={}", memoryId);
                listener.onFailed("摘要生成结果为空，已跳过本次压缩");
                return;
            }

            // 步骤 8：CAS 写回摘要与水位线；并发修改导致 CAS 失败时放弃覆盖
            long newWatermark = rows.get(keepStart - 1).getId();
            int summaryTokens = tokenEstimator.estimateTokenCountInText(newSummary);
            boolean updated = updateSummary(memoryId, watermark, newSummary, newWatermark, summaryTokens);
            if (!updated) {
                log.warn("摘要 CAS 写回失败（会话被并发修改），放弃本次压缩：memoryId={}", memoryId);
                listener.onFailed("会话正被其他操作修改，已跳过本次压缩");
                return;
            }
            log.info("会话上下文压缩完成：memoryId={}，水位线={} → {}，摘要token={}，压缩前估算={}",
                    memoryId, watermark, newWatermark, summaryTokens, estimate);
            listener.onCompleted(toCompact.size(), summaryTokens);
        } catch (Exception e) {
            // 步骤 9：压缩失败不影响本轮对话，记日志后按原样继续（可能因超窗口被厂商拒绝）
            log.error("会话上下文压缩失败，本轮按原上下文继续：memoryId={}", memoryId, e);
            listener.onFailed("压缩过程出现异常，本轮按原上下文继续");
        }
    }

    /**
     * 估算当前上下文用量：优先采信模型返回的真实基线（上一次调用值，
     * 已含工具定义等 messages 之外的开销）；仅在基线缺失时退化为按当前完整视图估算。
     * 基线缺失的三种情况：新会话尚未调用过模型、刚压缩成功被置空、上次调用失败被置空
     *
     * @param session 会话行（提供基线与摘要 token 数）
     * @param rows    水位线之后的消息行
     * @return 估算的上下文 token 数
     */
    private long estimateContextTokens(ChatSession session, List<ChatMessageRecord> rows) {
        // 步骤 1：有真实基线就直接采信，省去逐条消息 token 编码的开销
        Long baseline = session.getLastInputTokens();
        if (baseline != null) {
            return baseline;
        }

        // 步骤 2：基线缺失时，按与运行期一致的口径组装视图（含系统提示词、占位符化与截断）
        List<ChatMessage> rowsAsMessages = new ArrayList<>(rows.size() + 1);
        ChatMessageRecord systemRow =
                chatMessageRecordService.findLastByType(session.getId(), ChatMessageTypeCode.SYSTEM.code());
        if (systemRow != null) {
            rowsAsMessages.add(ChatMessageDeserializer.messageFromJson(systemRow.getContent()));
        }
        for (ChatMessageRecord row : rows) {
            if (row.getMessageType() != null && row.getMessageType() == ChatMessageTypeCode.SYSTEM.code()) {
                continue;
            }
            rowsAsMessages.add(ChatMessageDeserializer.messageFromJson(row.getContent()));
        }
        long viewTokens = tokenEstimator.estimateTokenCountInMessages(viewAssembler.assemble(rowsAsMessages));

        // 步骤 3：补上摘要自身的占用（优先用落库时算好的值，缺省时按文本现算）
        String summary = session.getSummary();
        if (summary != null && !summary.isBlank()) {
            Integer stored = session.getSummaryTokenCount();
            viewTokens += (stored != null && stored > 0) ? stored : tokenEstimator.estimateTokenCountInText(summary);
        }
        return viewTokens;
    }

    /**
     * 求保留区起点：从最新消息往回累加 token，累加到保留区预算即停止，
     * 返回保留区首条消息在列表中的下标
     *
     * @param rows       水位线之后的消息行
     * @param keepBudget 保留区 token 预算
     * @return 保留区起点下标，累加不到预算说明全部消息都该保留时返回 0
     */
    private int findKeepStart(List<ChatMessageRecord> rows, int keepBudget) {
        // 步骤 1：从末尾往回累加，超过预算即停止，起点定在停止处的后一条
        int kept = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).getMessageType() != null && rows.get(i).getMessageType() == ChatMessageTypeCode.SYSTEM.code()) {
                continue;
            }
            kept += tokenEstimator.estimateTokenCountInText(rows.get(i).getContent());
            if (kept > keepBudget) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * 把保留区起点前移到工具调用组边界：若起点落在工具结果消息上，
     * 说明其对应的 AiMessage(tool_calls) 已被划入摘要区，必须继续前移直到起点不是工具结果，
     * 否则发给模型的消息序列会出现「没有对应 tool_calls 的孤儿工具消息」
     *
     * @param rows      水位线之后的消息行
     * @param keepStart 初步算出的保留区起点
     * @return 对齐后的保留区起点
     */
    private int alignToolGroupBoundary(List<ChatMessageRecord> rows, int keepStart) {
        // 步骤 1：只要起点消息是工具结果，就继续前移，直到落在非工具结果消息上
        int start = keepStart;
        while (start > 0 && start < rows.size()
                && rows.get(start).getMessageType() != null
                && rows.get(start).getMessageType() == ChatMessageTypeCode.TOOL_EXECUTION_RESULT.code()) {
            start--;
        }
        return start;
    }

    /**
     * 生成新摘要：把「旧摘要 + 待压缩消息」渲染成提示词交给模型，
     * 用 maxOutputTokens 限制摘要长度，并同步等待流式结果收齐
     *
     * @param oldSummary 旧摘要，可能为空（首次压缩）
     * @param toCompact  本次要压掉的消息行
     * @return 新摘要文本
     * @throws Exception 模型调用失败或超时
     */
    private String summarize(String oldSummary, List<ChatMessageRecord> toCompact) throws Exception {
        // 步骤 1：组装提示词——把「硬上限 × 软上限比例」作为 token 软上限写进提示词，避免摘要顶满预算
        int softLimit = (int) (properties.getSummaryMaxTokens() * properties.getSummarySoftLimitRatio());
        String prompt = """
                请把以下对话压缩成结构化摘要，用于后续会话延续。用中文输出。
                必须保留：用户目标与显式约束（原话）、已确认决策、未完成任务、关键事实、文件名/表名/工具名。
                可省略：寒暄、闲聊、已完成的中间推理。
                输出控制在 %d token 以内。

                【已有摘要】
                %s

                【待压缩的对话内容】
                %s
                """.formatted(
                softLimit,
                (oldSummary == null || oldSummary.isBlank()) ? "无" : oldSummary,
                renderMessages(toCompact));

        // 步骤 2：发起流式调用，用 CompletableFuture 把异步回调转成同步等待；
        //         框架保证 onCompleteResponse 带回累积好的完整正文，无需自行拼接增量
        CompletableFuture<String> future = new CompletableFuture<>();
        streamingChatModel.chat(
                ChatRequest.builder()
                        .messages(UserMessage.from(prompt))
                        .maxOutputTokens(properties.getSummaryMaxTokens())
                        .build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        AiMessage aiMessage = completeResponse.aiMessage();
                        future.complete(aiMessage == null || aiMessage.text() == null ? "" : aiMessage.text());
                    }

                    @Override
                    public void onError(Throwable error) {
                        future.completeExceptionally(error);
                    }
                });
        return future.get(properties.getSummaryTimeoutSeconds(), TimeUnit.SECONDS).trim();
    }

    /**
     * 把消息行渲染成便于模型阅读的对话文本：按角色分行，
     * 工具调用标注工具名与入参、工具结果标注工具名
     *
     * @param rows 待渲染的消息行
     * @return 渲染后的多行文本
     */
    private String renderMessages(List<ChatMessageRecord> rows) {
        // 步骤 1：逐条按角色渲染，系统提示词与空正文跳过
        StringBuilder builder = new StringBuilder();
        for (ChatMessageRecord row : rows) {
            ChatMessage message = ChatMessageDeserializer.messageFromJson(row.getContent());
            if (message instanceof SystemMessage) {
                continue;
            } else if (message instanceof UserMessage userMessage) {
                builder.append("用户：").append(userMessage.singleText() == null ? "" : userMessage.singleText());
            } else if (message instanceof AiMessage aiMessage) {
                builder.append("助手：").append(aiMessage.text() == null ? "" : aiMessage.text());
                if (aiMessage.hasToolExecutionRequests()) {
                    builder.append("（发起工具调用：");
                    aiMessage.toolExecutionRequests().forEach(request ->
                            builder.append(request.name()).append('(').append(request.arguments()).append(") "));
                    builder.append('）');
                }
            } else if (message instanceof ToolExecutionResultMessage toolResult) {
                builder.append("工具[").append(toolResult.toolName()).append("]：")
                        .append(toolResult.text() == null ? "" : toolResult.text());
            } else {
                continue;
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * CAS 写回摘要与水位线：以「水位线未变」为条件更新，
     * 影响行数为 0 说明会话已被并发压缩过，本次结果作废
     *
     * @param memoryId       会话 ID
     * @param oldWatermark   期望的旧水位线（CAS 条件）
     * @param newSummary     新摘要文本
     * @param newWatermark   新水位线
     * @param summaryTokens  新摘要 token 数
     * @return 更新成功返回 true
     */
    private boolean updateSummary(Long memoryId, long oldWatermark, String newSummary,
                                  long newWatermark, int summaryTokens) {
        // 步骤 1：条件更新摘要字段；同时把真实 token 基线置空，
        //         让下一轮以压缩后的视图重新估算，避免用压缩前的旧基线重复触发
        return chatSessionService.update()
                .eq(true, "id", memoryId)
                .eq(true, "summary_upto_message_id", oldWatermark)
                .set(true, "summary", newSummary)
                .set(true, "summary_upto_message_id", newWatermark)
                .set(true, "summary_token_count", summaryTokens)
                .set(true, "last_input_tokens", null)
                .setSql(true, "compact_count = compact_count + 1")
                .update();
    }

}
