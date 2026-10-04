package com.liu.eka.tool.resilience;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具外部调用的内层执行器：在编排层（各 Tool 方法体内）显式包裹每一次外部调用，
 * 提供「单次超时 + 重试 + 该步总时长」三重预算。重试只重跑失败的那一步，不重跑整个工具方法；
 * 超时靠把调用丢进线程池计时判定（当前线程阻塞无法自计时），退避采用指数增长叠加随机抖动。
 * 预算由调用点就地传入（ToolBudget），能否重试由调用方传入的 ToolPolicy（幂等性）决定
 *
 * @author Luxon
 * @date 2026/09/18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolGuard {

    /** 退避基础等待时长：第 1 次失败后等待该时长，随后按倍率指数增长 */
    private static final Duration BACKOFF_INITIAL = Duration.ofMillis(200);

    /** 退避指数增长倍率：第 n 次重试等待 = 基础时长 × 该值^(n-1) */
    private static final double BACKOFF_MULTIPLIER = 2.0;

    /** 单次退避等待的上限，防止指数增长无限放大 */
    private static final Duration BACKOFF_MAX = Duration.ofSeconds(1);

    /** 退避抖动比例：在计算出的等待时长上叠加 ±该比例的随机偏移 */
    private static final double BACKOFF_JITTER = 0.5;

    /** 工具韧性线程池：内层池用于隔离执行单次尝试以便计时与取消 */
    private final ResiliencePools executors;

    /**
     * 在给定预算内执行一次外部调用：做单次超时与重试，成功返回结果，
     * 预算耗尽或遇到不可重试错误时抛 ToolInvocationException 交外层转错误文本
     *
     * @param step   步骤名，仅用于日志与异常定位（如 rag.embedding、sql.execute）
     * @param policy 重试策略：TRANSIENT 按预算重试，NONE 仅做单次超时
     * @param budget 该步骤的预算：单次超时、最大尝试次数、累计耗时上限
     * @param call   真正的外部调用逻辑，由调用方以 lambda 传入
     * @param <T>    返回值类型
     * @return 外部调用的返回值
     * @throws ToolInvocationException 预算耗尽或遇到不可重试错误
     */
    public <T> T run(String step, ToolPolicy policy, ToolBudget budget, Callable<T> call) {
        // 步骤 1：不可重试策略强制单次尝试，并把累计耗时上限换算成截止时刻
        int maxAttempts = policy == ToolPolicy.NONE ? 1 : Math.max(1, budget.maxAttempts());
        long deadline = System.nanoTime() + budget.totalTimeout().toNanos();
        Throwable lastError = null;

        // 步骤 2：逐次尝试，直到成功或预算耗尽
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 步骤 2.1：剩余总时长已耗尽则不再发起本次尝试，直接进入收尾
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            // 单次等待取「单次超时」与「剩余总时长」的较小值，保证不超过该步总预算
            long singleNanos = Math.min(budget.singleTimeout().toNanos(), remaining);
            Future<T> future;
            try {
                future = executors.inner().submit(call);
            } catch (RejectedExecutionException e) {
                // 池已满：属确定性失败，立即返回不再重试
                log.warn("工具调用被拒绝，线程池已满：step={}", step);
                throw new ToolInvocationException(step, attempt - 1, true, "工具并发已满，请稍后重试", e);
            }

            // 步骤 2.2：在单次超时内等待结果
            long startedAt = System.nanoTime();
            try {
                T result = future.get(singleNanos, TimeUnit.NANOSECONDS);
                log.info("工具调用成功：step={}，尝试={}，耗时={}ms", step, attempt, elapsedMillis(startedAt));
                return result;
            } catch (TimeoutException e) {
                // 单次超时：取消本次尝试后按预算决定是否重试
                future.cancel(true);
                lastError = e;
                log.warn("工具调用单次超时：step={}，尝试={}，耗时={}ms", step, attempt, elapsedMillis(startedAt));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                lastError = cause;
                // 确定性失败（参数错误、SQL 语法错、服务拒连等）重试无意义，立即返回
                if (!ToolErrorClassifier.isRetryable(cause)) {
                    log.warn("工具调用遇不可重试错误，立即返回：step={}，尝试={}，原因={}",
                            step, attempt, cause.toString());
                    throw new ToolInvocationException(step, attempt, true, "不可重试错误：" + brief(cause), cause);
                }
                log.warn("工具调用失败，将按预算重试：step={}，尝试={}，原因={}", step, attempt, cause.toString());
            } catch (InterruptedException e) {
                // 被外层总超时或容器关闭中断，恢复中断标记后立即收尾
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new ToolInvocationException(step, attempt, false, "工具调用被中断", e);
            }

            // 步骤 2.3：还有下次尝试时退避等待，等待后仍须落在该步总预算内
            if (attempt >= maxAttempts) {
                break;
            }
            long backoffNanos = nextBackoffNanos(attempt);
            if (System.nanoTime() + backoffNanos >= deadline) {
                break;
            }
            try {
                Thread.sleep(backoffNanos / 1_000_000L, (int) (backoffNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolInvocationException(step, attempt, false, "工具调用被中断", e);
            }
        }

        // 步骤 3：预算耗尽，抛统一异常交外层 ToolTimeoutExecutor 转成错误文本
        throw new ToolInvocationException(step, maxAttempts, false,
                "重试 " + maxAttempts + " 次仍未成功", lastError);
    }

    /**
     * 计算第 n 次重试前的退避时长：以基础时长为起点按倍率指数增长，封顶后再叠加随机抖动；
     * 抖动让并发调用错开重试时刻，避免下游刚恢复就被同时打满
     *
     * @param attempt 已完成的尝试次数（从 1 开始），第 1 次失败后取基础时长
     * @return 退避纳秒数，最小为 0
     */
    private static long nextBackoffNanos(int attempt) {
        // 步骤 1：指数增长并封顶
        double grown = BACKOFF_INITIAL.toNanos() * Math.pow(BACKOFF_MULTIPLIER, attempt - 1);
        double capped = Math.min(grown, BACKOFF_MAX.toNanos());
        // 步骤 2：叠加 ±抖动的随机偏移
        double factor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * BACKOFF_JITTER;
        return Math.max(0, (long) (capped * factor));
    }

    /**
     * 计算自起点的已耗时毫秒数，用于日志记录
     *
     * @param startedAtNanos 起点纳秒时刻（System.nanoTime）
     * @return 已耗时毫秒
     */
    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * 提取异常的一句话描述：优先用消息，无消息时退回类名并截断，供日志与兜底错误文本使用
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
