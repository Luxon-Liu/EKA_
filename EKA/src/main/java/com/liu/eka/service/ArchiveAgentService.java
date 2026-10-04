package com.liu.eka.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Agent 对话服务：驱动一轮 agent loop（模型决策 -> 调工具 -> 回喂 -> 再决策），
 * 返回最终回复与本轮工具调用链；跨轮记忆按记忆 ID 隔离存取
 *
 * @author Luxon
 * @date 2026/09/04
 */
public interface ArchiveAgentService {

    /**
     * 流式执行一轮 agent 对话：loop 全过程事件经 SSE 逐帧推送
     * （文本增量 -> 工具调用请求/结果 -> 结束或异常）
     *
     * @param memoryId 记忆 ID（后端自增），调用方保证非空
     * @param message  用户本轮问题，调用方保证非空
     * @param fileIds  本轮随消息一起发送的已上传文件 ID 列表，可为空
     * @param userId   当前登录用户 ID，调用方从请求线程取出后显式传入
     *                 （轮次在独立守护线程执行，ThreadLocal 上下文不会自动传递）
     * @return SSE 发射器，调用方订阅后 loop 在后台线程跑完自动关闭
     */
    SseEmitter streamChat(Long memoryId, String message, List<Long> fileIds, String userId);
}
