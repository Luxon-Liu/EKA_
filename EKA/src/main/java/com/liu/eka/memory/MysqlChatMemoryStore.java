package com.liu.eka.memory;

import com.liu.eka.entity.conversation.ChatMessageRecord;
import com.liu.eka.entity.conversation.ChatMessageTypeCode;
import com.liu.eka.entity.conversation.ChatSession;
import com.liu.eka.service.ChatMessageRecordService;
import com.liu.eka.service.ChatSessionService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 MySQL 的 ChatMemoryStore 实现：会话消息拆到 ai_chat_message 表一行一条，
 * content 存单条 langchain4j ChatMessage JSON、seq 记录会话内累计先后。
 * <p>
 * 存储语义为 append-only：入库只追加、绝不删改历史行，DB 永远全量存档；
 * 上下文压缩只改「加载时的视图」——靠会话表的摘要水位线决定从哪条消息开始加载，
 * 水位线之前的原文已被摘要替代，不再读入。
 * <p>
 * 系统提示词特殊处理：框架每轮对话都会把同一份系统提示词重复 add 一次，
 * 这里做幂等去重；且它永久保留、不参与压缩，加载时始终取最新一条置于消息序列最前
 *
 * @author Luxon
 * @date 2026/09/04
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlChatMemoryStore implements ChatMemoryStore {

    /** 会话消息行读写服务：按会话与水路位查询、批量追加消息表 */
    private final ChatMessageRecordService chatMessageRecordService;

    /** 会话服务：读取会话行上的摘要水位线 */
    private final ChatSessionService chatSessionService;

    /**
     * 加载会话上下文：返回「最新系统提示词 + 水位线之后的消息」，
     * 水位线之前的消息已被摘要替代，由上层 CompactingChatMemory 补入摘要文本；
     * 无记录时返回空列表（框架视为新会话）
     *
     * @param memoryId 会话 ID（AiServices 的 memoryId）
     * @return 该会话加载用的消息列表（含系统提示词，不含摘要），无记录时返回空列表
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        long sessionId = ((Number) memoryId).longValue();
        try {
            // 步骤 1：取该会话最新的系统提示词行——它永久保留、不参与压缩，需独立于水位线加载
            ChatMessageRecord systemRow =
                    chatMessageRecordService.findLastByType(sessionId, ChatMessageTypeCode.SYSTEM.code());

            // 步骤 2：取水位线之后的消息行，水位线之前的原文已被摘要覆盖，不再读入
            long watermark = currentWatermark(sessionId);
            List<ChatMessageRecord> rows = chatMessageRecordService.listAfterId(sessionId, watermark);

            // 步骤 3：组装序列，系统提示词固定在最前；行里的系统消息跳过，避免与步骤 1 重复
            List<ChatMessage> messages = new ArrayList<>(rows.size() + 1);
            if (systemRow != null) {
                messages.add(ChatMessageDeserializer.messageFromJson(systemRow.getContent()));
            }
            for (ChatMessageRecord row : rows) {
                if (row.getMessageType() != null && row.getMessageType() == ChatMessageTypeCode.SYSTEM.code()) {
                    continue;
                }
                messages.add(ChatMessageDeserializer.messageFromJson(row.getContent()));
            }
            return messages;
        } catch (Exception e) {
            throw new IllegalStateException("读取会话记忆失败，memoryId=" + sessionId, e);
        }
    }

    /**
     * 追加写入会话消息：只把本次传入的消息作为增量落库，绝不触碰已有历史行；
     * 系统提示词做幂等去重（与已有的最新一条内容相同则跳过），避免框架每轮重复添加时刷出重复行
     *
     * @param memoryId 会话 ID
     * @param messages 本次新增的消息（CompactingChatMemory 保证只传增量）
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 步骤 1：空批次直接返回，避免无意义的事务与 SQL
        if (messages == null || messages.isEmpty()) {
            return;
        }
        long sessionId = ((Number) memoryId).longValue();
        try {
            // 步骤 2：取当前最大顺序号，本次新增消息接在其后，保持会话内累计先后
            Integer maxSeq = chatMessageRecordService.maxSeq(sessionId);
            int nextSeq = maxSeq == null ? 0 : maxSeq + 1;

            // 步骤 3：逐条序列化组装消息行，系统提示词与已有最新一条内容相同则跳过
            List<ChatMessageRecord> rows = new ArrayList<>(messages.size());
            for (ChatMessage message : messages) {
                String json = ChatMessageSerializer.messageToJson(message);
                if (message instanceof SystemMessage && isSameAsLatestSystemMessage(sessionId, json)) {
                    continue;
                }
                rows.add(ChatMessageRecord.builder()
                        .sessionId(sessionId)
                        .seq(nextSeq++)
                        .messageType(ChatMessageTypeCode.codeOf(message.type()))
                        .content(json)
                        .build());
            }

            // 步骤 4：批量追加，历史行原样保留（压缩只靠水位线控制读取范围，不删数据）
            if (!rows.isEmpty()) {
                chatMessageRecordService.saveBatch(rows);
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入会话记忆失败，memoryId=" + sessionId, e);
        }
    }

    /**
     * 清空指定会话的全部记忆：物理删除该会话的消息行，会话本身保留。
     * 仅供框架 clear 语义保留实现，正常业务不调用（历史全量存档）
     *
     * @param memoryId 会话 ID
     */
    @Override
    public void deleteMessages(Object memoryId) {
        // 步骤 1：按会话清空消息行，无记录时静默成功
        long sessionId = ((Number) memoryId).longValue();
        try {
            chatMessageRecordService.removeBySessionId(sessionId);
        } catch (Exception e) {
            throw new IllegalStateException("删除会话记忆失败，memoryId=" + sessionId, e);
        }
    }

    /**
     * 读当前摘要水位线：会话不存在或从未压缩时按 0 处理（等价于从头加载）
     *
     * @param sessionId 会话 ID
     * @return 摘要已覆盖到的最后一条消息 ID，未压缩时返回 0
     */
    private long currentWatermark(long sessionId) {
        // 步骤 1：按主键查会话行，取水位线字段
        ChatSession session = chatSessionService.getById(sessionId);
        if (session == null || session.getSummaryUptoMessageId() == null) {
            return 0L;
        }
        return session.getSummaryUptoMessageId();
    }

    /**
     * 判断待写入的系统提示词是否与已有的最新一条完全一致：
     * 框架每轮都会重复添加同一份提示词，内容一致即视为重复，无需再落一行
     *
     * @param sessionId 会话 ID
     * @param json      待写入系统提示词的 JSON 原文
     * @return 已存在相同内容返回 true
     */
    private boolean isSameAsLatestSystemMessage(long sessionId, String json) {
        // 步骤 1：取最后一条系统提示词行，比较内容
        ChatMessageRecord latest = chatMessageRecordService.findLastByType(sessionId, ChatMessageTypeCode.SYSTEM.code());
        return latest != null && json.equals(latest.getContent());
    }
}
