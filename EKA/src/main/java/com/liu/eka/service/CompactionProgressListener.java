package com.liu.eka.service;

/**
 * 上下文压缩进度回调：把压缩过程中的关键节点（开始 / 成功 / 失败）通知调用方，
 * 供上层转成 SSE 事件推给前端展示；压缩服务本身不感知具体推送方式
 * <p>
 * 未触发压缩（用量未超触发线、没有可压缩的历史）时不回调任何方法，
 * 前端据此只在真正发生压缩时才出现提示
 *
 * @author Luxon
 * @date 2026/09/18
 */
public interface CompactionProgressListener {

    /**
     * 压缩开始：已确定用量超触发线且存在可压缩历史、即将调用摘要模型时触发；
     * 该阶段耗时可达分钟级，前端需据此从"等待回答"切换到"正在压缩上下文"
     */
    void onStart();

    /**
     * 压缩成功：摘要已生成并 CAS 写回水位线后触发
     *
     * @param compactedMessages 本次被压成摘要的早期消息条数
     * @param summaryTokens 新摘要占用的 token 数
     */
    void onCompleted(int compactedMessages, int summaryTokens);

    /**
     * 压缩失败：摘要为空、CAS 写回失败或过程异常导致本次放弃压缩时触发，
     * 本轮对话按原上下文继续
     *
     * @param reason 面向用户的失败原因（不含技术细节，技术细节由服务端日志记录）
     */
    void onFailed(String reason);
}
