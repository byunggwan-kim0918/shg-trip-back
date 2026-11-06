package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 필드 타입을 {@code ItineraryGenerateRequest}와 1:1로 맞춰 프론트 마법사 프리필에 무변환으로 쓴다.
 * party는 패널 표시용 원문이고 마법사에는 companion·partySize가 들어간다.
 *
 * <p>모든 필드는 nullable이며, 확실히 추론되지 않은 값은 null/빈 배열로 둔다.
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
        BigDecimal budget,
        String companion,
        Integer partySize,
        String arrivalTime,
        String departureTime
) {
    /** 콘텐츠 실패(횡설수설·여행 무관 문장) 시 프론트가 조용히 무시하도록 반환하는 전(全) null 응답. */
    public static SentenceParseResponse empty() {
        return new SentenceParseResponse(null, null, null, null, List.of(), List.of(), null, null, null,
                null, null, null, null);
    }
}
