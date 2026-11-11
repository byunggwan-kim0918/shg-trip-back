package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssemblyPromptNameTest {

    private final AssemblyCallGenerator generator = new AssemblyCallGenerator(null,
            new AnthropicProperties("claude-haiku-4-5-20251001", "claude-sonnet-5", 8192));

    @Test
    @DisplayName("스토리 프롬프트의 장소명은 한국어 이름이다")
    void storyUsesKoreanName() {
        StepData step = new StepData(1, 1, "09:00", "10:30",
                new PlaceData("浅草寺", "", "Spiritual Center > Buddhist Temple", "Tokyo", "JP", null, null, 1L, "센소지"),
                List.of(), null, null, null, null, null, BigDecimal.ZERO);
        VectorEnrichedInput input = new VectorEnrichedInput(
                "도쿄", List.of("culture"), List.of("attraction"), "normal", "any", BigDecimal.valueOf(1_000_000),
                LocalDate.of(2027, 5, 10), LocalDate.of(2027, 5, 12), "도쿄 여행", null,
                "도쿄", "JP", List.of("Tokyo"), List.of("문화"), null, "MEDIUM", "봄", "컨텍스트", null, null);

        String message = generator.buildUserMessage(List.of(step), "concept", input);

        assertThat(message).contains("09:00-10:30 센소지 (Tokyo)").doesNotContain("浅草寺");
    }
}
