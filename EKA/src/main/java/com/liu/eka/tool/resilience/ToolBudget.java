package com.liu.eka.tool.resilience;

import java.time.Duration;

/**
 * 单次外部调用的预算：单次尝试的超时上限、最大尝试次数、该步骤累计耗时上限。
 * 由调用点在编排层就地声明（如 ToolBudget.ofMillis(2000, 2, 4500)），让预算紧贴它约束的那次调用，
 * 不散落到外部配置文件，避免「配置与实现两处声明、改一处忘一处」
 *
 * @param singleTimeout 单次尝试的超时上限，超时即取消本次尝试
 * @param maxAttempts   允许的最大尝试次数（含首次调用），1 表示不重试
 * @param totalTimeout  该步骤的累计耗时上限，含各次尝试与退避等待
 * @author Luxon
 * @date 2026/09/18
 */
public record ToolBudget(Duration singleTimeout, int maxAttempts, Duration totalTimeout) {

    /**
     * 以毫秒为单位构造预算：统一毫秒粒度，同时覆盖秒级与亚秒级的单次超时（如 1500、4000），
     * 免去逐个手写 Duration
     *
     * @param singleMillis 单次尝试超时毫秒数
     * @param maxAttempts  最大尝试次数
     * @param totalMillis  该步骤累计耗时上限毫秒数
     * @return 预算实例
     */
    public static ToolBudget ofMillis(long singleMillis, int maxAttempts, long totalMillis) {
        return new ToolBudget(
                Duration.ofMillis(singleMillis), maxAttempts, Duration.ofMillis(totalMillis));
    }
}
