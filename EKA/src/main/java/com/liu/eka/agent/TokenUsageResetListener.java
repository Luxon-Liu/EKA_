package com.liu.eka.agent;

import com.liu.eka.service.ChatSessionService;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型调用失败监听器：本轮模型调用一旦报错，token 基线就停留在报错之前的旧值上、
 * 不再代表真实上下文（报错前新落的 AI 回复与工具结果都没算进去），
 * 故把基线置空，让下一轮压缩判断退回按当前视图估算
 *
 * @author Luxon
 * @date 2026/09/17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenUsageResetListener implements AiServiceListener<AiServiceErrorEvent> {

    /** 会话服务：清空会话行上的 token 基线 */
    private final ChatSessionService chatSessionService;

    /**
     * 声明本监听器关注的事件类型
     *
     * @return 调用失败事件类型
     */
    @Override
    public Class<AiServiceErrorEvent> getEventClass() {
        return AiServiceErrorEvent.class;
    }

    /**
     * 处理调用失败事件：把该会话的 token 基线置空；
     * memoryId 非会话主键时静默跳过，任何异常都不影响主流程
     *
     * @param event 调用失败事件
     */
    @Override
    public void onEvent(AiServiceErrorEvent event) {
        // 步骤 1：取 memoryId，只有数字（会话自增主键）时才有清空意义
        Object memoryId = event.invocationContext().chatMemoryId();
        if (!(memoryId instanceof Number sessionId)) {
            return;
        }

        // 步骤 2：置空基线——失败的调用没能写回真实用量，旧基线已失真；
        //         置空后下一轮压缩判断退化为按当前视图估算
        try {
            chatSessionService.update()
                    .eq(true, "id", sessionId.longValue())
                    .set(true, "last_input_tokens", null)
                    .update();
        } catch (Exception e) {
            log.warn("清空上下文 token 基线失败，忽略本次：memoryId={}", memoryId, e);
        }
    }
}
