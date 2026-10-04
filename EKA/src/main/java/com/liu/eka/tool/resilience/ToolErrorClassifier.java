package com.liu.eka.tool.resilience;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 工具外部调用的错误分类器：判定某个异常是否属于「瞬时性、换个时机重试可能成功」的错误。
 * 与模型兜底用的 {@code RetriableErrors} 分开：那一套把所有 IOException（含文件不存在）都视为可重试，
 * 适合「换个模型再试」，但不适合工具重试——文件不存在、SQL 语法错误重试多少次都一样，必须立即返回。
 * 判定顺序：先判致命错误（确定性失败），命中即不可重试；否则再判瞬时错误
 *
 * @author Luxon
 * @date 2026/09/18
 */
public final class ToolErrorClassifier {

    /** 禁止实例化，纯静态工具类 */
    private ToolErrorClassifier() {
    }

    /**
     * 判断异常是否可重试：沿 cause 链先找致命特征，再找瞬时特征；
     * 致命错误优先级最高（即使外层是超时文案，只要链条里有确定性失败就判不可重试）
     *
     * @param error 待判定的异常，可为 null
     * @return 可重试返回 true；不可重试、特征不明或 null 一律返回 false（保守失败即返回）
     */
    public static boolean isRetryable(Throwable error) {
        // 步骤 1：空值不可重试
        if (error == null) {
            return false;
        }
        // 步骤 2：先扫致命特征，命中即不可重试
        if (containsFatal(error)) {
            return false;
        }
        // 步骤 3：再扫瞬时特征，命中才可重试
        return containsTransient(error);
    }

    /**
     * 沿 cause 链查找致命（确定性失败）特征：参数错误、文件不存在、服务拒连、
     * HTTP 4xx、SQL 语法/未知表、认证失败等，这些重试多少次结果都一样
     *
     * @param error 异常链起点
     * @return 命中任一特征返回 true
     */
    private static boolean containsFatal(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            // 参数校验类：模型传错参数或代码断言失败，重试无意义
            if (cur instanceof IllegalArgumentException
                    || cur instanceof NoSuchFileException
                    || cur instanceof FileNotFoundException
                    || cur instanceof AccessDeniedException) {
                return true;
            }
            // 服务拒连（服务没起来/已挂掉）：重试只会白等
            if (cur instanceof ConnectException) {
                return true;
            }
            String msg = messageOf(cur);
            if (!msg.isEmpty()) {
                // 服务拒连的文本兜底（部分客户端把 ConnectException 包成别的类型）
                if (msg.contains("connection refused")) {
                    return true;
                }
                // HTTP 4xx：请求本身有问题（认证、权限、不存在、参数不合法），重试不变
                if (msg.contains("http 400") || msg.contains("http 401") || msg.contains("http 403")
                        || msg.contains("http 404") || msg.contains("http 405") || msg.contains("http 422")) {
                    return true;
                }
                // 认证/鉴权失败
                if (msg.contains("invalid api key") || msg.contains("invalid_api_key")
                        || msg.contains("unauthorized") || msg.contains("authentication")) {
                    return true;
                }
                // SQL 语法错误、未知表/字段、表不存在：模型写的 SQL 有问题，重试不会变对
                if (msg.contains("sqlsyntaxerrorexception") || msg.contains("you have an error in your sql syntax")
                        || msg.contains("unknown column") || msg.contains("unknown table")
                        || msg.contains("doesn't exist") || msg.contains("does not exist")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 沿 cause 链查找瞬时特征：各类超时、连接被重置/中断、HTTP 5xx/429、
     * ES 节点或分片暂不可用、数据库连接池取连超时与死锁回滚
     *
     * @param error 异常链起点
     * @return 命中任一特征返回 true
     */
    private static boolean containsTransient(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            // 超时族：socket 读超时、HTTP 请求超时、JDBC 语句超时、并发等待超时
            if (cur instanceof SocketTimeoutException
                    || cur instanceof HttpTimeoutException
                    || cur instanceof TimeoutException
                    || cur instanceof SQLTimeoutException) {
                return true;
            }
            // JDBC 瞬时异常族（连接中断、事务回滚重试等）
            if (cur instanceof SQLTransientException) {
                return true;
            }
            // 连接被对端关闭导致的流提前结束
            if (cur instanceof EOFException) {
                return true;
            }
            // JDBC 连接类 SQLState（08 开头）
            if (cur instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) {
                return true;
            }
            String msg = messageOf(cur);
            if (!msg.isEmpty()) {
                // 连接被重置/中断/提前断开
                if (msg.contains("connection reset") || msg.contains("broken pipe")
                        || msg.contains("unexpected end of stream") || msg.contains("premature end")
                        || msg.contains("connection abort") || msg.contains("connect timed out")) {
                    return true;
                }
                // 超时文本兜底（部分客户端不回传明确的超时类型）
                if (msg.contains("timed out") || msg.contains("timeout")) {
                    return true;
                }
                // HTTP 5xx 与限流 429：服务端暂时性问题
                if (msg.contains("http 500") || msg.contains("http 502") || msg.contains("http 503")
                        || msg.contains("http 504") || msg.contains("http 429")) {
                    return true;
                }
                // ES 节点/分片暂不可用
                if (msg.contains("unavailable_shards_exception") || msg.contains("cluster_block_exception")
                        || msg.contains("nonodeavailable") || msg.contains("node not connected")
                        || msg.contains("connect_exception")) {
                    return true;
                }
                // 数据库连接池取连超时、死锁与锁等待回滚、连接中断
                if (msg.contains("deadlock found") || msg.contains("lock wait timeout")
                        || msg.contains("communications link failure") || msg.contains("could not get jdbc connection")
                        || msg.contains("connection is not available")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 取异常消息并统一小写，便于做关键字匹配；空消息返回空串
     *
     * @param error 异常
     * @return 小写消息，无消息时为空串
     */
    private static String messageOf(Throwable error) {
        String msg = error.getMessage();
        return msg == null ? "" : msg.toLowerCase(Locale.ROOT);
    }
}
