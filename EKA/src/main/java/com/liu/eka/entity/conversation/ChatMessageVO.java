package com.liu.eka.entity.conversation;

import lombok.Builder;
import lombok.Data;

/**
 * 会话消息对外视图：把消息行主键与正文一起交给前端，
 * 前端据此把消息 ID 作为稳定标识（文件卡片锚点、编辑重发定位都依赖它）
 *
 * @author Luxon
 * @date 2026/09/21
 */
@Data
@Builder
public class ChatMessageVO {

    /** 消息 ID（ai_chat_message.id，前端用作消息的稳定标识） */
    private Long id;

    /** 单条消息内容（langchain4j ChatMessage 的 JSON 原文） */
    private String message;
}
