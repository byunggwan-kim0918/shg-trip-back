package com.shg.trip.shgtrip.global.response;

import com.shg.trip.shgtrip.global.exception.ErrorCode;

public record ErrorInfo(String code, String message) {

    public ErrorInfo(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMessage());
    }
}
