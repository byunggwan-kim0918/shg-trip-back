package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.GenerationTrace;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.user.entity.User;
import com.shg.trip.shgtrip.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class GenerationTraceRepositoryIntegrationTest {

    @MockBean S3Client s3Client;

    @Autowired private GenerationTraceRepository traceRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate tx;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;
    private Long itineraryId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(User.builder().email("trace-it@invalid").nickname(null).build()).getId();
        itineraryId = itineraryRepository.save(Itinerary.builder().userId(userId).title("부산 2박 3일")
                .destination("부산").startDate(LocalDate.of(2026, 12, 1)).endDate(LocalDate.of(2026, 12, 3))
                .build()).getId();
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(s -> {
            traceRepository.deleteByUserId(userId);
            itineraryRepository.deleteAllByUserId(userId);
        });
        userRepository.deleteById(userId);
    }

    @Test
    @DisplayName("새 일정은 품질 상태 OK로 저장되고 판정 입력 JSON을 그대로 읽는다")
    void itineraryQualityColumns() {
        Itinerary loaded = itineraryRepository.findById(itineraryId).orElseThrow();
        assertThat(loaded.getQualityStatus()).isEqualTo(Itinerary.QualityStatus.OK);

        tx.executeWithoutResult(s -> itineraryRepository.findById(itineraryId).orElseThrow()
                .applyQuality(Itinerary.QualityStatus.NEEDS_ADJUSTMENT, java.util.List.of("1일차 안내"),
                        "{\"rules\":{\"dayCount\":3},\"fixedNotices\":[]}"));

        Itinerary updated = itineraryRepository.findById(itineraryId).orElseThrow();
        assertThat(updated.getQualityStatus()).isEqualTo(Itinerary.QualityStatus.NEEDS_ADJUSTMENT);
        assertThat(updated.getPlanContext()).contains("\"dayCount\"");
    }

    @Test
    @DisplayName("탈퇴 삭제는 일정에 연결된 기록과 관문에서 막혀 일정 없는 기록을 모두 지운다")
    void deleteByUserIdRemovesLinkedAndRejected() {
        traceRepository.save(trace(itineraryId));
        traceRepository.save(trace(null));

        int deleted = tx.execute(s -> traceRepository.deleteByUserId(userId));

        assertThat(deleted).isEqualTo(2);
    }

    @Test
    @DisplayName("선택 모델이 본 후보 수를 저장하고, 없는 기록은 null로 읽는다")
    void searchPoolSizeIsOptional() {
        Long withSize = traceRepository.save(GenerationTrace.builder().userId(userId).jobId("job-pool")
                .traceVersion(1).request("{}").enrichedInput("{}").selectionRaw("{}").selectionFinal("{}")
                .candidates("[]").searchPoolSize(48).scheduleArgs("{}").build()).getId();
        Long without = traceRepository.save(trace(null)).getId();

        assertThat(traceRepository.findById(withSize).orElseThrow().getSearchPoolSize()).isEqualTo(48);
        assertThat(traceRepository.findById(without).orElseThrow().getSearchPoolSize()).isNull();
    }

    @Test
    @DisplayName("일정 없는 기록은 여러 건 둘 수 있고, 한 일정의 기록은 하나뿐이다")
    void uniquePerItineraryOnly() {
        traceRepository.save(trace(null));
        traceRepository.save(trace(null));
        traceRepository.saveAndFlush(trace(itineraryId));

        assertThatThrownBy(() -> traceRepository.saveAndFlush(trace(itineraryId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("기간 정리는 오래된 거부 기록만 지우고 일정 기록과 최근 거부 기록은 둔다")
    void deleteFailedBeforeKeepsLinkedAndRecent() {
        Long old = traceRepository.save(trace(null)).getId();
        traceRepository.save(trace(null));
        traceRepository.save(trace(itineraryId));
        jdbc.update("UPDATE generation_traces SET created_at = now() - interval '40 days' WHERE id = ?", old);

        int deleted = tx.execute(s -> traceRepository.deleteFailedBefore(OffsetDateTime.now().minusDays(30)));

        assertThat(deleted).isEqualTo(1);
        assertThat(traceRepository.findById(old)).isEmpty();
        assertThat(traceRepository.findByItineraryId(itineraryId)).isPresent();
    }

    private GenerationTrace trace(Long linkedItinerary) {
        return GenerationTrace.builder().itineraryId(linkedItinerary).userId(userId).jobId("job-it").traceVersion(1)
                .request("{}").enrichedInput("{}").selectionRaw("{}").selectionFinal("{}").candidates("[]")
                .scheduleArgs("{}").build();
    }
}
