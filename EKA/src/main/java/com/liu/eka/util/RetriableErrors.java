package com.liu.eka.util;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 可重试异常判定工具：识别连接/网络/超时这类"换个模型重试可能成功"的异常，
 * 用于兜底模型切换决策；业务性异常（参数错误、上下文超限等）不视为可重试
 *
 * @author Luxon
 * @date 2026/09/09
 */
public final class RetriableErrors {

    /** 禁止实例化，纯静态工具类 */
    private RetriableErrors() {
    }

    /**
     * 判断异常是否为可重试的连接/网络/超时类异常：遍历 cause 链，
     * 命中明确的超时或连接断开类型即判定可重试（含 JDK 网络异常的直接类型匹配）
     *
     * @param error 待判定的异常，可为 null
     * @return 可重试返回 true，其余（含 null）返回 false
     */
    public static boolean isRetriable(Throwable error) {
        // 步骤 1：遍历 cause 链，逐个检查异常类型与消息特征
        Throwable cur = error;
        while (cur != null) {
            // 步骤 1.1：直接类型匹配——JDK 网络异常族与超时异常族
            if (cur instanceof SocketException
                    || cur instanceof SocketTimeoutException
                    || cur instanceof TimeoutException
                    || cur instanceof IOException) {
                return true;
            }
            // 步骤 1.2：消息特征兜底——兼容第三方/框架包装后的错误描述
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("connection reset")
                        || lower.contains("connection refused")
                        || lower.contains("connect timed out")
                        || lower.contains("broken pipe")
                        || lower.contains("timeout")
                        || lower.contains("socket")
                        || lower.contains("aborted connection")
                        || lower.contains("established connection")
                        || lower.contains("servletoutputstream failed to flush")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}