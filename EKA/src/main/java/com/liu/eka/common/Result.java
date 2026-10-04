package com.liu.eka.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一接口返回体：所有 controller 一律返回本类，前端只认这一个结构；
 * code 全局写死两个值——200 成功、500 失败，不再区分 400/401/404，
 * 具体原因一律看 message 中文文案。
 * 成功：ok() / ok(data) / ok(message, data)；失败：fail() / fail(message) / fail(message, data)。
 * 注意：ok(String) 单参形态与 ok(T) 泛型擦除冲突，故 message 单参请写成 ok(msg, null)
 *
 * @param <T> 业务数据载荷类型
 * @author Luxon
 * @date 2026/09/04
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 成功码，写死 */
    private static final int SUCCESS_CODE = 200;

    /** 失败码，写死 */
    private static final int FAIL_CODE = 500;

    /** 成功默认文案 */
    private static final String SUCCESS_MESSAGE = "请求成功";

    /** 失败默认文案 */
    private static final String FAIL_MESSAGE = "请求失败";

    /** 状态码：200 成功，500 失败 */
    private int code;

    /** 提示信息：成功时默认为「请求成功」，失败时为具体中文错误原因 */
    private String message;

    /** 业务数据载荷，失败时一般为 null */
    private T data;

    /**
     * 成功返回：无数据，message 为默认文案
     *
     * @param <T> 业务数据载荷类型
     * @return code 为 200 的成功返回体
     */
    public static <T> Result<T> ok() {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    /**
     * 成功返回：带业务数据，message 为默认文案
     *
     * @param data 业务数据，可为 null
     * @param <T>  业务数据载荷类型
     * @return code 为 200 的成功返回体
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    /**
     * 成功返回：自定义提示文案 + 业务数据，message 为空时回落默认文案
     *
     * @param message 自定义成功文案，为空时用默认文案
     * @param data    业务数据，可为 null（message 单参形态请调 ok(msg, null)）
     * @param <T>     业务数据载荷类型
     * @return code 为 200 的成功返回体
     */
    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(SUCCESS_CODE, defaultIfBlank(message, SUCCESS_MESSAGE), data);
    }

    /**
     * 失败返回：message 为默认文案
     *
     * @param <T> 业务数据载荷类型
     * @return code 为 500 的失败返回体
     */
    public static <T> Result<T> fail() {
        return new Result<>(FAIL_CODE, FAIL_MESSAGE, null);
    }

    /**
     * 失败返回：自定义中文错误原因，为空时回落默认文案
     *
     * @param message 中文错误原因，为空时用默认文案
     * @param <T>     业务数据载荷类型
     * @return code 为 500 的失败返回体
     */
    public static <T> Result<T> fail(String message) {
        return new Result<>(FAIL_CODE, defaultIfBlank(message, FAIL_MESSAGE), null);
    }

    /**
     * 失败返回：自定义中文错误原因 + 业务数据，message 为空时回落默认文案
     *
     * @param message 中文错误原因，为空时用默认文案
     * @param data    业务数据，一般为 null
     * @param <T>     业务数据载荷类型
     * @return code 为 500 的失败返回体
     */
    public static <T> Result<T> fail(String message, T data) {
        return new Result<>(FAIL_CODE, defaultIfBlank(message, FAIL_MESSAGE), data);
    }

    /**
     * 文案为空（null/空白串）时回落默认文案
     *
     * @param value        调用方传入的文案，可为 null
     * @param defaultValue 默认文案
     * @return 非空文案
     */
    private static String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
