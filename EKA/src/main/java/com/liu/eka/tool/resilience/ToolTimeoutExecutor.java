package com.liu.eka.tool.resilience;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行的外层装饰器（langchain4j ToolExecutor 适配）：只负责「整个工具方法总超时」，
 * 把工具方法丢进独立线程执行并计时，超时即取消并返回错误文本；内层各步骤的单次超时与重试
 * 已由 ToolGuard 在编排层完成，本层管的是它们的累积耗时（内层管不了累积）。
 * 正常结果原样透传；内层预算耗尽或不可重试错误在此转成 ToolExecutionResult(isError=true) 的文本
 * 回给模型，不炸断整轮；参数错误则原样抛出，交给框架的 toolArgumentsErrorHandler 处理，避免串味
 *
 * @author Luxon
 * @date 2026/09/18
 */
@Slf4j
public class ToolTimeoutExecutor implements ToolExecutor {

    /** 工具方法名，用于日志与面向模型的错误文案 */
    private final String toolName;

    /** 真正的执行器：DefaultToolExecutor（参数异常包装开启、执行异常外抛开启） */
    private final ToolExecutor delegate;

    /** 整个工具方法的总超时上限 */
    private final Duration totalTimeout;

    /** 外层线程池：工具方法在此隔离执行，当前线程只负责计时与取消 */
    private final ExecutorService pool;

    /**
     * 构造外层装饰器
     *
     * @param toolName     工具方法名
     * @param delegate     被装饰的真实执行器
     * @param totalTimeout 整个工具方法的总超时
     * @param pool         外层执行线程池
     */
    public ToolTimeoutExecutor(String toolName, ToolExecutor delegate, Duration totalTimeout, ExecutorService pool) {
        this.toolName = toolName;
        this.delegate = delegate;
        this.totalTimeout = totalTimeout;
        this.pool = pool;
    }

    /**
     * 无上下文的执行入口：用会话记忆 ID 组装最小调用上下文后复用带上下文的执行逻辑，
     * 保证这条入口同样受总超时保护
     *
     * @param request  工具调用请求
     * @param memoryId 会话记忆 ID
     * @return 工具执行的文本结果
     */
    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        InvocationContext context = InvocationContext.builder().chatMemoryId(memoryId).build();
        return executeWithContext(request, context).resultText();
    }

    /**
     * 在总超时内执行整个工具方法：超时取消并返回错误文本，内层失败转错误文本，
     * 参数错误原样抛出交框架的参数错误处理器
     *
     * @param request 工具调用请求（工具名与参数）
     * @param context 调用上下文（含会话记忆 ID）
     * @return 工具执行结果，失败时为 isError=true 的错误文本
     */
    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
        // 步骤 1：把整个工具方法提交到外层池，当前线程只计时，池满则快速失败
        Future<ToolExecutionResult> future;
        try {
            future = pool.submit(() -> delegate.executeWithContext(request, context));
        } catch (RejectedExecutionException e) {
            log.warn("工具执行被拒绝，线程池已满：tool={}", toolName);
            return errorResult("工具「" + toolName + "」当前并发已满，请稍后重试");
        }

        // 步骤 2：在总超时内等待结果
        try {
            return future.get(totalTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 总超时：尝试中断内层正在进行的调用（内层在退避或阻塞时可响应中断）
            future.cancel(true);
            log.warn("工具执行总超时，已中止：tool={}，上限={}s", toolName, totalTimeout.toSeconds());
            return errorResult("工具「" + toolName + "」执行超时（上限 " + totalTimeout.toSeconds() + "s），已中止");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            // 参数错误原样抛出，交给框架的 toolArgumentsErrorHandler（不能当成执行错误吞掉）
            if (cause instanceof ToolArgumentsException toolArgumentsException) {
                throw toolArgumentsException;
            }
            log.warn("工具执行失败：tool={}，原因={}", toolName, cause.toString());
            return errorResult(friendlyMessage(cause));
        } catch (InterruptedException e) {
            // 容器关闭或调用链被中断：恢复中断标记后返回错误文本
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("工具执行被中断：tool={}", toolName);
            return errorResult("工具「" + toolName + "」调用被中断");
        }
    }

    /**
     * 构造面向模型的错误结果：isError 置真，模型据此换工具或向用户说明
     *
     * @param text 错误文本
     * @return 错误结果
     */
    private static ToolExecutionResult errorResult(String text) {
        return ToolExecutionResult.builder()
                .isError(true)
                .resultText(text)
                .build();
    }

    /**
     * 组装可读的失败文案：剥开框架的执行异常包装，内层 ToolInvocationException 保留其
     * 「步骤 + 尝试次数 + 原因」信息，其它异常退回类名与消息
     *
     * @param cause 框架执行异常包装内的原因
     * @return 面向模型的错误文本
     */
    private String friendlyMessage(Throwable cause) {
        Throwable root = cause instanceof ToolExecutionException executionException
                && executionException.getCause() != null
                ? executionException.getCause()
                : cause;
        if (root instanceof ToolInvocationException invocationException) {
            return "工具「" + toolName + "」" + invocationException.getMessage();
        }
        return "工具「" + toolName + "」调用失败：" + brief(root);
    }

    /**
     * 提取异常的一句话描述：优先用消息，无消息时退回类名并截断
     *
     * @param error 异常
     * @return 简短描述
     */
    private static String brief(Throwable error) {
        String message = error.getMessage();
        String text = message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
