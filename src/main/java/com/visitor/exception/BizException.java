package com.visitor.exception;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final int code;
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(errorCode.getMessage() + "：" + detail);
        this.code = errorCode.getCode();
        this.errorCode = errorCode;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.errorCode = null;
    }
}
