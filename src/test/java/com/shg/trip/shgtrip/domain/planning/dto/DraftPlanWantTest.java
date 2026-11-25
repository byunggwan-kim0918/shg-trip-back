package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DraftPlanWantTest {

    @Test
    @DisplayName("초안의 food_aliases를 읽고, 빈 값·음식 이름과 같은 값·중복·너무 긴 값은 버리고 개수를 자른다")
    void foodAliasesAreReadAndBounded() throws Exception {
        String json = """
                {"id":"w1","kind":"food","text":"멘타이코 먹기","food":"멘타이코","satisfy":[],
                 "food_aliases":["明太子"," 明太子 ","","멘타이코","明太","めんたいこ","명란","明太子料理の専門店で食べられる博多名物",
                 "からし明太子"]}""";

        DraftPlan.Want want = new ObjectMapper().readValue(json, DraftPlan.Want.class);

        assertThat(want.foodAliases()).containsExactly("明太子", "明太", "めんたいこ", "명란");
    }

    @Test
    @DisplayName("음식 이름에 들어 있는 더 넓은 말과 비교 키가 같은 표기·한 글자 표기는 버린다")
    void broaderOrDuplicateKeyAliasesAreDropped() {
        DraftPlan.Want want = new DraftPlan.Want("w1", "food", "돈코츠 라멘", null, "돈코츠 라멘", null, List.of(), List.of(),
                List.of("라멘", "돈코츠", "豚骨ラーメン", "豚骨 ラーメン", "麺", "Tonkotsu Ramen", "tonkotsu ramen"));

        assertThat(want.foodAliases()).containsExactly("豚骨ラーメン", "Tonkotsu Ramen");
        assertThat(new DraftPlan.Want("w1", "food", "돈코츠 라멘 먹기", null, null, null, List.of(), List.of(),
                List.of("라멘", "豚骨ラーメン")).foodAliases()).containsExactly("豚骨ラーメン");
    }

    @Test
    @DisplayName("모델이 food_aliases를 배열 대신 문자열 하나로 내도 초안을 버리지 않는다")
    void singleStringFoodAliasIsAccepted() throws Exception {
        DraftPlan.Want want = new ObjectMapper().readValue(
                "{\"id\":\"w1\",\"kind\":\"food\",\"text\":\"멘타이코\",\"food\":\"멘타이코\",\"satisfy\":[],"
                        + "\"food_aliases\":\"明太子\"}", DraftPlan.Want.class);

        assertThat(want.foodAliases()).containsExactly("明太子");
    }

    @Test
    @DisplayName("food_aliases가 없는 기록 초안은 빈 목록으로 읽는다")
    void missingFoodAliasesIsEmpty() throws Exception {
        DraftPlan.Want want = new ObjectMapper().readValue(
                "{\"id\":\"w1\",\"kind\":\"food\",\"text\":\"피순대\",\"food\":\"피순대\",\"satisfy\":[]}",
                DraftPlan.Want.class);

        assertThat(want.foodAliases()).isEmpty();
        assertThat(new DraftPlan.Want("w1", "food", "피순대", null, "피순대", null, List.of(), List.of(),
                Arrays.asList(null, " ")).foodAliases()).isEmpty();
    }
}
