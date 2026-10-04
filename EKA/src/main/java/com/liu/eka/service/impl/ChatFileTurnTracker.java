package com.liu.eka.service.impl;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本轮文件变更追踪器：记录某一轮对话中被 write_file 新建或覆盖的文件 ID，
 * 供轮次收尾时统一把这些文件的占位符锚点回填到本轮最后一条消息。
 * <p>
 * 之所以用全局表按会话 ID 记录、而不是 ThreadLocal：工具执行跑在独立的韧性线程池里，
 * 与请求线程不是同一条线程，ThreadLocal 在池化复用下不可靠；同一会话本身有轮次互斥，
 * 按会话 ID 记录不存在并发写冲突
 *
 * @author Luxon
 * @date 2026/09/21
 */
@Component
public class ChatFileTurnTracker {

    /** 会话 ID -> 本轮被写/改的文件 ID 集合 */
    private final Map<Long, Set<Long>> touchedFiles = new ConcurrentHashMap<>();

    /**
     * 登记一个本轮被写/改的文件
     *
     * @param sessionId 会话 ID，为空时忽略
     * @param fileId    文件 ID，为空时忽略
     */
    public void mark(Long sessionId, Long fileId) {
        if (sessionId == null || fileId == null) {
            return;
        }
        touchedFiles.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet()).add(fileId);
    }

    /**
     * 取出并清空某会话本轮登记的文件集合（轮次收尾时调用，避免污染下一轮）
     *
     * @param sessionId 会话 ID
     * @return 本轮被写/改的文件 ID 集合；无登记时返回空集合
     */
    public Set<Long> drain(Long sessionId) {
        Set<Long> files = touchedFiles.remove(sessionId);
        return files == null ? Set.of() : files;
    }
}
