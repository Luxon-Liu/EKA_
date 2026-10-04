package com.liu.eka.controller;

import com.liu.eka.common.SessionGuard;
import com.liu.eka.common.UserContext;
import com.liu.eka.entity.chat.ChatRequest;
import com.liu.eka.service.ArchiveAgentService;
import com.liu.eka.util.Texts;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 对话入口接口：流式问答，整轮 agent loop 的全过程事件经 SSE 逐帧推送；
 * 跨轮追问时前端透传 memoryId 以延续记忆
 *
 * @author Luxon
 * @date 2026/09/04
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Validated
public class ArchiveAgentController {

    /** 档案智能体对话服务 */
    private final ArchiveAgentService archiveAgentService;

    /** 会话归属守卫：对话前先确认该会话属于当前登录用户 */
    private final SessionGuard sessionGuard;

    /**
     * 流式对话：loop 全过程事件经 SSE 逐帧推送（文本增量 -> 工具调用请求/结果 -> 结束或异常）；
     * 前端按事件名分发渲染；入参由 @Valid 注解校验，业务异常由全局异常翻译器转 Result
     * （校验失败在建流之前直接 400，不进事件流）
     *
     * @param request 对话请求（含本轮问题、记忆 ID）
     * @return SSE 事件流，全过程推完后由服务端关闭
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        // 步骤 1：记录请求入口（问题只记前 100 字，避免长文本刷屏；入参非空由 @Valid 保证）
        String message = request.getMessage();
        Long memoryId = request.getMemoryId();
        log.info("收到 Agent 流式对话请求：memoryId={}，问题前 100 字={}",
                memoryId, Texts.abbreviate(message, 100));

        // 步骤 2：在请求线程内校验会话归属并取出用户 ID（轮次实际跑在守护线程里，
        //         取不到请求线程的上下文），防止向他人会话发消息
        sessionGuard.requireOwned(memoryId);
        String userId = UserContext.requireUserId();

        // 步骤 3：委托服务层启动流式 loop（用户 ID 显式传入，供守护线程内工具落盘使用）
        return archiveAgentService.streamChat(memoryId, message, request.getFileIds(), userId);
    }
}
