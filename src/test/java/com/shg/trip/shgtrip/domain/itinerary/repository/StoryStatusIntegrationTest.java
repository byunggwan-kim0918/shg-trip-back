package com.shg.trip.shgtrip.domain.itinerary.repository;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary.StoryStatus;
import com.shg.trip.shgtrip.domain.planning.dto.AssemblyItineraryOutput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.service.StorySaveHelper;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class StoryStatusIntegrationTest {

    @MockBean S3Client s3Client;

    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StorySaveHelper storySaveHelper;
    @Autowired private TransactionTemplate tx;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(User.builder().email("story-it@invalid").nickname(null).build()).getId();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM generation_traces WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM itinerary_steps WHERE itinerary_id IN (SELECT id FROM itineraries WHERE user_id = ?)",
                userId);
        jdbc.update("DELETE FROM places WHERE name LIKE 'story-it-%'");
        tx.executeWithoutResult(s -> itineraryRepository.deleteAllByUserId(userId));
        userRepository.deleteById(userId);
    }

    @Test
    @DisplayName("새로 저장한 일정은 PENDING이다")
    void newItineraryIsPending() {
        assertThat(status(itinerary("나고야 3박 4일", 4))).isEqualTo(StoryStatus.PENDING);
    }

    @Test
    @DisplayName("이야기 저장은 메모·제목·DONE을 함께 커밋하고 @Version은 그대로 둔다")
    void saveStoryCommitsDoneWithoutVersionBump() {
        Long id = itinerary("나고야 3박 4일", 4);
        Long stepId = step(id, 1, 1, "나고야성의 혼마루 어전을 보러 간다");
        Integer versionBefore = version(id);

        storySaveHelper.saveStory(id, List.of(stepData(1, 1, placeName(stepId))), story(1, "이야기"), "나고야 3박 4일");

        assertThat(status(id)).isEqualTo(StoryStatus.DONE);
        assertThat(notes(stepId)).isEqualTo("이야기");
        assertThat(title(id)).isEqualTo("나고야 장어 산책");
        assertThat(version(id)).isEqualTo(versionBefore);
    }

    @Test
    @DisplayName("저장 트랜잭션이 롤백되면 DONE도 남지 않고, 실패 기록은 롤백과 별개로 커밋된다")
    void rollbackLeavesPendingThenMarkFailedCommits() {
        Long id = itinerary("나고야 3박 4일", 4);
        Long stepId = step(id, 1, 1, "넣은 이유");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            storySaveHelper.saveStory(id, List.of(stepData(1, 1, placeName(stepId))), story(1, "이야기"), "나고야 3박 4일");
            throw new IllegalStateException("commit 직전 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(status(id)).isEqualTo(StoryStatus.PENDING);
        assertThat(notes(stepId)).isEqualTo("넣은 이유");
        assertThat(title(id)).isEqualTo("나고야 3박 4일");

        assertThat(storySaveHelper.markFailed(id)).isTrue();
        assertThat(status(id)).isEqualTo(StoryStatus.FAILED);
    }

    @Test
    @DisplayName("이야기가 끝나기 전에 읽은 일정을 확정·저장해도 이야기 상태와 제목을 되돌리지 않는다")
    void staleEntitySaveKeepsStoryResult() {
        Long id = itinerary("나고야 3박 4일", 4);
        Long stepId = step(id, 1, 1, "넣은 이유");
        TransactionTemplate separate = new TransactionTemplate(txManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(s -> {
            Itinerary loaded = itineraryRepository.findById(id).orElseThrow();
            separate.executeWithoutResult(n -> storySaveHelper.saveStory(id,
                    List.of(stepData(1, 1, placeName(stepId))), story(1, "이야기"), "나고야 3박 4일"));
            loaded.complete();
        });

        assertThat(status(id)).isEqualTo(StoryStatus.DONE);
        assertThat(title(id)).isEqualTo("나고야 장어 산책");
        assertThat(jdbc.queryForObject("SELECT status FROM itineraries WHERE id = ?", String.class, id))
                .isEqualTo("FINALIZED");
    }

    @Test
    @DisplayName("실패 기록은 DONE을 덮지 않고, 늦게 끝난 이야기는 FAILED를 DONE으로 바꾼다")
    void transitionsRespectFinishedStory() {
        Long done = itinerary("오사카 2박 3일", 3);
        itineraryRepository.updateStoryStatus(done, StoryStatus.DONE);
        assertThat(storySaveHelper.markFailed(done)).isFalse();
        assertThat(status(done)).isEqualTo(StoryStatus.DONE);

        Long late = itinerary("교토 1박 2일", 2);
        Long stepId = step(late, 1, 1, null);
        storySaveHelper.markFailed(late);
        storySaveHelper.saveStory(late, List.of(stepData(1, 1, placeName(stepId))), story(1, "이야기"), "교토 1박 2일");
        assertThat(status(late)).isEqualTo(StoryStatus.DONE);
    }

    @Test
    @DisplayName("끝나지 못한 이야기 정리는 오래된 PENDING만 FAILED로 바꾼다")
    void staleTransitionOnlyOldPending() {
        Long old = itinerary("부산 1박 2일", 2);
        Long recent = itinerary("부산 2박 3일", 3);
        Long oldDone = itinerary("부산 당일치기", 1);
        itineraryRepository.updateStoryStatus(oldDone, StoryStatus.DONE);
        jdbc.update("UPDATE itineraries SET created_at = now() - interval '2 hours' WHERE id IN (?, ?)", old, oldDone);

        int changed = itineraryRepository.transitionStoryStatusCreatedBefore(StoryStatus.PENDING, StoryStatus.FAILED,
                OffsetDateTime.now().minusMinutes(30));

        assertThat(changed).isEqualTo(1);
        assertThat(status(old)).isEqualTo(StoryStatus.FAILED);
        assertThat(status(recent)).isEqualTo(StoryStatus.PENDING);
        assertThat(status(oldDone)).isEqualTo(StoryStatus.DONE);
    }

    @Test
    @DisplayName("V71 기존 일정 판별: 메모 없음·이유만 FAILED, 하루 2개 넘는 메모 DONE, 판별 불가는 null")
    void migrationClassifiesLegacyItineraries() throws Exception {
        // 나고야 3박 4일: 이야기가 실패해 넣은 이유 3개만 메모로 남고 제목도 임시값이다.
        Long reasonsOnly = itinerary("나고야 3박 4일", 4);
        String[] reasons = {"장어 덮밥을 세 가지로 나눠 먹는 나고야식을 맛본다", "성 안 혼마루 어전을 본다", "오스 상점가 골목을 걷는다"};
        for (int i = 0; i < 12; i++) step(reasonsOnly, i / 3 + 1, i + 1, i < 3 ? reasons[i] : null);
        trace(reasonsOnly, "{\"concept\":\"나고야\",\"days\":[{\"items\":[{\"name\":\"이마이 소혼케\",\"reason\":\"" + reasons[0]
                + "\"},{\"name\":\"나고야성\",\"reason\":\"" + reasons[1] + "\"}]},{\"items\":[{\"reason\":\"" + reasons[2]
                + "\"},{\"reason\":\"쓰이지 않은 이유\"}]}]}");

        Long storyDone = itinerary("나고야 장어와 성 산책", 2);
        for (int i = 0; i < 6; i++) step(storyDone, i / 3 + 1, i + 1, "이야기 " + i);

        Long noNotes = itinerary("서울 1박 2일", 2);
        step(noNotes, 1, 1, null);
        step(noNotes, 1, 2, "   ");

        Long noSteps = itinerary("부산 당일치기", 1);

        Long fewNotesNoTrace = itinerary("경주 1박 2일", 2);
        step(fewNotesNoTrace, 1, 1, "판별할 근거가 없는 메모");
        step(fewNotesNoTrace, 1, 2, null);

        Long fewNotesNotReason = itinerary("전주 당일치기", 1);
        step(fewNotesNotReason, 1, 1, "이야기 문장");
        trace(fewNotesNotReason, "{\"days\":[{\"items\":[{\"reason\":\"다른 이유\"}]}]}");

        Long finalizedReasonsOnly = itinerary("제주 1박 2일", 2);
        jdbc.update("UPDATE itineraries SET status = 'FINALIZED' WHERE id = ?", finalizedReasonsOnly);
        step(finalizedReasonsOnly, 1, 1, "오름에 오른다");
        trace(finalizedReasonsOnly, "{\"days\":[{\"items\":[{\"reason\":\"오름에 오른다\"}]}]}");

        List<Long> ids = List.of(reasonsOnly, storyDone, noNotes, noSteps, fewNotesNoTrace, fewNotesNotReason,
                finalizedReasonsOnly);
        jdbc.update("UPDATE itineraries SET story_status = NULL WHERE user_id = ?", userId);
        jdbc.update(v71Backfill() + " AND i.user_id = " + userId);

        assertThat(ids.stream().map(this::status).toList()).containsExactly(
                StoryStatus.FAILED, StoryStatus.DONE, StoryStatus.FAILED, StoryStatus.FAILED, null, null,
                StoryStatus.FAILED);
    }

    /** 운영 데이터를 바꾼 그 UPDATE를 그대로 다시 돌려 판별 규칙을 고정한다. */
    private static String v71Backfill() throws Exception {
        String sql = new ClassPathResource("db/migration/V71__add_itinerary_story_status.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String backfill = sql.substring(sql.indexOf("WITH step_notes")).strip();
        return backfill.endsWith(";") ? backfill.substring(0, backfill.length() - 1) : backfill;
    }

    private Long itinerary(String title, int days) {
        LocalDate start = LocalDate.of(2026, 12, 5);
        return itineraryRepository.save(Itinerary.builder().userId(userId).title(title).destination("나고야")
                .startDate(start).endDate(start.plusDays(days - 1)).build()).getId();
    }

    private Long step(Long itineraryId, int day, int order, String notes) {
        Long placeId = jdbc.queryForObject("""
                INSERT INTO places (name, address, latitude, longitude, category)
                VALUES (?, ?, 35.17, 136.90, 'Landmarks and Outdoors') RETURNING id
                """, Long.class, "story-it-" + itineraryId + "-" + order, "story-it " + System.nanoTime());
        return jdbc.queryForObject("""
                INSERT INTO itinerary_steps (itinerary_id, step_order, day_number, start_time, end_time, place_id, notes)
                VALUES (?, ?, ?, '09:00', '10:00', ?, ?) RETURNING id
                """, Long.class, itineraryId, order, day, placeId, notes);
    }

    private void trace(Long itineraryId, String draftJson) {
        jdbc.update("""
                INSERT INTO generation_traces (itinerary_id, user_id, job_id, trace_version, request, enriched_input,
                                               selection_raw, selection_final, candidates, schedule_args)
                VALUES (?, ?, 'story-it', 1, '{}', '{}', ?::jsonb, '{}', '[]', '{}')
                """, itineraryId, userId, draftJson);
    }

    private String placeName(Long stepId) {
        return jdbc.queryForObject("SELECT p.name FROM itinerary_steps s JOIN places p ON p.id = s.place_id WHERE s.id = ?",
                String.class, stepId);
    }

    private static StepData stepData(int day, int order, String placeName) {
        return new StepData(order, day, "09:00", "10:00", new PlaceData(placeName, null, "관광", "Nagoya", "JP"),
                List.of(), null, null, null, null, null, BigDecimal.ZERO);
    }

    private static AssemblyItineraryOutput story(int order, String text) {
        return new AssemblyItineraryOutput("나고야 장어 산책", List.of(),
                List.of(new AssemblyItineraryOutput.StoryStep(order, text)));
    }

    private StoryStatus status(Long id) {
        String raw = jdbc.queryForObject("SELECT story_status FROM itineraries WHERE id = ?", String.class, id);
        return raw == null ? null : StoryStatus.valueOf(raw);
    }

    private Integer version(Long id) {
        return jdbc.queryForObject("SELECT version FROM itineraries WHERE id = ?", Integer.class, id);
    }

    private String title(Long id) {
        return jdbc.queryForObject("SELECT title FROM itineraries WHERE id = ?", String.class, id);
    }

    private String notes(Long stepId) {
        return jdbc.queryForObject("SELECT notes FROM itinerary_steps WHERE id = ?", String.class, stepId);
    }
}
