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
    ITINERARY_FINALIZED("ITINERARY_004", "확정된 일정은 장소를 바꿀 수 없어요.", HttpStatus.CONFLICT),
    ITINERARY_EMPTY("ITINERARY_005", "장소가 없는 일정은 확정할 수 없어요.", HttpStatus.BAD_REQUEST),

    // Place
    PLACE_NOT_FOUND("PLACE_001", "장소를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    PLACE_REGION_MISMATCH("PLACE_002", "선택한 장소가 여행지와 다른 지역입니다.", HttpStatus.BAD_REQUEST),

    // AI
    AI_SERVICE_ERROR("AI_001", "AI 서비스 오류가 발생했습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    AI_SERVICE_TIMEOUT("AI_002", "AI 서비스 응답 시간이 초과되었습니다.", HttpStatus.GATEWAY_TIMEOUT),

    // Planning
    GENERATION_ALREADY_IN_PROGRESS("PLANNING_001", "이미 일정 생성이 진행 중입니다.", HttpStatus.CONFLICT),
    GENERATION_CANCELLED("PLANNING_002", "일정 생성이 취소되었습니다.", HttpStatus.BAD_REQUEST),
    GENERATION_BLOCKED("PLANNING_003", "입력 검증에 반복 실패해 일시적으로 생성이 제한됩니다.", HttpStatus.TOO_MANY_REQUESTS),
    GENERATION_QUOTA_EXCEEDED("PLANNING_004", "30일 생성 한도(5회)를 초과했습니다.", HttpStatus.TOO_MANY_REQUESTS),
    INSUFFICIENT_PLACE_DATA("PLANNING_005", "이 여행지는 아직 장소 정보가 부족해요. 다른 여행지로 시도해 주세요.", HttpStatus.UNPROCESSABLE_ENTITY),
    GENERATION_JOB_NOT_FOUND("PLANNING_006", "생성 작업을 찾을 수 없어요. 만료됐거나 이미 끝난 작업이에요.", HttpStatus.NOT_FOUND),
    PARSE_RATE_LIMITED("PLANNING_007", "문장 분석 요청이 너무 잦아요. 잠시 후 다시 시도해 주세요.", HttpStatus.TOO_MANY_REQUESTS),

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
