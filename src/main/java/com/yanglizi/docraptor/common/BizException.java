package com.yanglizi.docraptor.common;

import lombok.Getter;

/**
 * 业务异常：携带错误码，可附带结构化 data（如 40902 的 conflictTaskId）。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object payload;

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object payload) {
        super(message);
        this.errorCode = errorCode;
        this.payload = payload;
    }

    /** 按错误码模板渲染 message（{@code {} } 占位按参数顺序替换）。 */
    public static BizException of(ErrorCode errorCode, Object... messageArgs) {
        return new BizException(errorCode, errorCode.render(messageArgs));
    }

    /** 带结构化 data 的错误（如 40902 的 conflictTaskId）。 */
    public static BizException withPayload(ErrorCode errorCode, Object payload, Object... messageArgs) {
        return new BizException(errorCode, errorCode.render(messageArgs), payload);
    }
}
