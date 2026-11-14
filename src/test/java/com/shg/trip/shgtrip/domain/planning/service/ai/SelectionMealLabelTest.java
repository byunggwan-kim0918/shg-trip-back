package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 후보 줄의 음식 종류 라벨은 프롬프트 규칙("해물탕·횟집·회는 한 종류")과 같은 묶음 단위로 읽혀야 한다. */
class SelectionMealLabelTest {

    @Test
    @DisplayName("요리 라벨은 묶음을 앞에 붙이고, 묶음이 없는 요리와 보강 종류는 그대로 둔다")
    void dishLabelsCarryTheirGroup() {
        assertThat(SelectionCallGenerator.roleLabel(meal("청해 조개구이", "SEAFOOD"))).isEqualTo("[식사·해산물(조개)] ");
        assertThat(SelectionCallGenerator.roleLabel(meal("짬뽕지존", "CHINESE"))).isEqualTo("[식사·면(짬뽕·짜장)] ");
        assertThat(SelectionCallGenerator.roleLabel(meal("토속초당순두부", "KOREAN_SET"))).isEqualTo("[식사·국물(순두부)] ");
        assertThat(SelectionCallGenerator.roleLabel(meal("초당만두", "SNACK"))).isEqualTo("[식사·만두·딤섬] ");
        assertThat(SelectionCallGenerator.roleLabel(meal("바다식당", "SEAFOOD"))).isEqualTo("[식사·해산물] ");
    }

    private static PlaceCandidate meal(String name, String cuisine) {
        return new PlaceCandidate(1, 1L, name, name + " 주소", "Dining and Drinking > Restaurant", List.of(),
                "Busan", "KR", BigDecimal.valueOf(35.1), BigDecimal.valueOf(129.0), null, null, 0.5,
                null, null, null, 60, false, null, null, null, null, "MEAL", cuisine, false, null);
    }
}
