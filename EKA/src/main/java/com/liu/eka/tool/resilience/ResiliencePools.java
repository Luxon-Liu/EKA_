package com.liu.eka.tool.resilience;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具韧性的两个执行线程池：内层池负责「单次尝试」的隔离执行以便计时超时，
 * 外层池负责「整个工具方法」的总超时隔离。两池必须分开——外层任务会在自身线程内
 * 向池内再提交内层任务并等待，若共用同一个池，池满时外层等内层、内层无线程可用的嵌套死锁。
 * <p>
 * 队列采用 SynchronousQueue（零容量），语义是「要么立刻执行、要么立刻拒绝」：
 * 工具调用排队毫无价值——总超时自提交时刻起算，排到队尾的任务常常还没开始执行就已超时，
 * 日志却会记成「执行超时」，把排查引向错误方向。零容量还让并发上限严格等于配置值，
 * 避免「先填满核心线程、再堆积队列、队列满了才扩容」使最大线程数形同虚设。
 * 池满时 AbortPolicy 立即抛拒绝异常，由调用方转成面向模型的错误文本，不拖垮服务
 * <p>
 * 容量是「单实例」的并发上限（见下方 OUTER_THREADS / INNER_THREADS 常量）。
 * 系统级扩容靠增加实例数，而不是放大单机线程池：线程池越大，下游（ES / MySQL / 模型 API）越先被打死
 *
 * @author Luxon
 * @date 2026/09/18
 */
@Component
public class ResiliencePools {

    /** 外层池线程数：同时执行的工具方法上限，超出即拒绝并把「并发已满」回给模型 */
    private static final int OUTER_THREADS = 16;

    /** 内层池线程数：同时进行的外部调用尝试上限（每次工具方法串行占用 1 个，故大于外层池） */
    private static final int INNER_THREADS = 32;

    /** 内层池：每个并发的外部调用尝试占一个线程，池内任务会被超时取消 */
    private final ThreadPoolExecutor innerPool;

    /** 外层池：每个并发执行的工具方法占一个线程，任务内部还会提交内层任务 */
    private final ThreadPoolExecutor outerPool;

    /**
     * 构建内外两个线程池：内层池容量大于外层池（一次工具方法会串行发起多次内层调用），
     * 线程均为守护线程并允许核心线程空闲回收，避免长期占用资源
     */
    public ResiliencePools() {
        // 内层池：外部调用尝试的隔离执行
        this.innerPool = buildPool("tool-guard-", INNER_THREADS);
        // 外层池：整个工具方法的隔离执行
        this.outerPool = buildPool("tool-outer-", OUTER_THREADS);
    }

    /**
     * 构建一个命名清晰、零容量队列、快速失败且空闲回收的线程池
     *
     * @param prefix  线程名前缀，便于日志与线程转储定位
     * @param threads 线程数，核心与最大取同一值，使并发上限严格可预期
     * @return 线程池实例
     */
    private static ThreadPoolExecutor buildPool(String prefix, int threads) {
        // 步骤 1：线程工厂——顺序编号 + 守护线程，避免应用关闭时阻塞 JVM 退出
        AtomicInteger sequence = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        // 步骤 2：零容量队列 + AbortPolicy：线程都在忙时立即拒绝，不排队、不静默等待
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                threads, threads, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), factory, new ThreadPoolExecutor.AbortPolicy());
        // 步骤 3：允许核心线程空闲回收，低峰期不常驻线程
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * 获取内层线程池：供 ToolGuard 隔离执行单次外部调用尝试
     *
     * @return 内层线程池
     */
    public ExecutorService inner() {
        return innerPool;
    }

    /**
     * 获取外层线程池：供 ToolTimeoutExecutor 隔离执行整个工具方法以计时总超时
     *
     * @return 外层线程池
     */
    public ExecutorService outer() {
        return outerPool;
    }

    /**
     * 容器关闭时停止两个线程池：立即中断在跑任务并清空排队任务，避免残留线程
     */
    @PreDestroy
    public void shutdown() {
        innerPool.shutdownNow();
        outerPool.shutdownNow();
    }
}
