package com.liu.eka.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liu.eka.common.UserContext;
import com.liu.eka.entity.chat.AgentEvent;
import com.liu.eka.entity.conversation.ChatFileVO;
import com.liu.eka.entity.conversation.ChatMessageRecord;
import com.liu.eka.entity.conversation.ChatMessageTypeCode;
import com.liu.eka.service.ArchiveAgentService;
import com.liu.eka.service.ChatFileService;
import com.liu.eka.service.ChatMessageRecordService;
import com.liu.eka.service.CompactionProgressListener;
import com.liu.eka.service.CompactionService;
import com.liu.eka.agent.ArchiveAgent;
import com.liu.eka.agent.ArchiveAgentFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Agent 对话服务实现：先按需压缩会话上下文，再向工厂拿当轮智能体代理跑流式 loop，
 * 全过程事件逐帧推给前端
 * <p>
 * 并发模型：每个请求单独起一个守护线程执行本轮，请求之间互不阻塞；
 * 同一会话用"运行中标记"互斥，保证该会话同时只有一轮在跑——
 * 两轮并发会交错写记忆（消息 seq 冲突、水位线互相覆盖）
 * <p>
 * 之所以不在请求线程里直接跑：压缩必须在 emitter 返回前完成（构建代理要读压缩后的记忆视图），
 * 而它可能耗时数分钟。若在请求线程同步执行，发射器迟迟不返回、事件被 SseEmitter 缓冲，
 * 前端在压缩期间看不到任何反馈（表现为卡死）
 *
 * @author Luxon
 * @date 2026/09/04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveAgentServiceImpl implements ArchiveAgentService {

    /** SSE 连接超时毫秒数，超时未结束由容器自动关闭 */
    private static final long SSE_TIMEOUT_MILLIS = 600_000L;

    /** 档案智能体代理工厂 */
    private final ArchiveAgentFactory agentFactory;

    /** 上下文压缩服务：每轮对话开始前判断是否需要把较早的对话压成摘要 */
    private final CompactionService compactionService;

    /** 事件 JSON 序列化器（SSE 帧载荷转字符串） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 会话文件业务服务：拼接上传文件清单、回填占位符锚点 */
    private final ChatFileService chatFileService;

    /** 会话消息服务：查本轮消息 ID 边界，用于文件占位符锚定 */
    private final ChatMessageRecordService chatMessageRecordService;

    /** 本轮文件变更追踪器：取本轮被写/改的文件 ID */
    private final ChatFileTurnTracker fileTurnTracker;

    /**
     * 正在处理中的会话 ID：靠 add 的原子性实现"同一会话同时只跑一轮"。
     * 轮次结束（正常结束/流式异常/启动失败）时移除，remove 天然幂等，
     * 因此多个出口各调一次也不会出错；条目仅在轮次进行期间存在，无需额外清理
     */
    private final Set<Long> runningSessions = ConcurrentHashMap.newKeySet();

    /**
     * 流式执行一轮 agent 对话：请求线程只建发射器并立即返回，
     * 本轮实际工作交给一个独立守护线程，事件逐帧推送，结束或异常帧发出后关闭连接
     *
     * @param memoryId 记忆 ID（后端自增），调用方保证非空
     * @param message  用户本轮问题，调用方保证非空
     * @param fileIds  本轮随消息一起发送的已上传文件 ID 列表，可为空
     * @param userId   当前登录用户 ID，由请求线程取出后显式传入
     * @return SSE 发射器（线程安全，跨线程推送）
     */
    @Override
    public SseEmitter streamChat(Long memoryId, String message, List<Long> fileIds, String userId) {
        // 步骤 1：建发射器，事件出口直接推 SSE（SseEmitter 线程安全，可跨线程发送）；
        // closed 标志记录连接是否已不可用，一旦置位后续所有事件全部丢弃，避免向死连接反复写帧
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean(false);
        BiConsumer<AgentEvent, Map<String, Object>> eventSink = (type, data) -> {
            if (!closed.get()) {
                send(emitter, type, data, closed);
            }
        };

        // 步骤 2：每个请求单独起一个守护线程执行本轮——请求线程立刻返回发射器并完成注册，
        // 压缩期间的事件才能真正实时推给前端
        Thread worker = new Thread(
                () -> {
                    // 步骤 2.1：把请求线程的用户身份带进本轮线程——工具生成文件的落盘路径
                    // 依赖 UserContext 取用户 ID，ThreadLocal 不跨线程，必须显式写入
                    UserContext.setUserId(userId);
                    try {
                        runTurn(memoryId, message, fileIds, eventSink, emitter, closed);
                    } catch (Exception e) {
                        // 兜底：线程里的漏网异常不会被任何人捕获，必须自己转成错误帧，
                        // 否则前端会永远等不到 DONE/ERROR 而悬挂
                        log.error("Agent 轮次执行异常：memoryId={}", memoryId, e);
                        if (!closed.get()) {
                            eventSink.accept(AgentEvent.ERROR, AgentEvent.errorData(friendlyError(e)));
                            emitter.complete();
                        }
                    } finally {
                        // 步骤 2.2：本轮线程结束即清上下文，防止线程池复用串号
                        UserContext.clear();
                    }
                },
                "agent-loop-" + memoryId);
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }

    /**
     * 一轮对话的实际执行体：先抢该会话的运行标记保证同会话串行，再按需压缩上下文
     * （进度以 COMPACTION_* 事件推送），最后构建代理并启动流式 loop。
     * 标记的清除点在轮次真正结束时，因此覆盖"压缩 + 构建 + 整轮回答"全过程
     *
     * @param memoryId  记忆 ID
     * @param message   用户本轮问题
     * @param fileIds   本轮随消息一起发送的已上传文件 ID 列表，可为空
     * @param eventSink 事件出口（已封装连接可用性判断）
     * @param emitter   本轮发射器
     * @param closed    连接可用性标志
     */
    private void runTurn(Long memoryId, String message, List<Long> fileIds,
                         BiConsumer<AgentEvent, Map<String, Object>> eventSink,
                         SseEmitter emitter, AtomicBoolean closed) {
        // 步骤 1：会话标识缺失属于非法请求，直接回错误帧（避免后续 add(null) 抛 NPE）
        if (memoryId == null) {
            eventSink.accept(AgentEvent.ERROR, AgentEvent.errorData("会话标识缺失，请刷新页面后重试"));
            emitter.complete();
            return;
        }

        // 步骤 2：抢本轮执行权——add 成功表示该会话当前没有轮次在跑；
        // 失败说明上一轮尚未结束（可能正在压缩），直接拒绝，避免两轮并发交错写记忆
        if (!runningSessions.add(memoryId)) {
            log.warn("同一会话已有进行中的轮次，拒绝本轮并发请求：memoryId={}", memoryId);
            eventSink.accept(AgentEvent.ERROR, AgentEvent.errorData("上一条消息还在处理中，请稍候再发"));
            emitter.complete();
            return;
        }

        // 步骤 3：记录本轮消息 ID 起点（收尾锚定上传文件用），并把上传文件清单拼进用户消息，
        //         让模型知道本轮带了哪些文件、文件名是什么，便于后续按名读取
        long beforeMaxId = maxMessageId(memoryId);
        String effectiveMessage = appendFileNotice(message, chatFileService.listVOByIds(fileIds));

        try {
            // 步骤 4：本轮开始前压缩上下文——只有超出窗口触发线才会真正调用摘要模型，
            // 压缩的开始/成功/失败都推事件给前端展示；未触发压缩则一个事件都不发
            compactionService.compactIfNeeded(memoryId, new CompactionProgressListener() {
                @Override
                public void onStart() {
                    eventSink.accept(AgentEvent.COMPACTION_START, AgentEvent.compactionStartData());
                }

                @Override
                public void onCompleted(int compactedMessages, int summaryTokens) {
                    eventSink.accept(AgentEvent.COMPACTION_DONE,
                            AgentEvent.compactionDoneData(compactedMessages, summaryTokens));
                }

                @Override
                public void onFailed(String reason) {
                    eventSink.accept(AgentEvent.COMPACTION_ERROR, AgentEvent.compactionErrorData(reason));
                }
            });

            // 步骤 5：构建当轮代理（压缩已推进水位线，此处读取的是压缩后的记忆视图）
            ArchiveAgent agent = agentFactory.build(memoryId, eventSink);

            // 步骤 6：启动流式 loop；标记的清除放在回调里，确保覆盖整轮回答，
            // 而不是 start() 返回时就清掉（start 只负责发起，回答还在框架线程里跑）
            agent.chatStream(memoryId, effectiveMessage)
                    .onPartialResponse(text -> eventSink.accept(AgentEvent.STREAM_TEXT, AgentEvent.textData(text)))
                    .onCompleteResponse(response -> {
                        try {
                            // 先回填文件锚点再发 DONE：前端收到 DONE 后会重拉文件列表，
                            // 此时锚点必须已落库，否则卡片挂不到本轮消息上
                            finishTurn(memoryId, beforeMaxId, fileIds, true);
                            // 连接已断则不再补发 DONE，避免对已关闭发射器做多余操作
                            if (!closed.get()) {
                                eventSink.accept(AgentEvent.DONE, AgentEvent.doneData(response.aiMessage().text()));
                                emitter.complete();
                            }
                        } finally {
                            runningSessions.remove(memoryId);
                        }
                    })
                    .onError(error -> {
                        try {
                            log.error("Agent 流式对话失败：memoryId={}", memoryId, error);
                            if (!closed.get()) {
                                eventSink.accept(AgentEvent.ERROR, AgentEvent.errorData(friendlyError(error)));
                                emitter.complete();
                            }
                        } finally {
                            finishTurn(memoryId, beforeMaxId, fileIds, false);
                            runningSessions.remove(memoryId);
                        }
                    })
                    .start();
        } catch (Exception e) {
            // start 之前（压缩、构建）或 start 自身抛出的异常：回调不会再来，
            // 必须在此立即清掉标记，否则该会话会被永久占住、用户再也发不出消息
            log.error("Agent 轮次启动失败：memoryId={}", memoryId, e);
            finishTurn(memoryId, beforeMaxId, fileIds, false);
            runningSessions.remove(memoryId);
            if (!closed.get()) {
                eventSink.accept(AgentEvent.ERROR, AgentEvent.errorData(friendlyError(e)));
                emitter.complete();
            }
        }
    }

    /**
     * 轮次收尾：回填文件占位符锚点。上传的文件锚到本轮用户消息；
     * 本轮生成/修改的文件只在正常完成时才锚到本轮最后一条 AI 消息——
     * 出错或中断的轮次里 AI 消息根本没落库，照样锚定会退化成"挂到用户消息下"，
     * 故此时只清空登记而不回填锚点（文件仍可在会话文件面板中找到）。
     * 失败不影响本轮结果，仅记日志
     *
     * @param memoryId      记忆 ID
     * @param beforeMaxId   本轮开始前的最大消息 ID
     * @param uploadFileIds 本轮上传的文件 ID 列表，可为空
     * @param completed     本轮是否正常完成（拿到了完整回答）
     */
    private void finishTurn(Long memoryId, long beforeMaxId, List<Long> uploadFileIds, boolean completed) {
        try {
            // 步骤 1：本轮的用户消息；无论本轮是否正常结束，上传文件都锚到它
            Long userMessageId = firstUserMessageIdAfter(memoryId, beforeMaxId);
            chatFileService.anchorFiles(uploadFileIds, userMessageId);

            // 步骤 2：生成文件仅在正常完成时锚到本轮最后一条 AI 消息；
            //         未正常结束时只清空登记，避免锚点错落到用户消息上
            Set<Long> touchedFiles = fileTurnTracker.drain(memoryId);
            if (completed) {
                chatFileService.anchorFiles(touchedFiles, maxMessageId(memoryId));
            }
        } catch (Exception e) {
            log.warn("文件占位符锚点回填失败：memoryId={}", memoryId, e);
        }
    }

    /**
     * 把上传文件清单拼进用户消息：模型据此知道本轮带了哪些文件、文件名是什么
     *
     * @param message     用户原始问题
     * @param uploadFiles 本轮上传的文件列表，可为空
     * @return 追加文件清单后的消息；无文件时原样返回
     */
    private String appendFileNotice(String message, List<ChatFileVO> uploadFiles) {
        if (uploadFiles.isEmpty()) {
            return message;
        }
        // 清单里同时给出文件 ID 与文件名：ID 供模型调用 read_file / write_file 时引用
        String names = uploadFiles.stream()
                .map(file -> "[id=" + file.getId() + "] " + file.getFileName())
                .collect(Collectors.joining("、"));
        return message + "\n\n【本轮上传文件】" + names;
    }

    /**
     * 查该会话当前最大的消息 ID
     *
     * @param sessionId 会话 ID
     * @return 最大消息 ID；会话内无消息时返回 0
     */
    private long maxMessageId(Long sessionId) {
        ChatMessageRecord record = chatMessageRecordService.query()
                .eq(true, "session_id", sessionId)
                .eq(true, "del_flag", 0)
                .orderByDesc(true, "id")
                .last("limit 1")
                .one();
        return record == null ? 0L : record.getId();
    }

    /**
     * 查该会话中 ID 大于给定值的第一条用户消息 ID（本轮用户消息）
     *
     * <p>只认用户消息：会话首轮的系统提示是随本轮记忆写入的（晚于本轮起点），
     * 若只按"第一条消息"取会把系统提示当成用户消息，文件卡片就会挂到界面上根本不渲染的
     * 系统消息下，导致卡片永远不显示</p>
     *
     * @param sessionId 会话 ID
     * @param afterId   起始消息 ID（不含）
     * @return 第一条用户消息 ID；无匹配时返回 null
     */
    private Long firstUserMessageIdAfter(Long sessionId, long afterId) {
        ChatMessageRecord record = chatMessageRecordService.query()
                .eq(true, "session_id", sessionId)
                .eq(true, "del_flag", 0)
                .eq(true, "message_type", ChatMessageTypeCode.USER.code())
                .gt(true, "id", afterId)
                .orderByAsc(true, "id")
                .last("limit 1")
                .one();
        return record == null ? null : record.getId();
    }

    /**
     * 把异常翻译成适合展示给非研发用户的友好中文提示：遍历异常链收集消息，
     * 按常见类型（超时/网络/内部异常）给出不暴露技术细节的中文；技术细节已由 log.error 落日志
     *
     * @param error 流式对话过程中抛出的异常
     * @return 面向用户的中文友好提示
     */
    private static String friendlyError(Throwable error) {
        // 步骤 1：遍历 cause 链，拼出全部异常消息作为类型判断依据
        StringBuilder chain = new StringBuilder();
        Throwable cur = error;
        while (cur != null) {
            if (cur.getMessage() != null) {
                chain.append(cur.getMessage()).append('\n');
            }
            cur = cur.getCause();
        }
        String text = chain.toString();

        // 步骤 2：按类型给出友好文案，均不含技术术语
        if (text.contains("timeout") || text.contains("Timeout") || text.contains("超时")) {
            return "当前服务响应较慢，请稍后重试";
        }
        if (text.contains("Connection reset") || text.contains("SocketException")
                || text.contains("Broken pipe") || text.contains("connect timed out")
                || text.contains("Connection refused")) {
            return "网络出现波动，请稍后重试";
        }
        if (text.contains("NoClassDefFoundError") || text.contains("ClassNotFoundException")) {
            return "服务内部出现异常，请稍后重试";
        }
        return "服务暂时开小差，请稍后重试";
    }

    /**
     * 发送一帧 SSE 事件：事件名取枚举常量名，载荷转 JSON 字符串；
     * 发送失败说明连接已断（客户端中止/网络断开），置关闭标志后直接放弃，
     * 不再对发射器做 completeWithError 等操作——否则会在"已完成的发射器"上再抛
     * IllegalStateException，产生层层嵌套的日志噪音
     *
     * @param emitter SSE 发射器
     * @param type    事件类型
     * @param data    事件载荷
     * @param closed  连接可用性标志，发送失败时置为不可用
     */
    private void send(SseEmitter emitter, AgentEvent type, Map<String, Object> data, AtomicBoolean closed) {
        // 步骤 1：正常发送一帧；失败说明连接已断，置标志后静默放弃
        try {
            emitter.send(SseEmitter.event()
                    .name(type.name())
                    .data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            // 步骤 2：只记录一次断开，不再尝试任何发射器操作，避免死连接上的连锁异常
            if (closed.compareAndSet(false, true)) {
                log.warn("SSE 连接已断开，后续事件不再推送：{}", e.getMessage());
            }
        }
    }
}
