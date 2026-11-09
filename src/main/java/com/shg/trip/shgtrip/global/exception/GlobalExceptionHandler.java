package com.shg.trip.shgtrip.global.exception;

import com.shg.trip.shgtrip.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
                .body(ApiResponse.error(e.getErrorCode(), e.userMessage()));
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
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        // @ValidDateRange 같은 클래스 레벨 에러는 필드 에러에 없다.
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

    /**
     * 깨진 JSON·빈 본문·없는 enum 값·빠진 파라미터나 쿠키·없는 정렬 필드처럼
     * 클라이언트가 고쳐야 하는 요청이 500으로 기록되지 않게 한다.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class, PropertyReferenceException.class})
    public ResponseEntity<ApiResponse<Void>> handleUnreadableRequest(Exception e) {
        log.warn("Bad request: {}", e.getMessage());
        return error(ErrorCode.INVALID_INPUT);
    }

    /** 낙관락 충돌과 교착·잠금 대기 실패(PostgreSQL deadlock_detected)는 다시 시도하면 되는 요청이다. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleConcurrencyFailure(ConcurrencyFailureException e) {
        log.warn("Concurrent update conflict: {}", e.getMessage());
        return error(ErrorCode.ITINERARY_VERSION_CONFLICT);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not allowed: {}", e.getMessage());
        return error(ErrorCode.METHOD_NOT_ALLOWED, e.getHeaders());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("Unsupported media type: {}", e.getMessage());
        return error(ErrorCode.UNSUPPORTED_MEDIA_TYPE, e.getHeaders());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return error(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static ResponseEntity<ApiResponse<Void>> error(ErrorCode code) {
        return error(code, HttpHeaders.EMPTY);
    }

    /** 405의 Allow, 415의 Accept처럼 RFC가 요구하는 헤더를 그대로 싣는다. */
    private static ResponseEntity<ApiResponse<Void>> error(ErrorCode code, HttpHeaders headers) {
        return ResponseEntity.status(code.getHttpStatus()).headers(headers).body(ApiResponse.error(code));
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
