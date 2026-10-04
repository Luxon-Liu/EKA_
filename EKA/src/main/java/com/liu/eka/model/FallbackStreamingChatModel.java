package com.liu.eka.model;

import com.liu.eka.util.RetriableErrors;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式对话模型兜底包装：先调主模型，遇可重试异常（连接/超时）且尚未输出任何内容时，
 * 静默改用兜底模型重新发起同一请求；若已开始输出则无法回滚，直接透传错误。
 * 用于 Agent 流式问答，让底层厂商波动对 Agent loop 完全透明
 *
 * @author Luxon
 * @date 2026/09/09
 */
@Slf4j
public class FallbackStreamingChatModel implements StreamingChatModel {

    /** 主模型（首选厂商） */
    private final StreamingChatModel primary;

    /** 兜底模型（连接更稳的备选厂商） */
    private final StreamingChatModel fallback;

    /**
     * 构造兜底包装：持有主备两个真实流式模型
     *
     * @param primary  主模型，优先调用
     * @param fallback 兜底模型，主模型未输出时可重试失败时顶替
     */
    public FallbackStreamingChatModel(StreamingChatModel primary, StreamingChatModel fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    /**
     * 发起一轮流式对话：包装主模型的回调，主模型在产生任何输出前以可重试异常失败时，
     * 静默切换兜底模型重跑整轮（此时前端无感知）；有输出后失败则按原样透传
     *
     * @param request 本次对话请求
     * @param handler 调用方提供的流式回调
     */
    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        // 步骤 1：跟踪"是否已输出内容"与"是否已切换过"，决定失败时能否切兜底
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean switched = new AtomicBoolean(false);

        // 步骤 2：包装回调，转发所有增量事件给调用方，并维护 started 标志
        StreamingChatResponseHandler wrapped = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                started.set(true);
                handler.onPartialResponse(token);
            }

            @Override
            public void onPartialResponse(PartialResponse r, PartialResponseContext ctx) {
                started.set(true);
                handler.onPartialResponse(r, ctx);
            }

            @Override
            public void onPartialThinking(PartialThinking t) {
                started.set(true);
                handler.onPartialThinking(t);
            }

            @Override
            public void onPartialThinking(PartialThinking t, PartialThinkingContext ctx) {
                started.set(true);
                handler.onPartialThinking(t, ctx);
            }

            @Override
            public void onPartialToolCall(PartialToolCall c) {
                started.set(true);
                handler.onPartialToolCall(c);
            }

            @Override
            public void onPartialToolCall(PartialToolCall c, PartialToolCallContext ctx) {
                started.set(true);
                handler.onPartialToolCall(c, ctx);
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall c) {
                started.set(true);
                handler.onCompleteToolCall(c);
            }

            @Override
            public void onCompleteResponse(ChatResponse r) {
                handler.onCompleteResponse(r);
            }

            @Override
            public void onError(Throwable t) {
                // 异步失败路径：满足切换条件则静默切兜底，否则透传
                if (!trySwitch(request, handler, started, switched, t)) {
                    handler.onError(t);
                }
            }
        };

        // 步骤 3：发起主模型调用；同步抛出的可重试异常同样走切换判定
        try {
            primary.chat(request, wrapped);
        } catch (RuntimeException e) {
            if (!trySwitch(request, handler, started, switched, e)) {
                handler.onError(e);
            }
        }
    }

    /**
     * 尝试切换兜底模型重跑整轮请求：仅当未输出任何内容、未切换过且异常可重试三者齐备，
     * 并用 CAS 抢占切换权防止并发重入；切换成功返回 true
     *
     * @param request  本轮对话请求
     * @param target   调用方原始回调（兜底模型直接对接它，不再嵌套包装）
     * @param started  是否已产生输出的标志
     * @param switched 是否已切换过的标志
     * @param t        触发的异常
     * @return 已切换返回 true，未满足切换条件返回 false
     */
    private boolean trySwitch(ChatRequest request, StreamingChatResponseHandler target,
                              AtomicBoolean started, AtomicBoolean switched, Throwable t) {
        // 步骤 1：先决条件校验——已输出/已切换/异常不可重试任一命中则放弃切换
        if (started.get() || switched.get() || !RetriableErrors.isRetriable(t)) {
            return false;
        }
        // 步骤 2：CAS 抢占切换权，避免多线程同时发起两次兜底重试
        if (!switched.compareAndSet(false, true)) {
            return false;
        }
        log.warn("主模型流式调用失败，切换兜底模型重试：{}", t.getMessage());
        // 步骤 3：用兜底模型重跑整轮，直接对接调用方回调；兜底再失败则透传错误
        try {
            fallback.chat(request, target);
        } catch (RuntimeException e) {
            target.onError(e);
        }
        return true;
    }
}