package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NameKoOverrideJobTest {

    @Test
    @DisplayName("QID 키와 국가:원문 이름 키를 읽고, 주석·빈 줄은 건너뛴다")
    void parsesBothKeys() {
        var overrides = NameKoOverrideJob.parse(List.of(
                "# 설명", "", "Q270983\t금각사", "JP:金閣寺 \t 금각사 "));

        assertThat(overrides).containsExactly(
                new NameKoOverrideJob.Override("Q270983", null, null, "금각사"),
                new NameKoOverrideJob.Override(null, "JP", "金閣寺", "금각사"));
    }

    @Test
    @DisplayName("형식이 틀린 줄이 있으면 전부 거부한다")
    void rejectsMalformedLines() {
        assertThatThrownBy(() -> NameKoOverrideJob.parse(List.of("Q1\t가", "Q2")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2행");
        assertThatThrownBy(() -> NameKoOverrideJob.parse(List.of("金閣寺\t금각사")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("키");
    }

    @Test
    @DisplayName("저장소의 보정표가 형식에 맞고, 실행하면 항목마다 QID로 적용한다")
    void appliesBundledTable() {
        PlaceRepository repository = mock(PlaceRepository.class);

        new NameKoOverrideJob(repository).run();

        verify(repository).overrideNameKoByQid("Q270983", "금각사");
        verify(repository).overrideNameKoByQid("Q257473", "은각사");
    }
}
