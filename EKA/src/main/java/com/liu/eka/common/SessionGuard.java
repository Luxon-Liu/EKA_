package com.liu.eka.common;

import com.liu.eka.entity.conversation.ChatSession;
import com.liu.eka.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 会话归属守卫：在文件、Agent 等按 memoryId 操作的入口统一校验
 * 「会话存在 + 未删除 + 归属当前登录用户」，防止跨用户越权访问
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Component
@RequiredArgsConstructor
public class SessionGuard {

    /** 会话服务：按 ID + 用户查会话行 */
    private final ChatSessionService chatSessionService;

    /**
     * 校验会话归属：查不到即视为不存在或无权访问，统一抛业务异常由全局兜底翻译
     *
     * @param memoryId 会话 ID，允许为空（为空直接判失败）
     * @throws IllegalStateException 会话不存在、已删除或不属于当前登录用户时抛出
     */
    public void requireOwned(Long memoryId) {
        // 步骤 1：入参兜底，会话 ID 为空无从校验
        if (memoryId == null) {
            throw new IllegalStateException("会话不存在");
        }

        // 步骤 2：按「ID + 当前用户 + 未删除」精确查询，三者任一不满足都视为无权访问
        ChatSession session = chatSessionService.query().eq(true, "id", memoryId)
                .eq(true, "user_id", UserContext.requireUserId())
                .eq(true, "del_flag", 0)
                .select("id")
                .one();
        if (session == null) {
            throw new IllegalStateException("会话不存在");
        }
    }
}
