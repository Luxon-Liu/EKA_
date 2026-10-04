package com.liu.eka.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liu.eka.entity.conversation.ChatMessageRecord;
import com.liu.eka.mapper.ChatMessageRecordMapper;
import com.liu.eka.service.ChatMessageRecordService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 会话消息服务的实现说明：消息行只追加不删除，查询按「全量」与「水位线之后」两种口径提供
 *
 * @author Luxon
 * @date 2026/09/17
 */
@Service
public class ChatMessageRecordServiceImpl extends ServiceImpl<ChatMessageRecordMapper, ChatMessageRecord>
        implements ChatMessageRecordService {

    /**
     * 按会话查询全部消息行：只取有效行，按顺序号升序返回（前端加载完整会话原文用）
     *
     * @param sessionId 会话 ID
     * @return 该会话的全部消息行，无记录时返回空列表
     */
    @Override
    public List<ChatMessageRecord> listBySessionId(long sessionId) {
        // 步骤 1：按会话与有效标记过滤，顺序号升序还原消息先后
        return query().eq(true, "session_id", sessionId)
                .eq(true, "del_flag", 0)
                .orderByAsc(true, "seq")
                .list();
    }

    /**
     * 按会话查询水位线之后的消息行：只取有效行，按顺序号升序返回（AI 加载上下文用）
     *
     * @param sessionId      会话 ID
     * @param afterMessageId 水位线（只取 id 大于该值的消息）
     * @return 水位线之后的消息行，无记录时返回空列表
     */
    @Override
    public List<ChatMessageRecord> listAfterId(long sessionId, long afterMessageId) {
        // 步骤 1：用自增主键做水位线过滤，主键单调递增即时间线先后
        return query().eq(true, "session_id", sessionId)
                .eq(true, "del_flag", 0)
                .gt(true, "id", afterMessageId)
                .orderByAsc(true, "seq")
                .list();
    }

    /**
     * 查询该会话最后一条指定类型的消息行：按主键倒序取第一条
     *
     * @param sessionId   会话 ID
     * @param messageType 消息类型编码
     * @return 最后一条匹配的消息行，无匹配时返回 null
     */
    @Override
    public ChatMessageRecord findLastByType(long sessionId, int messageType) {
        // 步骤 1：按会话与类型过滤，主键倒序取最新一条
        return query().eq(true, "session_id", sessionId)
                .eq(true, "message_type", messageType)
                .orderByDesc(true, "id")
                .last("LIMIT 1")
                .one();
    }

    /**
     * 取该会话当前最大的顺序号：按顺序号倒序取第一条的 seq
     *
     * @param sessionId 会话 ID
     * @return 当前最大顺序号，会话内无消息时返回 null
     */
    @Override
    public Integer maxSeq(long sessionId) {
        // 步骤 1：按会话过滤，顺序号倒序取最新一条，只取 seq 值
        ChatMessageRecord last = query().eq(true, "session_id", sessionId)
                .orderByDesc(true, "seq")
                .last("LIMIT 1")
                .one();
        return last == null ? null : last.getSeq();
    }

    /**
     * 清空指定会话的全部消息行：物理删除，无记录时静默成功
     *
     * @param sessionId 会话 ID
     */
    @Override
    public void removeBySessionId(long sessionId) {
        // 步骤 1：按会话物理删除
        update().eq(true, "session_id", sessionId).remove();
    }
}
