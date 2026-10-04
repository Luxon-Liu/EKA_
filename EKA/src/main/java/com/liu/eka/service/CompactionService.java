package com.liu.eka.service;

/**
 * 会话上下文压缩服务的接口说明：在每轮对话开始前判断上下文用量，
 * 超阈值时把较早的对话压成结构化摘要并推进水位线，防止长会话超出模型窗口
 *
 * @author Luxon
 * @date 2026/09/17
 */
public interface CompactionService {

    /**
     * 按需压缩指定会话：估算上下文用量，超过窗口的触发比例时，
     * 把保留区之前的消息连同旧摘要一起压成新摘要，并 CAS 推进水位线；
     * 全过程经 listener 回调通知调用方，供前端展示压缩进度。
     * 压缩失败或压缩后仍超线时放弃压缩、不改动任何数据（由调用方继续正常对话）
     *
     * @param memoryId 会话 ID，调用方保证非空
     * @param listener 压缩进度回调，调用方保证非空
     */
    void compactIfNeeded(Long memoryId, CompactionProgressListener listener);
}
