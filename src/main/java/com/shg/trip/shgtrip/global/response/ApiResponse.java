package com.shg.trip.shgtrip.global.response;

import com.shg.trip.shgtrip.global.exception.ErrorCode;

public record ApiResponse<T>(boolean success, T data, ErrorInfo error) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(false, null, new ErrorInfo(errorCode));
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, new ErrorInfo(errorCode.getCode(), message));
    }
}
