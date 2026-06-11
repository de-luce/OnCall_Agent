package com.deluce.oncall.exception;

import com.deluce.oncall.controller.OnCallController;
import com.deluce.oncall.dto.ApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OnCallController.class)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NonTransientAiException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResult<Void> handleAiException(NonTransientAiException ex) {
        log.error("[LLM 调用失败] {}", ex.getMessage(), ex);
        return ApiResult.fail(toFriendlyAiMessage(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[请求异常] {}", ex.getMessage());
        return ApiResult.fail(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("[参数校验失败] {}", message);
        return ApiResult.fail(message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<Void> handleGeneral(Exception ex) {
        log.error("[服务内部错误] {}", ex.getMessage(), ex);
        return ApiResult.fail("服务内部错误: " + ex.getMessage());
    }

    private String toFriendlyAiMessage(String raw) {
        if (raw == null) {
            return "LLM 调用失败";
        }
        if (raw.contains("No models loaded")) {
            return "LM Studio 未加载模型：请打开 LM Studio → 加载模型 → 开启 Local Server（端口 1234）";
        }
        if (raw.contains("Connection refused") || raw.contains("connect")) {
            return "无法连接 LM Studio：请确认 Local Server 已启动（http://127.0.0.1:1234）";
        }
        return "LLM 调用失败: " + raw;
    }
}
