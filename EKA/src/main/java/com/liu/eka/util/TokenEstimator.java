package com.liu.eka.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.model.TokenCountEstimator;
import org.springframework.stereotype.Component;

/**
 * 通用 token 估算器：基于 jtokkit 的 cl100k_base（OpenAI BPE）编码统计 token 数，
 * 对中文约 1 汉字 ≈ 1 token，作为模型窗口预算的近似依据。
 * 用于会话压缩的上下文用量估算，与切块器 DoclingHybridChunker 使用同一套编码口径
 *
 * @author Luxon
 * @date 2026/09/17
 */
@Component
public class TokenEstimator implements TokenCountEstimator {

    /** jtokkit 编码注册器：进程内全局单例，负责按需加载并缓存编码器 */
    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();

    /** 实际使用的编码器：cl100k_base，与切块器口径保持一致 */
    private final Encoding encoding = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    /**
     * 估算一段文本的 token 数
     *
     * @param text 待估算文本，允许为空
     * @return 估算出的 token 数，空文本返回 0
     */
    @Override
    public int estimateTokenCountInText(String text) {
        // 步骤 1：空文本直接返回 0，避免无谓的编码计算
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 步骤 2：按 cl100k_base 编码统计 token 数
        return encoding.countTokens(text);
    }

    /**
     * 估算单条消息的 token 数：按落库口径序列化成 JSON 再统计，
     * 与「消息在数据库里占多少 token」这一语义对齐
     *
     * @param message 待估算消息，不允许为空
     * @return 该消息的估算 token 数
     */
    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        // 步骤 1：用官方序列化器转成 JSON 文本，保证与落库/发送口径一致
        return estimateTokenCountInText(ChatMessageSerializer.messageToJson(message));
    }

    /**
     * 估算一批消息的 token 总数：逐条累加，供上下文预算判断使用
     *
     * @param messages 待估算消息集合，允许为空
     * @return 全部消息的估算 token 数之和，空集合返回 0
     */
    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        // 步骤 1：逐条估算并累加
        int total = 0;
        if (messages == null) {
            return total;
        }
        for (ChatMessage message : messages) {
            total += estimateTokenCountInMessage(message);
        }
        return total;
    }
}
