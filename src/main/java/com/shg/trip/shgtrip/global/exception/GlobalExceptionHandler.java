package com.shg.trip.shgtrip.global.exception;

import com.shg.trip.shgtrip.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e,
                                                                     HttpServletResponse response) {
        log.warn("Business exception: {}", e.getMessage());
        resetEventStreamContentType(response);
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.error(e.getErrorCode()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException e) {
        log.warn("ResponseStatusException: {} {}", e.getStatusCode(), e.getReason());
        String reason = e.getReason() != null ? e.getReason() : ErrorCode.RESOURCE_NOT_FOUND.getMessage();
        return ResponseEntity
                .status(e.getStatusCode())
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_FOUND, reason));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        // 필드 에러: "fieldName: message" 형식으로 수집
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        // 클래스 레벨 에러 (예: @ValidDateRange) 추가
        String globalErrors = e.getBindingResult().getGlobalErrors().stream()
                .map(error -> error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        String fullMessage = globalErrors.isBlank() ? message
                : (message.isBlank() ? globalErrors : message + ", " + globalErrors);

        log.warn("Validation failed: {}", fullMessage);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, fullMessage));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e, HttpServletResponse response) {
        log.error("Unexpected error", e);
        resetEventStreamContentType(response);
        return ResponseEntity
                .internalServerError()
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    /**
     * SSE 핸들러는 응답 Content-Type을 text/event-stream으로 미리 정해 둔다. 그대로 두면 JSON 오류 본문을
     * 쓸 컨버터가 없어 HttpMessageNotWritableException이 나고 클라이언트는 응답 없이 매달린다.
     */
    private static void resetEventStreamContentType(HttpServletResponse response) {
        String contentType = response.getContentType();
        if (contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE) && !response.isCommitted()) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
    }
}
