package com.liu.eka.tool.resilience;

import lombok.Getter;

/**
 * 工具外部调用失败时抛出的统一异常：内层 ToolGuard 在「重试预算耗尽」或「遇到不可重试错误」时抛出，
 * 由外层 ToolTimeoutExecutor 接住并转成面向模型的错误文本；携带步骤名与已尝试次数便于日志定位
 *
 * @author Luxon
 * @date 2026/09/18
 */
@Getter
public class ToolInvocationException extends RuntimeException {

    /** 失败的外部调用步骤名（与 yml 中 steps 的键一致） */
    private final String step;

    /** 实际已尝试次数（含首次调用） */
    private final int attempts;

    /** 是否属于不可重试的确定性失败：true 表示识别为致命错误后立即返回，未走完重试预算 */
    private final boolean fatal;

    /**
     * 构造工具调用失败异常
     *
     * @param step     失败步骤名
     * @param attempts 已尝试次数
     * @param fatal    是否不可重试的确定性失败
     * @param message  面向日志与模型的失败描述
     * @param cause    最后一次失败的原因，可为 null
     */
    public ToolInvocationException(String step, int attempts, boolean fatal, String message, Throwable cause) {
        super(message, cause);
        this.step = step;
        this.attempts = attempts;
        this.fatal = fatal;
    }
}
