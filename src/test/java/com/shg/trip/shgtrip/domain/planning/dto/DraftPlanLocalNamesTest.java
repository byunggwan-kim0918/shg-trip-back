package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DraftPlanLocalNamesTest {

    @Test
    @DisplayName("현지어 표기는 비교 키가 같은 것을 하나만 남기고, 한국어 이름과 같은 키·빈 값·긴 설명은 뺀 뒤 세 개까지 둔다")
    void cleansLocalNames() {
        List<String> out = DraftPlan.cleanLocalNames("Kiyomizu-dera", Arrays.asList(
                " 清水寺 ", "清水寺", "kiyomizudera", "", null, "音羽山清水寺", "きよみずでら",
                "京都の東山にある有名な寺で舞台からの眺めが良いことで知られている場所です", "清水の舞台"));
        assertThat(out).containsExactly("清水寺", "音羽山清水寺", "きよみずでら");
    }

    @Test
    @DisplayName("초안 JSON의 local_names는 문자열 하나로 와도 읽고, 없으면 빈 목록이다")
    void readsLocalNamesFromDraftJson() throws Exception {
        DraftPlan plan = new ObjectMapper().readValue("""
                {"concept": "c", "wants": [{"id": "w1", "kind": "place", "text": "히가시차야가이", "place": "히가시차야가이",
                  "satisfy": [], "local_names": "ひがし茶屋街"}],
                 "days": [{"day": 1, "area": "가나자와", "items": [
                   {"slot": "morning", "kind": "sight", "name": "겐로쿠엔", "area": "가나자와", "place_named": true,
                    "local_names": ["兼六園"]},
                   {"slot": "lunch", "kind": "meal", "name": "해산물 덮밥", "area": "가나자와", "place_named": false}],
                   "spares": []}]}""", DraftPlan.class);
        assertThat(plan.wants().get(0).localNames()).containsExactly("ひがし茶屋街");
        assertThat(plan.days().get(0).items().get(0).localNames()).containsExactly("兼六園");
        assertThat(plan.days().get(0).items().get(1).localNames()).isEmpty();
    }
}
