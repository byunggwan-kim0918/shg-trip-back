package com.shg.trip.shgtrip.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Auth
    INVALID_PROVIDER("AUTH_001", "지원하지 않는 소셜 로그인입니다.", HttpStatus.BAD_REQUEST),
    OAUTH_AUTHENTICATION_FAILED("AUTH_002", "소셜 인증에 실패했습니다.", HttpStatus.UNAUTHORIZED),
    INVALID_TOKEN("AUTH_003", "유효하지 않은 토큰입니다.", HttpStatus.UNAUTHORIZED),
    EXPIRED_TOKEN("AUTH_004", "만료된 토큰입니다.", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_NOT_FOUND("AUTH_005", "리프레시 토큰을 찾을 수 없습니다.", HttpStatus.UNAUTHORIZED),
    EMAIL_NOT_PROVIDED("AUTH_006", "이메일 정보를 제공받지 못했습니다.", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_REUSE_DETECTED("AUTH_007", "비정상적인 토큰 사용이 감지되어 전체 세션이 종료되었습니다.", HttpStatus.UNAUTHORIZED),

    // User
    USER_NOT_FOUND("USER_001", "사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    DUPLICATE_NICKNAME("USER_002", "이미 사용 중인 닉네임입니다.", HttpStatus.CONFLICT),

    // Itinerary
    ITINERARY_NOT_FOUND("ITINERARY_001", "일정을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    ITINERARY_ACCESS_DENIED("ITINERARY_002", "일정에 접근할 권한이 없습니다.", HttpStatus.FORBIDDEN),
    ITINERARY_VERSION_CONFLICT("ITINERARY_003", "일정이 다른 곳에서 수정되었습니다. 다시 시도해주세요.", HttpStatus.CONFLICT),

    // Place
    PLACE_NOT_FOUND("PLACE_001", "장소를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),

    // Wishlist
    WISHLIST_ALREADY_EXISTS("WISHLIST_001", "이미 찜한 장소입니다.", HttpStatus.CONFLICT),
    WISHLIST_NOT_FOUND("WISHLIST_002", "찜 목록에 없는 장소입니다.", HttpStatus.NOT_FOUND),

    // AI
    AI_SERVICE_ERROR("AI_001", "AI 서비스 오류가 발생했습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVICE_TIMEOUT("AI_002", "AI 서비스 응답 시간이 초과되었습니다.", HttpStatus.GATEWAY_TIMEOUT),

    // External
    EXTERNAL_API_ERROR("EXTERNAL_001", "외부 API 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY),

    // Validation
    VALIDATION_FAILED("VALIDATION_001", "입력 데이터 검증에 실패했습니다.", HttpStatus.UNPROCESSABLE_ENTITY),

    // Common
    RESOURCE_NOT_FOUND("COMMON_002", "요청한 리소스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    INVALID_INPUT("COMMON_001", "잘못된 입력입니다.", HttpStatus.BAD_REQUEST),
    INTERNAL_SERVER_ERROR("COMMON_999", "서버 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
