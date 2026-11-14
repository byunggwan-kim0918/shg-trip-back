package com.shg.trip.shgtrip.domain.planning.dto;

import java.time.OffsetDateTime;

/**
 * @param used         30일 창에서 사용한 성공 생성 횟수
 * @param limit        30일 한도(5)
 * @param resetAt      쿼터 창 리셋 시각(첫 생성 + 30일). 사용 이력 없으면 null
 * @param blockedUntil 입력 검증 5회 실패로 차단된 경우 해제 시각. 차단 아니면 null
 * @param unbilledUsed    24시간 창에서 쿼터를 쓰지 않고 끝난 생성 수(조정 필요 저장, 판정 거부)
 * @param unbilledLimit   24시간 미차감 한도(3). 닿으면 창이 끝날 때까지 생성을 막는다
 * @param unbilledResetAt 미차감 창 리셋 시각. 사용 이력 없으면 null
 */
public record GenerationQuotaResponse(
        int used,
        int limit,
        OffsetDateTime resetAt,
        OffsetDateTime blockedUntil,
        int unbilledUsed,
        int unbilledLimit,
        OffsetDateTime unbilledResetAt
) {
}
