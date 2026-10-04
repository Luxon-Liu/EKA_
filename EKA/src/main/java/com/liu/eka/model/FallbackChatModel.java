package com.liu.eka.model;

import com.liu.eka.util.RetriableErrors;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 阻塞对话模型兜底包装：先调主模型，遇可重试异常（连接/超时）静默切兜底模型重试，
 * 用于标题生成等一次性调用，让底层厂商波动对调用方完全透明
 *
 * @author Luxon
 * @date 2026/09/09
 */
@Slf4j
public class FallbackChatModel implements ChatModel {

    /** 主模型（首选厂商） */
    private final ChatModel primary;

    /** 兜底模型（连接更稳的备选厂商） */
    private final ChatModel fallback;

    /**
     * 构造兜底包装：持有主备两个真实模型
     *
     * @param primary  主模型，优先调用
     * @param fallback 兜底模型，主模型可重试失败时顶替
     */
    public FallbackChatModel(ChatModel primary, ChatModel fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    /**
     * 调用主模型，失败则切兜底模型重试（仅当异常属于可重试的连接/超时类）
     *
     * @param request 本次对话请求
     * @return 主模型或兜底模型返回的完整响应
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        // 步骤 1：优先走主模型，成功直接返回
        try {
            return primary.chat(request);
        } catch (RuntimeException e) {
            // 步骤 2：可重试异常才切兜底，其余（参数错误等）保持原样抛出
            if (!RetriableErrors.isRetriable(e)) {
                throw e;
            }
            log.warn("主模型调用失败，切换兜底模型重试：{}", e.getMessage());
            return fallback.chat(request);
        }
    }
}