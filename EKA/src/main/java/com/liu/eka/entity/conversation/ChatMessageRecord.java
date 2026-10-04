package com.liu.eka.entity.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 会话消息行的实体说明（对应 ai_chat_message 表）：一条消息一行，
 * content 存单条 langchain4j ChatMessage 的 JSON，按 seq 还原会话内消息先后。
 * 类名刻意与 langchain4j 的 ChatMessage 区分，避免同名冲突
 *
 * @author Luxon
 * @date 2026/09/17
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_chat_message")
public class ChatMessageRecord {

    /** 消息 ID（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID（ai_chat_session.id） */
    private Long sessionId;

    /** 会话内顺序号，从 0 开始 */
    private Integer seq;

    /** 消息类型编码：1系统提示 2用户提问 3AI回复 4工具结果 5自定义（对应 ChatMessageTypeCode） */
    private Integer messageType;

    /** 单条消息内容（langchain4j ChatMessage 的 JSON 原文） */
    private String content;

    /** 删除标记：0有效 1删除 */
    private Integer delFlag;

    /** 创建时间（数据库自动维护） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库自动维护） */
    private LocalDateTime updatedAt;
}
