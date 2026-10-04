package com.liu.eka.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liu.eka.common.UserContext;
import com.liu.eka.entity.conversation.ChatSession;
import com.liu.eka.entity.chat.NewSession;
import com.liu.eka.mapper.ChatSessionMapper;
import com.liu.eka.service.ChatSessionService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 会话服务的实现说明：先让 AI 按首问生成标题，再连标题一次落库
 *
 * @author Luxon
 * @date 2026/09/17
 */
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements ChatSessionService {

    /** 会话标题生成提示词：约束只返标题正文 */
    private static final String TITLE_PROMPT = "你是会话标题生成器。根据用户的第一条提问生成一个简短中文标题，"
            + "15 个字以内，只返回标题正文，不要加引号、不要解释。";

    /** 阻塞对话模型 */
    private final ChatModel chatModel;

    /**
     * 新建会话：先让 AI 按首问生成标题，再连标题一次落库，返回自增 ID
     *
     * @param firstMessage 用户第一条提问原文，调用方保证非空
     * @return 新会话的记忆 ID 与标题；落库失败返回空 ID 配默认标题
     */
    @Override
    public NewSession createSession(String firstMessage) {
        // 步骤 1：AI 按首问生成标题（阻塞等待直接返回，失败用默认标题）
        String title = "新会话";
        try {
            String generated = chatModel.chat(
                    List.of(new SystemMessage(TITLE_PROMPT), new UserMessage(firstMessage)))
                    .aiMessage().text().trim();
            if (!generated.isEmpty()) {
                title = generated;
            }
        } catch (Exception e) {
            title = "新会话";
        }

        // 步骤 2：标题落库到会话表并写入归属用户（新会话消息行本来就是空的，无需再写占位），
        //        失败直接抛错走全局兜底
        ChatSession session = new ChatSession();
        session.setTitle(title);
        session.setUserId(UserContext.requireUserId());
        if (!save(session) || session.getId() == null) {
            throw new IllegalStateException("会话创建失败");
        }
        return new NewSession(session.getId(), title);
    }
}
