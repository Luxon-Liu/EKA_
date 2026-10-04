package com.liu.eka.entity.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 新建会话返回体：后端自增生成的记忆 ID 与 AI 按首问生成的标题，
 * 前端拿到后透传 memoryId 进 stream 接口延续本会话
 *
 * @author Luxon
 * @date 2026/09/04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewSession {

    /** 记忆 ID：ai_chat_session 自增主键，后端生成 */
    private Long memoryId;

    /** 会话标题：AI 按首问生成，生成失败时为"新会话" */
    private String title;
}
