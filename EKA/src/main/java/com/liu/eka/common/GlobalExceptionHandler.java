package com.liu.eka.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常兜底：任何未处理的异常到这里统一翻译成 Result 失败返回体，
 * code 写死 500，具体原因看 message 中文文案，前端无需分支处理
 *
 * @author Luxon
 * @date 2026/09/04
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 统一兜底：参数校验失败取首个字段错误，其余异常直接用自带信息，
     * message 为空时 Result 自动回落默认失败文案
     *
     * @param e 未处理的异常
     * @return code 为 500 的失败返回体
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        // 参数校验异常自带信息是英文长串没法看，单独取首个字段错误拼可读提示
        if (e instanceof MethodArgumentNotValidException validation) {
            String message = validation.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(error -> error.getField() + error.getDefaultMessage())
                    .orElse(null);
            log.warn("请求参数校验失败：{}", message);
            return Result.fail(message);
        }
        // 上传文件超限单独翻译：Spring 自带信息是英文长串，直接回中文限制说明
        if (e instanceof MaxUploadSizeExceededException) {
            log.warn("上传文件超限：{}", e.getMessage());
            return Result.fail("文件过大，单个文件不超过 20MB、单次不超过 50MB");
        }
        // 其余异常记 error 日志后把自带中文原因回给前端
        log.error("服务端异常：{}", e.getMessage(), e);
        return Result.fail(e.getMessage());
    }
}
