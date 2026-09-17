package com.yanglizi.docraptor.common;

import lombok.Data;

/**
 * 统一响应包装 {code, message, data}，见 docs/03-api-contract.md 1.1。
 * 成功固定 message="success"；失败 message 可直接展示给用户。
 */
@Data
public class R<T> {

    private int code;
    private String message;
    private T data;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = ErrorCode.SUCCESS.code();
        r.message = ErrorCode.SUCCESS.message();
        r.data = data;
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message, T data) {
        R<T> r = new R<>();
        r.code = errorCode.code();
        r.message = message;
        r.data = data;
        return r;
    }

    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return fail(errorCode, message, null);
    }
}
