package com.yanglizi.docraptor.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.stream.Collectors;

/**
 * 全局异常兜底：所有异常都转成统一包装体，HTTP 状态码默认 200（契约 1.1）。
 * 唯一例外：multipart 体积超限返回 HTTP 413 + code=41301（契约 1.1 的约定例外）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Object> handleBiz(BizException e) {
        log.warn("业务异常 code={} message={}", e.getErrorCode().code(), e.getMessage());
        if (e.getPayload() != null) {
            return R.fail(e.getErrorCode(), e.getMessage(), e.getPayload());
        }
        return R.fail(e.getErrorCode(), e.getMessage());
    }

    /** 契约 1.1 的约定例外：容器在进入 Controller 前拦截，返回 HTTP 413。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Object>> handleTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传超过限制: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(R.fail(ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE.message()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Object> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) {
            detail = e.getMessage();
        }
        return R.fail(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.render(detail));
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public R<Object> handleBadRequest(Exception e, HttpServletRequest request) {
        String detail = e instanceof MissingServletRequestParameterException m
                ? "缺少必填参数 " + m.getParameterName()
                : e.getMessage();
        log.warn("请求参数异常 {} : {}", request.getRequestURI(), detail);
        return R.fail(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.render(detail == null ? "参数格式错误" : detail));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<Object> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return R.fail(ErrorCode.PARAM_INVALID, ErrorCode.PARAM_INVALID.render(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public R<Object> handleOther(Exception e, HttpServletRequest request) {
        log.error("未捕获异常 {} {}", request.getMethod(), request.getRequestURI(), e);
        return R.fail(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message());
    }
}
