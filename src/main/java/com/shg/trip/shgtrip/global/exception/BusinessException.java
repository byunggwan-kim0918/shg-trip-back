package com.shg.trip.shgtrip.global.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 응답 본문에 직접 싣는 SSE 경로용. 상세 원인은 로그에만 남긴다. */
    public String userMessage() {
        return errorCode.hidesDetail() || getMessage() == null ? errorCode.getMessage() : getMessage();
    }
}
