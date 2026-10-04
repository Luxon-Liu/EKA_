package com.liu.eka.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.liu.eka.entity.conversation.ChatMessageRecord;

import java.util.List;

/**
 * AI 会话消息服务的接口说明：消息行 append-only 全量存档，
 * 对外提供「前端全量读」与「AI 按水位线增量读」两个视角的查询
 *
 * @author Luxon
 * @date 2026/09/17
 */
public interface ChatMessageRecordService extends IService<ChatMessageRecord> {

    /**
     * 按会话查询全部消息行：前端加载会话原文用，只取有效行、按顺序号升序
     *
     * @param sessionId 会话 ID
     * @return 该会话的全部消息行，无记录时返回空列表
     */
    List<ChatMessageRecord> listBySessionId(long sessionId);

    /**
     * 按会话查询水位线之后的消息行：AI 加载上下文用，
     * 水位线之前的消息已被摘要替代，无需再读
     *
     * @param sessionId      会话 ID
     * @param afterMessageId 水位线（只取 id 大于该值的消息）
     * @return 水位线之后的有效消息行，按顺序号升序，无记录时返回空列表
     */
    List<ChatMessageRecord> listAfterId(long sessionId, long afterMessageId);

    /**
     * 查询该会话最后一条指定类型的消息行：用于系统提示词的幂等判断
     * （框架每轮都会重复添加同一份系统提示词）
     *
     * @param sessionId   会话 ID
     * @param messageType 消息类型编码（见 ChatMessageTypeCode）
     * @return 最后一条匹配的消息行，无匹配时返回 null
     */
    ChatMessageRecord findLastByType(long sessionId, int messageType);

    /**
     * 取该会话当前最大的顺序号：追加写入时接在其后编排顺序号
     *
     * @param sessionId 会话 ID
     * @return 当前最大顺序号，会话内无消息时返回 null
     */
    Integer maxSeq(long sessionId);

    /**
     * 清空指定会话的全部消息行：物理删除，仅供 deleteMessages 保留实现使用，
     * 业务上正常流程不调用（历史永远全量存档）
     *
     * @param sessionId 会话 ID
     */
    void removeBySessionId(long sessionId);
}
