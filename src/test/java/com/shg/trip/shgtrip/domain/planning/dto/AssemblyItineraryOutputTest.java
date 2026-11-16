package com.shg.trip.shgtrip.domain.planning.dto;

import com.anthropic.core.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class AssemblyItineraryOutputTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("번호가 붙은 키나 문자열 번호로 온 스텝도 살리고, 번호나 이야기가 없는 스텝만 뺀다")
    void keepsStepsWithNumberedKeys() throws Exception {
        AssemblyItineraryOutput out = AssemblyItineraryOutput.fromToolInput(mapper.readTree("""
                {"title": "서울 궁궐 여행", "tags": ["궁궐", 3],
                 "steps": [
                   {"stepOrder": 1, "story": "경복궁에서 시작해요."},
                   {"stepOrder 4": "", "story": "점심은 국밥이에요."},
                   {"stepOrder": "6", "story": "저녁은 회예요."},
                   {"stepOrder 123456789012": "", "story": "번호가 너무 길어요."},
                   {"story": "번호가 없어요."},
                   {"stepOrder": 5, "story": "  "}
                 ]}
                """));

        assertThat(out.title()).isEqualTo("서울 궁궐 여행");
        assertThat(out.tags()).containsExactly("궁궐");
        assertThat(out.steps()).extracting(AssemblyItineraryOutput.StoryStep::stepOrder,
                AssemblyItineraryOutput.StoryStep::story)
                .containsExactly(tuple(1, "경복궁에서 시작해요."), tuple(4, "점심은 국밥이에요."), tuple(6, "저녁은 회예요."));
    }

    @Test
    @DisplayName("필드가 빠진 응답은 빈 값으로 읽는다")
    void readsMissingFieldsAsEmpty() throws Exception {
        AssemblyItineraryOutput out = AssemblyItineraryOutput.fromToolInput(mapper.readTree("{}"));

        assertThat(out.title()).isNull();
        assertThat(out.tags()).isEmpty();
        assertThat(out.steps()).isEmpty();
    }

    @Test
    @DisplayName("SDK 도구 입력을 JsonNode로 바꿔 읽는다")
    void readsSdkToolInput() {
        JsonValue input = JsonValue.from(Map.of("title", "부산", "steps",
                List.of(Map.of("stepOrder 2", "x", "story", "해운대를 걸어요."))));

        AssemblyItineraryOutput out = AssemblyItineraryOutput.fromToolInput(input.convert(JsonNode.class));

        assertThat(out.steps()).extracting(AssemblyItineraryOutput.StoryStep::stepOrder).containsExactly(2);
    }
}
