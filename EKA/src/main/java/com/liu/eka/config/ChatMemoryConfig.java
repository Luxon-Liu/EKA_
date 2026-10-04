package com.liu.eka.config;

import com.liu.eka.memory.CompactingChatMemory;
import com.liu.eka.memory.ConversationViewAssembler;
import com.liu.eka.memory.MysqlChatMemoryStore;
import com.liu.eka.service.ChatSessionService;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆配置：记忆实现（带上下文压缩的 CompactingChatMemory）与存储（MySQL）的装配收拢到一处；
 * 换记忆实现、改压缩策略只改这里，不碰 agent 组装。
 * 记忆窗口不再按「条数」截断，而是由 compaction 的窗口预算与摘要水位线共同控制
 *
 * @author Luxon
 * @date 2026/09/04
 */
@Configuration
public class ChatMemoryConfig {

    /**
     * 会话记忆提供器：按 memoryId 为每个会话建独立的压缩记忆实例，读写 MySQL 存储；
     * 提供器本身无状态可多线程共享，实例由框架按 memoryId 缓存复用，会话之间天然隔离
     *
     * @param chatMemoryStore    MySQL 会话记忆存储，各会话共享同一存储按 ID 读写
     * @param chatSessionService 会话服务，供记忆视图读取摘要
     * @param viewAssembler      视图组装器，供记忆视图做占位符化与截断
     * @return 记忆提供器（记忆 ID -> 该会话的记忆实例）
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(MysqlChatMemoryStore chatMemoryStore,
                                                 ChatSessionService chatSessionService,
                                                 ConversationViewAssembler viewAssembler) {
        return memoryId -> new CompactingChatMemory(memoryId, chatMemoryStore, chatSessionService, viewAssembler);
    }
}
