package com.liu.eka.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.liu.eka.entity.conversation.ChatSession;
import com.liu.eka.entity.chat.NewSession;

/**
 * AI 会话服务的接口说明：新建会话（占位建行 + AI 按首问生成标题）
 *
 * @author Luxon
 * @date 2026/09/17
 */
public interface ChatSessionService extends IService<ChatSession> {

    /**
     * 新建会话：在会话表占一行拿到自增 ID，再让 AI 按首问生成标题写回
     *
     * @param firstMessage 用户第一条提问原文，调用方保证非空
     * @return 新会话的记忆 ID 与标题；建行失败返回空 ID 配默认标题
     */
    NewSession createSession(String firstMessage);
}
