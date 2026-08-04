package com.shg.trip.shgtrip.domain.planning.dto;

import java.time.OffsetDateTime;

/**
 * 생성 쿼터/차단 상태 (GET /api/itineraries/generation-quota).
 * NewTripShell·ConfirmStep의 "이번 달 생성 가능 N/5회" 배지와 차단 안내에 사용.
 *
 * @param used         30일 창에서 사용한 성공 생성 횟수
 * @param limit        30일 한도(5)
 * @param resetAt      쿼터 창 리셋 시각(첫 생성 + 30일). 사용 이력 없으면 null
 * @param blockedUntil 입력 검증 5회 실패로 차단된 경우 해제 시각. 차단 아니면 null
 */
public record GenerationQuotaResponse(
        int used,
        int limit,
        OffsetDateTime resetAt,
        OffsetDateTime blockedUntil
) {
}
