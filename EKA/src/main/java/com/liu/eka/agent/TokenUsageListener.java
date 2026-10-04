package com.liu.eka.agent;

import com.liu.eka.service.ChatSessionService;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型用量监听器：每次模型调用返回后，把该次真实的 input token 数写回会话表，
 * 作为下一轮压缩判断的基线。
 * <p>
 * 用 AiService 级监听而非 ChatModelListener 的原因：后者的事件上下文里
 * 拿不到 memoryId（ChatRequest 只带 messages/parameters），且模型的主备兜底包装
 * 覆写了 chat() 会绕过 ChatModelListener 的触发点；AiService 事件则同时提供
 * 调用上下文（含 memoryId）与该次调用的原始 ChatResponse（含 tokenUsage）
 *
 * @author Luxon
 * @date 2026/09/17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenUsageListener implements AiServiceListener<AiServiceResponseReceivedEvent> {

    /** 会话服务：把 token 基线写回会话行 */
    private final ChatSessionService chatSessionService;

    /**
     * 声明本监听器关注的事件类型
     *
     * @return 模型响应事件类型
     */
    @Override
    public Class<AiServiceResponseReceivedEvent> getEventClass() {
        return AiServiceResponseReceivedEvent.class;
    }

    /**
     * 处理模型响应事件：取该次调用的 input token 数写回对应会话；
     * 用量缺失或 memoryId 非会话主键时静默跳过，任何异常都不影响主流程
     *
     * @param event 模型响应事件
     */
    @Override
    public void onEvent(AiServiceResponseReceivedEvent event) {
        // 步骤 1：取本次调用的 token 用量，缺失时跳过（部分厂商或流式场景可能不返回用量）
        TokenUsage usage = event.response() == null ? null : event.response().tokenUsage();
        if (usage == null || usage.inputTokenCount() == null) {
            return;
        }

        // 步骤 2：取 memoryId，只有数字（会话自增主键）时才有写回意义
        Object memoryId = event.invocationContext().chatMemoryId();
        if (!(memoryId instanceof Number sessionId)) {
            return;
        }

        // 步骤 3：覆盖写基线——一轮对话内模型可能被多次调用，最后一次的用量最大、最贴近真实上下文
        try {
            chatSessionService.update()
                    .eq(true, "id", sessionId.longValue())
                    .set(true, "last_input_tokens", usage.inputTokenCount().longValue())
                    .update();
        } catch (Exception e) {
            log.warn("写入上下文 token 基线失败，忽略本次：memoryId={}", memoryId, e);
        }
    }
}
