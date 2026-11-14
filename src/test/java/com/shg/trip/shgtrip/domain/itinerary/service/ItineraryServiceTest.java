package com.shg.trip.shgtrip.domain.itinerary.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryResponse;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryStepResponse;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryEditEvent;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryEditEventRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F3 스텝 재정렬/삭제 로직 단위 테스트.
 * findAndVerifyOwner(레포)와 EntityManager.flush()만 목킹하고, stepOrder 재배정·이동 재계산·검증을 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ItineraryServiceTest {

    @Mock private ItineraryRepository itineraryRepository;
    @Mock private PlaceRepository placeRepository;
    @Mock private GenerationTraceRepository generationTraceRepository;
    @Mock private ItineraryEditEventRepository editEventRepository;
    @Mock private EntityManager entityManager;
    @Mock private com.shg.trip.shgtrip.domain.planning.service.QualityGate qualityGate;

    @InjectMocks private ItineraryService itineraryService;

    private static final Long ITINERARY_ID = 1L;
    private static final Long USER_ID = 100L;

    private Itinerary itinerary;

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor로 생성자 주입되면 @InjectMocks가 @PersistenceContext 필드를 건드리지 않으므로 직접 주입
        ReflectionTestUtils.setField(itineraryService, "entityManager", entityManager);

        // Day1: A(0) B(1) C(2), Day2: D(3) E(4), Day3: F(5) 단일
        List<ItineraryStep> steps = new ArrayList<>(List.of(
                step(10L, 1, 0, "제주공항", 33.510, 126.491),
                step(11L, 1, 1, "카페", 33.450, 126.560),
                step(12L, 1, 2, "오름", 33.400, 126.600),
                step(20L, 2, 3, "호텔", 33.480, 126.500),
                step(21L, 2, 4, "해변", 33.240, 126.410),
                step(30L, 3, 5, "박물관", 33.500, 126.530)
        ));
        itinerary = Itinerary.builder()
                .id(ITINERARY_ID)
                .userId(USER_ID)
                .title("제주 여행")
                .destination("제주")
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 3))
                .steps(steps)
                .build();
        steps.forEach(s -> s.setItinerary(itinerary));
    }

    private ItineraryStep step(long id, int day, int order, String name, double lat, double lng) {
        Place place = Place.builder()
                .id(id * 10)
                .name(name).address(name + " 주소")
                .latitude(BigDecimal.valueOf(lat)).longitude(BigDecimal.valueOf(lng))
                .category("관광").region("제주").country("Korea")
                .build();
        return ItineraryStep.builder()
                .id(id).dayNumber(day).stepOrder(order)
                // order별 distinct 시간 → 재정렬 시 "시간 슬롯 고정((b))"과 "시간이 스텝에 붙어 이동((a))"이 구분된다.
                .startTime(String.format("%02d:00", 9 + order)).endTime(String.format("%02d:00", 10 + order))
                .place(place)
                .transportationMode("WALK")            // 초기값 있음 → 재계산 후 첫 스텝이 null 되는지 확인
                .transportationDuration(10)
                .build();
    }

    private void mockFind() {
        when(itineraryRepository.findByIdWithDetails(ITINERARY_ID)).thenReturn(Optional.of(itinerary));
        lenient().when(itineraryRepository.findByIdForUpdate(ITINERARY_ID)).thenReturn(Optional.of(itinerary));
    }

    private ItineraryEditEvent onlyEvent() {
        ArgumentCaptor<ItineraryEditEvent> captor = ArgumentCaptor.forClass(ItineraryEditEvent.class);
        verify(editEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private Map<Long, ItineraryStepResponse> byId(ItineraryResponse res) {
        return res.steps().stream().collect(java.util.stream.Collectors.toMap(ItineraryStepResponse::id, s -> s));
    }

    @Test
    @DisplayName("일정을 삭제하면 요청 문장이 담긴 생성 기록도 함께 지운다")
    void deletesGenerationTraceWithItinerary() {
        mockFind();

        itineraryService.deleteItinerary(ITINERARY_ID, USER_ID);

        assertThat(itinerary.isDeleted()).isTrue();
        verify(generationTraceRepository).deleteByItineraryId(ITINERARY_ID);
    }

    // ── reorder ──

    @Test
    @DisplayName("같은 day 내 재정렬: 점유 슬롯을 새 순서로 재배정하고 다른 day는 불변")
    void reordersWithinDay() {
        mockFind();
        // Day1 [A,B,C] → [C,A,B]
        ItineraryResponse res = itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(12L, 10L, 11L));

        var m = byId(res);
        assertThat(m.get(12L).stepOrder()).isEqualTo(0); // C
        assertThat(m.get(10L).stepOrder()).isEqualTo(1); // A
        assertThat(m.get(11L).stepOrder()).isEqualTo(2); // B
        // Day2/Day3 불변
        assertThat(m.get(20L).stepOrder()).isEqualTo(3);
        assertThat(m.get(21L).stepOrder()).isEqualTo(4);
        assertThat(m.get(30L).stepOrder()).isEqualTo(5);
    }

    @Test
    @DisplayName("재정렬 후 day 첫 스텝 교통정보는 비고, 이후 스텝은 재계산된다 (시간은 자리 고정)")
    void recalculatesTransportationAfterReorder() {
        mockFind();
        ItineraryResponse res = itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(12L, 10L, 11L));

        var m = byId(res);
        // 새 첫 스텝 C(order0): 인바운드 leg 없음 → null
        assertThat(m.get(12L).transportationMode()).isNull();
        assertThat(m.get(12L).transportationDuration()).isNull();
        // 이후 스텝 A(order1), B(order2): 재계산되어 mode 채워짐
        assertThat(m.get(10L).transportationMode()).isNotNull();
        assertThat(m.get(11L).transportationMode()).isNotNull();
        // 시간 슬롯 고정((b)): 이동한 스텝은 그 위치(슬롯)의 시간을 물려받는다.
        // [C(원래 11:00) A(09:00) B(10:00)] → 슬롯 시간 [09,10,11]에 재배치.
        // (a) 스텝에 시간이 붙어 이동이면 C=11:00으로 남아 실패 → 이 단언이 (b)를 못박는다.
        assertThat(m.get(12L).startTime()).isEqualTo("09:00"); // slot0 (원래 C는 11:00)
        assertThat(m.get(10L).startTime()).isEqualTo("10:00"); // slot1 (원래 A는 09:00)
        assertThat(m.get(11L).startTime()).isEqualTo("11:00"); // slot2 (원래 B는 10:00)
    }

    @Test
    @DisplayName("다른 도시에서 넘어온 날은 순서를 바꾸거나 첫 스텝을 지워도 열차 구간이 새 첫 스텝에 남는다")
    void keepsIntercityLegOnNewFirstStep() {
        mockFind();
        itinerary.getSteps().stream().filter(s -> s.getId() == 20L).findFirst().orElseThrow()
                .updateTransportation("TRAIN", 197, BigDecimal.valueOf(524), BigDecimal.valueOf(117_600));

        var reordered = byId(itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 2, List.of(21L, 20L)));
        assertThat(reordered.get(21L).transportationMode()).isEqualTo("TRAIN");
        assertThat(reordered.get(21L).transportationDuration()).isEqualTo(197);
        assertThat(reordered.get(20L).transportationMode()).isNotEqualTo("TRAIN");

        var deleted = byId(itineraryService.deleteStep(ITINERARY_ID, USER_ID, 21L));
        assertThat(deleted.get(20L).transportationMode()).isEqualTo("TRAIN");
    }

    @Test
    @DisplayName("표지 장소가 스텝 밖(여행지 대표 사진)이면 따로 읽어 사진과 출처를 함께 준다")
    void coverOutsideStepsCarriesAttribution() {
        ReflectionTestUtils.setField(itinerary, "coverPlaceId", 999L);
        mockFind();
        when(placeRepository.findById(999L)).thenReturn(Optional.of(Place.builder().id(999L).name("Tokyo Tower")
                .imageUrl("https://upload.wikimedia.org/t.jpg").imageCredit("Kim").imageLicense("CC BY 4.0")
                .imageSourceUrl("https://commons.wikimedia.org/wiki/File:T.jpg").build()));

        ItineraryResponse res = itineraryService.getItinerary(ITINERARY_ID, USER_ID);

        assertThat(res.coverImage()).isEqualTo("https://upload.wikimedia.org/t.jpg");
        assertThat(res.coverImageCredit()).isEqualTo("Kim");
        assertThat(res.coverImageLicense()).isEqualTo("CC BY 4.0");
        assertThat(res.coverImageSourceUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:T.jpg");
    }

    @Test
    @DisplayName("재정렬 요청이 해당 day 스텝 집합과 불일치하면(누락/타 day 주입) 400")
    void rejectsMismatchedReorder() {
        mockFind();
        // Day1 스텝인데 하나 누락
        assertThatThrownBy(() -> itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(10L, 11L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("재정렬 요청에 타 day 스텝 id가 섞이면 400")
    void rejectsCrossDayStepInReorder() {
        mockFind();
        // Day1 자리에 Day2 스텝(20L) 주입
        assertThatThrownBy(() -> itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(10L, 11L, 20L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("중복 stepId가 있으면 400")
    void rejectsDuplicateStepInReorder() {
        mockFind();
        assertThatThrownBy(() -> itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(10L, 10L, 11L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ── delete ──

    @Test
    @DisplayName("중간 스텝 삭제: 제거 후 stepOrder를 0..N-1로 재정렬")
    void deletesMiddleStepAndResequences() {
        mockFind();
        // Day1의 B(11L) 삭제 → 남은 A,C,D,E,F 재정렬
        ItineraryResponse res = itineraryService.deleteStep(ITINERARY_ID, USER_ID, 11L);

        var m = byId(res);
        assertThat(m).doesNotContainKey(11L);
        assertThat(m.get(10L).stepOrder()).isEqualTo(0); // A
        assertThat(m.get(12L).stepOrder()).isEqualTo(1); // C
        assertThat(m.get(20L).stepOrder()).isEqualTo(2); // D
        assertThat(m.get(21L).stepOrder()).isEqualTo(3); // E
        assertThat(m.get(30L).stepOrder()).isEqualTo(4); // F
    }

    @Test
    @DisplayName("삭제 후 해당 day 첫 스텝 교통정보는 비고 나머지는 재계산")
    void recalculatesTransportationAfterDelete() {
        mockFind();
        // Day1 첫 스텝 A(10L) 삭제 → 남은 [B,C] 중 새 첫 스텝은 B(11L)
        ItineraryResponse res = itineraryService.deleteStep(ITINERARY_ID, USER_ID, 10L);

        var m = byId(res);
        assertThat(m.get(11L).transportationMode()).isNull();   // 새 첫 스텝 B
        assertThat(m.get(12L).transportationMode()).isNotNull(); // C는 B 다음 → 재계산
    }

    @Test
    @DisplayName("day 마지막 1스텝은 삭제 불가 (400)")
    void rejectsDeletingLastStepOfDay() {
        mockFind();
        // Day3은 F(30L) 단일
        assertThatThrownBy(() -> itineraryService.deleteStep(ITINERARY_ID, USER_ID, 30L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("존재하지 않는 stepId 삭제는 404")
    void rejectsDeletingUnknownStep() {
        mockFind();
        assertThatThrownBy(() -> itineraryService.deleteStep(ITINERARY_ID, USER_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("소유자가 아니면 재정렬/삭제 모두 접근 거부")
    void rejectsNonOwner() {
        mockFind();
        Long otherUser = 999L;
        assertThatThrownBy(() -> itineraryService.reorderSteps(ITINERARY_ID, otherUser, 1, List.of(12L, 10L, 11L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ITINERARY_ACCESS_DENIED);
        assertThatThrownBy(() -> itineraryService.deleteStep(ITINERARY_ID, otherUser, 11L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ITINERARY_ACCESS_DENIED);
    }

    @Test
    @DisplayName("삭제 후 남은 스텝은 (day, order) 순서를 유지")
    void keepsDayOrderAfterDelete() {
        mockFind();
        ItineraryResponse res = itineraryService.deleteStep(ITINERARY_ID, USER_ID, 21L); // Day2 해변 삭제
        List<Integer> orders = res.steps().stream()
                .sorted(Comparator.comparingInt(ItineraryStepResponse::stepOrder))
                .map(ItineraryStepResponse::stepOrder)
                .toList();
        assertThat(orders).containsExactly(0, 1, 2, 3, 4); // 연속
    }

    // ── 확정·공유 ──

    @Test
    @DisplayName("확정된 일정은 재정렬·삭제·대안 선택을 막고 제목·태그 수정은 허용한다")
    void finalizedItineraryLocksSteps() {
        mockFind();
        itinerary.complete();

        assertThatThrownBy(() -> itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(12L, 10L, 11L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ITINERARY_FINALIZED);
        assertThatThrownBy(() -> itineraryService.deleteStep(ITINERARY_ID, USER_ID, 11L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ITINERARY_FINALIZED);
        assertThatThrownBy(() -> itineraryService.selectAlternative(ITINERARY_ID, 11L, 1L, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ITINERARY_FINALIZED);

        ItineraryResponse res = itineraryService.updateItinerary(ITINERARY_ID, USER_ID,
                new com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryUpdateRequest("새 제목", List.of("태그")));
        assertThat(res.title()).isEqualTo("새 제목");
    }

    @Test
    @DisplayName("이미 확정된 일정을 다시 확정해도 오류 없이 그대로 반환한다")
    void finalizeIsIdempotent() {
        mockFind();
        itineraryService.finalizeItinerary(ITINERARY_ID, USER_ID);
        itineraryService.finalizeItinerary(ITINERARY_ID, USER_ID);

        assertThat(itinerary.getStatus()).isEqualTo(Itinerary.ItineraryStatus.FINALIZED);
    }

    @Test
    @DisplayName("스텝이 없는 일정은 확정할 수 없다")
    void rejectsFinalizingEmptyItinerary() {
        itinerary.getSteps().clear();
        mockFind();

        assertThatThrownBy(() -> itineraryService.finalizeItinerary(ITINERARY_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ITINERARY_EMPTY);
        assertThat(itinerary.getStatus()).isEqualTo(Itinerary.ItineraryStatus.DRAFT);
    }

    @Test
    @DisplayName("유효한 공유 토큰이 있으면 새로 발급하지 않고 그대로 돌려준다")
    void reusesValidShareToken() {
        mockFind();
        var first = itineraryService.generateShareLink(ITINERARY_ID, USER_ID);
        var second = itineraryService.generateShareLink(ITINERARY_ID, USER_ID);

        assertThat(second.shareToken()).isEqualTo(first.shareToken());
        assertThat(second.expiresAt()).isEqualTo(first.expiresAt());
    }

    @Test
    @DisplayName("만료된 공유 토큰은 새로 발급한다")
    void reissuesExpiredShareToken() {
        mockFind();
        itinerary.generateShareToken("expired", java.time.OffsetDateTime.now().minusDays(1));

        var link = itineraryService.generateShareLink(ITINERARY_ID, USER_ID);

        assertThat(link.shareToken()).isNotEqualTo("expired");
        assertThat(link.expiresAt()).isAfter(java.time.OffsetDateTime.now());
    }

    // ── 편집 신호 ──

    @Test
    @DisplayName("일정 삭제는 지운 뒤에도 남는 편집 기록을 쓴다(장소 수·확정 여부)")
    void recordsItineraryDeletion() {
        mockFind();

        itineraryService.deleteItinerary(ITINERARY_ID, USER_ID);

        ItineraryEditEvent e = onlyEvent();
        assertThat(e.getType()).isEqualTo(ItineraryEditEvent.Type.DELETE_ITINERARY);
        assertThat(e.getItineraryId()).isEqualTo(ITINERARY_ID);
        assertThat(e.getUserId()).isEqualTo(USER_ID);
        assertThat(e.getPayload()).containsEntry("stepCount", 6).containsEntry("finalized", false);
    }

    @Test
    @DisplayName("재정렬은 장소 id 순서 전후를 남기고, 순서가 그대로면 남기지 않는다")
    void recordsReorderOnlyWhenChanged() {
        mockFind();

        itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(10L, 11L, 12L));
        verify(editEventRepository, never()).save(any());

        itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(12L, 10L, 11L));
        ItineraryEditEvent e = onlyEvent();
        assertThat(e.getType()).isEqualTo(ItineraryEditEvent.Type.REORDER);
        assertThat(e.getDayNumber()).isEqualTo(1);
        assertThat(e.getPayload()).containsEntry("before", List.of(100L, 110L, 120L))
                .containsEntry("after", List.of(120L, 100L, 110L));
    }

    @Test
    @DisplayName("스텝 삭제는 지운 장소 id·순서를 남기고, 거부된 삭제는 남기지 않는다")
    void recordsStepDeletion() {
        mockFind();

        assertThatThrownBy(() -> itineraryService.deleteStep(ITINERARY_ID, USER_ID, 30L))
                .isInstanceOf(BusinessException.class);
        verify(editEventRepository, never()).save(any());

        itineraryService.deleteStep(ITINERARY_ID, USER_ID, 11L);
        ItineraryEditEvent e = onlyEvent();
        assertThat(e.getType()).isEqualTo(ItineraryEditEvent.Type.DELETE_STEP);
        assertThat(e.getStepId()).isEqualTo(11L);
        assertThat(e.getPayload()).containsEntry("placeId", 110L).containsEntry("stepOrder", 1);
    }

    @Test
    @DisplayName("확정은 처음 한 번만 남긴다")
    void recordsFinalizeOnce() {
        mockFind();

        itineraryService.finalizeItinerary(ITINERARY_ID, USER_ID);
        itineraryService.finalizeItinerary(ITINERARY_ID, USER_ID);

        verify(editEventRepository, times(1)).save(any());
        assertThat(onlyEvent().getType()).isEqualTo(ItineraryEditEvent.Type.FINALIZE);
    }

    @Test
    @DisplayName("대안 선택은 바뀌기 전·후 장소 id를 남긴다")
    void recordsAlternativeSelection() {
        mockFind();
        ItineraryStep cafe = itinerary.getSteps().stream().filter(st -> st.getId().equals(11L)).findFirst().orElseThrow();
        Place other = Place.builder().id(999L).name("다른 카페").address("주소")
                .latitude(BigDecimal.valueOf(33.46)).longitude(BigDecimal.valueOf(126.55))
                .category("관광").region("제주").country("Korea").build();
        cafe.addAlternative(com.shg.trip.shgtrip.domain.itinerary.entity.AlternativeOption.builder()
                .id(5L).place(other).optionOrder(1).build());

        itineraryService.selectAlternative(ITINERARY_ID, 11L, 5L, USER_ID);

        ItineraryEditEvent e = onlyEvent();
        assertThat(e.getType()).isEqualTo(ItineraryEditEvent.Type.SELECT_ALTERNATIVE);
        assertThat(e.getPayload()).containsEntry("fromPlaceId", 110L).containsEntry("toPlaceId", 999L);
        verify(qualityGate).reflectEdit(itinerary);
    }

    @Test
    @DisplayName("대안 선택·재정렬·스텝 삭제는 바뀐 일정으로 품질을 다시 판정한다")
    void editsReflectQuality() {
        mockFind();
        itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(12L, 10L, 11L));
        itineraryService.deleteStep(ITINERARY_ID, USER_ID, 11L);

        verify(qualityGate, times(2)).reflectEdit(itinerary);
    }

    @Test
    @DisplayName("숙소·공항은 순서 변경으로 자리를 옮길 수 없고, 다른 스텝이 그 자리로 들어와도 거부한다")
    void rejectsMovingAnchors() {
        mockFind();
        ReflectionTestUtils.setField(stepById(10L).getPlace(), "category", "Travel and Transportation > Transport Hub > Airport");

        assertThatThrownBy(() -> itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(11L, 10L, 12L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("숙소와 공항·역은 자리를 옮길 수 없어요");
        assertThatThrownBy(() -> itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(12L, 11L, 10L)))
                .isInstanceOf(BusinessException.class);

        ItineraryResponse res = itineraryService.reorderSteps(ITINERARY_ID, USER_ID, 1, List.of(10L, 12L, 11L));
        assertThat(byId(res).get(10L).stepOrder()).isEqualTo(0);
    }

    @Test
    @DisplayName("편집 뒤 일정 금액은 장소 비용과 이동 비용의 합으로 다시 계산한다")
    void recalculatesEstimatedCostAfterEdit() {
        mockFind();
        itinerary.getSteps().forEach(st -> ReflectionTestUtils.setField(st, "estimatedCost", BigDecimal.valueOf(10_000)));

        ItineraryResponse res = itineraryService.deleteStep(ITINERARY_ID, USER_ID, 11L);

        BigDecimal expected = itinerary.getSteps().stream()
                .map(st -> BigDecimal.valueOf(10_000).add(
                        st.getTransportationCost() != null ? st.getTransportationCost() : BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(res.estimatedCost()).isEqualByComparingTo(expected);
        assertThat(expected).isGreaterThan(BigDecimal.valueOf(50_000));
    }

    @Test
    @DisplayName("편집은 일정 행 잠금을 먼저 잡는다")
    void locksItineraryBeforeEditing() {
        mockFind();

        itineraryService.deleteStep(ITINERARY_ID, USER_ID, 11L);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(itineraryRepository);
        order.verify(itineraryRepository).findByIdForUpdate(ITINERARY_ID);
        order.verify(itineraryRepository).findByIdWithDetails(ITINERARY_ID);
    }

    private ItineraryStep stepById(long id) {
        return itinerary.getSteps().stream().filter(st -> st.getId().equals(id)).findFirst().orElseThrow();
    }
}
