package com.liu.eka.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 会话上下文压缩配置：把 compaction 段各项参数收拢到一处，
 * 供压缩服务与视图组装共同读取，改参数只需动 application.yml
 *
 * @author Luxon
 * @date 2026/09/17
 */
@Getter
@Component
public class CompactionProperties {

    /** 模型上下文窗口总大小（token），按当前主模型填写，用于触发阈值与保留区预算 */
    @Value("${compaction.window-size:1000000}")
    private int windowSize;

    /** 触发压缩的阈值比例：上下文用量超过 windowSize × 该比例即触发摘要压缩 */
    @Value("${compaction.trigger-ratio:0.90}")
    private double triggerRatio;

    /** 保留区比例：压缩时保留最近 windowSize × 该比例 token 的原文，不进摘要 */
    @Value("${compaction.keep-ratio:0.15}")
    private double keepRatio;

    /** 摘要输出的硬上限（token）：作为调用摘要模型时的 maxOutputTokens，防止摘要本身失控 */
    @Value("${compaction.summary-max-tokens:16000}")
    private int summaryMaxTokens;

    /** 单条消息的字符上限：超出即截断（保留头尾），适用于所有非工具参数消息 */
    @Value("${compaction.single-message-max-chars:2000}")
    private int singleMessageMaxChars;

    /** 最近这么多 token 的工具输出保持原文，更老的替换为工具名占位符 */
    @Value("${compaction.keep-recent-tool-output-tokens:8000}")
    private int keepRecentToolOutputTokens;

    /** 摘要提示词里的 token 软上限占「摘要硬上限」的比例：留出余量，避免摘要写满被硬截断 */
    @Value("${compaction.summary-soft-limit-ratio:0.75}")
    private double summarySoftLimitRatio;

    /** 摘要调用的等待超时秒数：超时即放弃本次压缩，本轮按原上下文继续 */
    @Value("${compaction.summary-timeout-seconds:180}")
    private long summaryTimeoutSeconds;
}
