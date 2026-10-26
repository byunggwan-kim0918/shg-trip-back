package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sonnet 후보 줄에 인기도가 실제로 실리는지 못박는 테스트.
 *
 * <p>인기도가 프롬프트에 없으면 모델은 평점만 보고 판단하고, 평점을 매긴 사람이 몇 없는 ⭐4.6이
 * 대표 명소 ⭐4.3보다 좋아 보인다(실측 82·84번에서 인기 명소가 전부 spareIndices로 갔다).
 * DTO·매퍼 어디 한 곳이라도 신호를 흘리면 조용히 이 상태로 돌아가므로 경로를 고정한다.
 *
 * <p>신호는 Google 리뷰 수에서 Wikidata sitelinks로 바뀌었다 — Maps 약관 3.2.3(a)가 평점·리뷰의
 * 로컬 캐싱을 금지해 전량 삭제했기 때문이다. 숫자 대신 등급 문구를 주는 이유는 모델이
 * "sitelinks 16"의 척도를 모르기 때문이다.
 */
class SelectionPromptFameTest {

    private final SelectionCallGenerator generator =
            new SelectionCallGenerator(null,
                    new AnthropicProperties("claude-haiku-4-5-20251001", "claude-sonnet-5", 8192));

    private PlaceCandidate candidate(int index, String name, double rating, Integer sitelinks) {
        return new PlaceCandidate(
                index, (long) index, name, name + " 주소",
                "Landmarks and Outdoors > Tourist Attraction", List.of(),
                "Jeju", "KR", BigDecimal.valueOf(33.4), BigDecimal.valueOf(126.5),
                name + " 설명", BigDecimal.valueOf(rating), 0.5,
                null, null, null, null, false, null, "제주시", null, sitelinks);
    }

    private VectorEnrichedInput input() {
        return new VectorEnrichedInput(
                "제주", List.of("nature"), List.of("attraction"),
                "normal", "car", BigDecimal.valueOf(1_000_000),
                LocalDate.of(2027, 5, 10), LocalDate.of(2027, 5, 13),
                "제주 여행", null,
                "제주", "KR", List.of("Jeju"), List.of("자연"), null,
                "MEDIUM", "봄", "컨텍스트", null, null);
    }

    @Test
    @DisplayName("후보 줄에 인기도가 등급으로 실린다")
    void candidateLineCarriesFameTier() {
        String message = generator.buildUserMessage(input(), List.of(
                candidate(1, "한라산", 4.3, 46),
                candidate(2, "만장굴", 4.4, 11),
                candidate(3, "바늘오름", 4.6, 0),
                candidate(4, "미매칭장소", 4.5, null)
        ), 4);

        assertThat(message).contains("한라산 | Tourist Attraction | ⭐4.3 · 인기도 매우 높음");
        assertThat(message).contains("만장굴 | Tourist Attraction | ⭐4.4 · 인기도 매우 높음");
        // 0 = 조회했는데 Wikidata에 항목이 없음 → 무명임이 확인된 것
        assertThat(message).contains("바늘오름 | Tourist Attraction | ⭐4.6 · 인기도 낮음");
        // null = 아직 매칭 안 함 → 등급을 붙이지 않는다(0으로 오해하게 만들지 않는다)
        assertThat(message).contains("미매칭장소 | Tourist Attraction | ⭐4.5 |");
        assertThat(message).contains("평점 · 인기도");
    }
}
