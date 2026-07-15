package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 자연어 문장 파싱 결과.
 * 필드 타입을 {@code ItineraryGenerateRequest}와 1:1로 맞춰 프론트 마법사 프리필 시 무변환으로 사용한다.
 * (단 party는 마법사 필드가 아니라 "이해했어요" 패널 표시 전용이다.)
 *
 * <p>모든 필드는 nullable — 문장에서 확실히 추론되지 않은 값은 null/빈 배열로 둔다.
 * 콘텐츠 실패(횡설수설·여행 무관 문장) 시 {@link #empty()}를 반환해 프론트가 조용히 무시하게 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SentenceParseResponse(
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        String party,
        List<String> themes,
        List<String> categories,
        String pace,
        String transportPref,
        BigDecimal budget
) {
    /** 추론 실패 시 반환하는 전(全) null 응답. */
    public static SentenceParseResponse empty() {
        return new SentenceParseResponse(null, null, null, null, List.of(), List.of(), null, null, null);
    }
}
