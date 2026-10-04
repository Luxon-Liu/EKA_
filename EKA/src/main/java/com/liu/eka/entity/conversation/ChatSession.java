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
 * AI 会话行的实体说明（对应 ai_chat_session 表）：只承载会话元信息（标题等），
 * 消息正文已拆到 ai_chat_message 表按条存放
 *
 * @author Luxon
 * @date 2026/09/17
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_chat_session")
public class ChatSession {

    /** 会话 ID（自增主键，即前端透传的 memoryId） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户 ID（目前业务不用插入，空着） */
    private String userId;

    /** 会话标题（AI 按首问生成） */
    private String title;

    /** 删除标记：0有效 1删除 */
    private Integer delFlag;

    /** 创建时间（数据库自动维护） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库自动维护） */
    private LocalDateTime updatedAt;

    /** 上一次模型调用的真实 input token 数：压缩触发判断的基线，为空或 0 时退化为全量估算 */
    private Long lastInputTokens;

    /** 当前会话的结构化摘要：压缩产物，只存本表不落消息表，为空表示尚未压缩 */
    private String summary;

    /** 摘要水位线：摘要已覆盖到最后哪条消息 ID，0 表示从未压缩（加载时只取 id 大于该值的消息） */
    private Long summaryUptoMessageId;

    /** 摘要自身 token 数：估算上下文用量时直接取用，避免每轮重新估算整段摘要 */
    private Integer summaryTokenCount;

    /** 累计压缩次数：观测与排查用 */
    private Integer compactCount;
}
