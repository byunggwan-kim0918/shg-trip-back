package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.planning.service.PlanningCountries;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.GenerateJobResponse;
import com.shg.trip.shgtrip.domain.planning.service.GenerationPolicyService;
import com.shg.trip.shgtrip.domain.planning.service.TravelPlannerService;
import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatAnswerRequest;
import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatResponse;
import com.shg.trip.shgtrip.domain.tripchat.entity.TripChatSession;
import com.shg.trip.shgtrip.domain.tripchat.model.Interpretation;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import com.shg.trip.shgtrip.domain.tripchat.repository.TripChatSessionRepository;
import com.shg.trip.shgtrip.domain.user.service.ActiveUserGuard;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TripChatServiceTest {

    private static final Long USER = 7L;
    private static final String BUSAN_ALL = "부산 11월 20일부터 25일까지 남자친구랑 둘이, 바다 보면서 느긋하게 쉬고 싶고 "
            + "자동차 이용할 거임. 정해둔 건 없어";

    @Mock private TripChatSessionRepository repository;
    @Mock private TripChatInterpreter interpreter;
    @Mock private PlaceMatcher placeMatcher;
    @Mock private GenerationPolicyService generationPolicyService;
    @Mock private TravelPlannerService travelPlannerService;
    @Mock private ActiveUserGuard activeUserGuard;

    private final Map<UUID, TripChatSession> db = new HashMap<>();
    private TripChatService service;
    private final AtomicInteger turnIds = new AtomicInteger();
    private boolean failNextSave;

    @BeforeEach
    void setUp() {
        ObjectMapper om = JsonMapper.builder().findAndAddModules().build();
        Clock clock = Clock.fixed(Instant.parse("2026-11-10T01:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new TripChatService(repository, interpreter, placeMatcher, generationPolicyService,
                travelPlannerService, activeUserGuard, Validation.buildDefaultValidatorFactory().getValidator(), om,
                new TransactionTemplate(new NoResourceTransactionManager()), clock,
                new PlanningCountries(List.of("KR", "JP"), Destinations.fromClasspath()));

        lenient().when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            if (failNextSave) {
                failNextSave = false;
                throw new ObjectOptimisticLockingFailureException(TripChatSession.class, "x");
            }
            TripChatSession s = inv.getArgument(0);
            Long v = s.getVersion();
            ReflectionTestUtils.setField(s, "version", v == null ? 0L : v + 1);
            db.put(s.getId(), s);
            return s;
        });
        lenient().when(repository.findActive(anyLong())).thenAnswer(inv -> db.values().stream()
                .filter(s -> s.getUserId().equals(inv.getArgument(0)) && s.isActive()).findFirst());
        lenient().when(repository.findByIdAndUserId(any(), anyLong())).thenAnswer(inv ->
                Optional.ofNullable(db.get((UUID) inv.getArgument(0)))
                        .filter(s -> s.getUserId().equals(inv.getArgument(1))));
        lenient().when(placeMatcher.match(any())).thenReturn(Map.of());
    }

    // ---- 대화 ----

    @Test
    @DisplayName("새 대화는 여행지 질문 하나로 시작한다")
    void startsWithCityQuestion() {
        TripChatResponse r = service.create(USER, "req-1");

        assertThat(r.status()).isEqualTo("ASKING");
        assertThat(r.question().slot()).isEqualTo("CITY");
        assertThat(r.question().title()).isEqualTo("어디로 떠나볼까요?");
        assertThat(r.question().examplesOpen()).isFalse();
        assertThat(r.history()).isEmpty();
        assertThat(r.back()).isNull();
    }

    @Test
    @DisplayName("같은 요청 id로 다시 만들면 기존 대화를, 새 id면 기존 대화를 보관하고 새로 만든다")
    void createIsIdempotent() {
        TripChatResponse first = service.create(USER, "req-1");
        assertThat(service.create(USER, "req-1").id()).isEqualTo(first.id());

        TripChatResponse second = service.create(USER, "req-2");
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(db.get(UUID.fromString(first.id())).isActive()).isFalse();
    }

    @Test
    @DisplayName("첫 답에 여러 조건이 있으면 이미 답한 질문을 건너뛴다")
    void multiInfoAnswerSkipsQuestions() {
        TripChatResponse start = service.create(USER, "req-1");
        given(interpreter.interpret(anyString(), eq("부산 11월 20일부터 25일까지 남자친구랑 둘이")))
                .willReturn(result(new Interpretation(List.of(
                        upd("DESTINATION", "부산", "user", "부산"),
                        upd("START_DATE", "2026-11-20", "user", "11월 20일부터 25일까지"),
                        upd("END_DATE", "2026-11-25", "user", "11월 20일부터 25일까지"),
                        upd("COMPANION", "couple", "user", "남자친구랑 둘이")), "answered", null, false)));

        TripChatResponse r = answer(start, "부산 11월 20일부터 25일까지 남자친구랑 둘이");

        assertThat(r.question().slot()).isEqualTo("MOOD");
        assertThat(r.question().lead()).isEqualTo("부산 여행을 생각하고 있군요.");
        assertThat(r.lastExchange().answer()).isEqualTo("부산 11월 20일부터 25일까지 남자친구랑 둘이");
        assertThat(r.back()).isEqualTo("CITY");
        assertThat(row(r, Slot.DATES)).isEqualTo("11월 20일(금) ~ 25일(수) · 5박 6일");
    }

    @Test
    @DisplayName("열지 않은 나라의 여행지면 바로 준비 중이라고 알리고 여행지만 다시 묻는다")
    void closedCountryDestinationIsAskedAgain() {
        TripChatResponse start = service.create(USER, "req-1");
        given(interpreter.interpret(anyString(), eq("상하이 11월 20일부터 25일까지")))
                .willReturn(result(new Interpretation(List.of(
                        upd("DESTINATION", "상하이", "user", "상하이"),
                        upd("START_DATE", "2026-11-20", "user", "11월 20일부터 25일까지"),
                        upd("END_DATE", "2026-11-25", "user", "11월 20일부터 25일까지")), "answered", null, false)));

        TripChatResponse r = answer(start, "상하이 11월 20일부터 25일까지");

        assertThat(r.status()).isEqualTo("ASKING");
        assertThat(r.question().slot()).isEqualTo("CITY");
        assertThat(r.question().lead())
                .isEqualTo("중국 여행지는 아직 준비 중이에요. 지금은 한국·일본 여행지로 일정을 만들 수 있어요.");
        assertThat(row(r, Slot.DATES)).isEqualTo("11월 20일(금) ~ 25일(수) · 5박 6일");
    }

    @Test
    @DisplayName("같은 답을 다시 보내도 한 번만 해석한다")
    void duplicateTurnIsAppliedOnce() {
        TripChatResponse start = service.create(USER, "req-1");
        given(interpreter.interpret(anyString(), eq("부산"))).willReturn(result(new Interpretation(
                List.of(upd("DESTINATION", "부산", "user", "부산")), "answered", null, false)));
        TripChatAnswerRequest req = new TripChatAnswerRequest(start.question().id(), "turn-1", "text", "부산");

        TripChatResponse first = service.answer(USER, id(start), req);
        TripChatResponse again = service.answer(USER, id(start), req);

        verify(interpreter, times(1)).interpret(anyString(), anyString());
        assertThat(again.version()).isEqualTo(first.version());
        assertThat(again.history()).hasSize(1);
    }

    @Test
    @DisplayName("이미 바뀐 질문에 온 답은 충돌로 거절한다")
    void staleQuestionIsConflict() {
        TripChatResponse start = service.create(USER, "req-1");

        assertThatThrownBy(() -> service.answer(USER, id(start),
                new TripChatAnswerRequest("old-question", "turn-1", "text", "부산")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TRIP_CHAT_CONFLICT);
    }

    @Test
    @DisplayName("해석이 실패하면 503을 주고 상태를 그대로 둔다. 같은 답으로 다시 보내면 반영된다")
    void interpretFailureKeepsState() {
        TripChatResponse start = service.create(USER, "req-1");
        TripChatAnswerRequest req = new TripChatAnswerRequest(start.question().id(), "turn-1", "text", "부산");
        given(interpreter.interpret(anyString(), eq("부산")))
                .willThrow(new TripChatInterpreter.InterpretException("down", null))
                .willReturn(result(new Interpretation(List.of(upd("DESTINATION", "부산", "user", "부산")),
                        "answered", null, false)));

        assertThatThrownBy(() -> service.answer(USER, id(start), req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TRIP_CHAT_UNAVAILABLE);
        TripChatResponse current = service.current(USER);
        assertThat(current.question().id()).isEqualTo(start.question().id());
        assertThat(current.history()).isEmpty();
        assertThat(current.pending()).isNull();

        TripChatResponse retried = service.answer(USER, id(start), req);
        assertThat(retried.question().slot()).isEqualTo("DATES");
    }

    @Test
    @DisplayName("답을 해석하는 동안 온 다른 답은 처리 중 충돌로 거절한다")
    void pendingBlocksOtherAnswers() {
        TripChatResponse start = service.create(USER, "req-1");
        List<Throwable> concurrent = new ArrayList<>();
        given(interpreter.interpret(anyString(), eq("부산"))).willAnswer(inv -> {
            try {
                service.answer(USER, id(start), new TripChatAnswerRequest(start.question().id(), "turn-2", "text", "제주"));
            } catch (BusinessException e) {
                concurrent.add(e);
            }
            assertThat(service.current(USER).pending()).isEqualTo("부산");
            return result(new Interpretation(List.of(upd("DESTINATION", "부산", "user", "부산")), "answered", null, false));
        });

        service.answer(USER, id(start), new TripChatAnswerRequest(start.question().id(), "turn-1", "text", "부산"));

        assertThat(concurrent).singleElement().extracting("errorCode").isEqualTo(ErrorCode.TRIP_CHAT_CONFLICT);
        verify(interpreter, times(1)).interpret(anyString(), anyString());
    }

    @Test
    @DisplayName("되묻기의 '아직 모르겠어요'는 해석 없이 미정으로 두고 다음 질문으로 간다")
    void unknownButtonSkipsInterpreter() {
        TripChatResponse start = service.create(USER, "req-1");
        given(interpreter.interpret(anyString(), eq(BUSAN_ALL.replace("자동차 이용할 거임", "흠 걸어서?"))))
                .willReturn(result(new Interpretation(List.of(
                        upd("DESTINATION", "부산", "user", "부산"),
                        upd("START_DATE", "2026-11-20", "user", "11월 20일부터 25일까지"),
                        upd("END_DATE", "2026-11-25", "user", "11월 20일부터 25일까지"),
                        upd("COMPANION", "couple", "user", "남자친구랑 둘이"),
                        upd("PRIORITIES", "바다 보면서 느긋하게 쉬기", "user", "바다 보면서 느긋하게 쉬고 싶고"),
                        upd("TRANSPORT", "walk", "tentative", "흠 걸어서?")), "answered",
                        new Interpretation.FollowUp(true, "tentative", "걷는 쪽으로 생각 중이군요.",
                                "먼 곳은 택시나 버스도 괜찮아요?", "가까운 곳만 걸어서 둘러봐도 좋아요.",
                                List.of("가까운 곳만 걸어 다닐래", "멀면 다른 교통편도 괜찮아"), "예: 멀면 택시도 괜찮아"),
                        false)));

        TripChatResponse followUp = answer(start, BUSAN_ALL.replace("자동차 이용할 거임", "흠 걸어서?"));
        assertThat(followUp.question().kind()).isEqualTo("FOLLOW_UP");
        assertThat(followUp.question().tool()).isEqualTo("UNKNOWN");

        TripChatResponse next = service.answer(USER, id(start),
                new TripChatAnswerRequest(followUp.question().id(), "turn-x", "unknown", null));

        verify(interpreter, times(1)).interpret(anyString(), anyString());
        assertThat(next.question().slot()).isEqualTo("ANCHORS");
        assertThat(next.question().lead()).isEqualTo(QuestionBank.UNDECIDED_LEAD);
        assertThat(row(next, Slot.TRANSPORT)).isEqualTo("도보를 생각 중 · 확정 전");
    }

    @Test
    @DisplayName("확인 화면에서 한 항목을 고치면 그 항목만 바뀌고 확인 화면으로 돌아간다")
    void editReplacesOneSlot() {
        TripChatResponse review = toReview();
        assertThat(review.status()).isEqualTo("REVIEW");

        TripChatResponse editing = service.edit(USER, id(review), review.version(), Slot.COMPANY);
        assertThat(editing.question().kind()).isEqualTo("EDIT");
        assertThat(editing.question().prefill()).isEqualTo("남자친구랑 둘이");
        assertThat(editing.question().lead()).isEqualTo("바꾸고 싶은 내용을 알려주세요");
        assertThat(editing.back()).isEqualTo("CANCEL");

        given(interpreter.interpret(anyString(), eq("친구 3명이랑, 나까지 4명"))).willReturn(result(new Interpretation(
                List.of(upd("COMPANION", "friends", "user", "친구 3명이랑"), upd("PARTY_SIZE", "4", "user", "나까지 4명")),
                "answered", null, false)));
        TripChatResponse after = answer(editing, "친구 3명이랑, 나까지 4명");

        assertThat(after.status()).isEqualTo("REVIEW");
        assertThat(row(after, Slot.COMPANY)).isEqualTo("친구 3명이랑");
        assertThat(row(after, Slot.CITY)).isEqualTo("부산");
        assertThat(row(after, Slot.MOOD)).isEqualTo("바다 보면서 느긋하게 쉬기");
    }

    @Test
    @DisplayName("수정을 취소하면 확인 화면으로 돌아간다")
    void cancelEditReturnsToReview() {
        TripChatResponse review = toReview();
        TripChatResponse editing = service.edit(USER, id(review), review.version(), Slot.CITY);

        TripChatResponse back = service.cancelEdit(USER, id(review), editing.version());

        assertThat(back.status()).isEqualTo("REVIEW");
        assertThat(back.question()).isNull();
    }

    @Test
    @DisplayName("확인 화면의 추가 조건은 요약에 보존된다")
    void extraConditionsAppearInSummary() {
        TripChatResponse review = toReview();
        given(interpreter.interpret(anyString(), eq("첫날 오후 3시에 도착해. 해산물은 못 먹어"))).willReturn(result(
                new Interpretation(List.of(upd("ARRIVAL_TIME", "15:00", "user", "첫날 오후 3시에 도착해"),
                        new Interpretation.Update("FOOD_AVOID", "add", "해산물", "known", "user", "해산물은 못 먹어", "", "")),
                        "answered", null, false)));

        TripChatResponse r = service.answer(USER, id(review), new TripChatAnswerRequest(
                TripChatService.EXTRA_QUESTION_ID, "turn-extra", "text", "첫날 오후 3시에 도착해. 해산물은 못 먹어"));

        assertThat(r.status()).isEqualTo("REVIEW");
        assertThat(row(r, Slot.EXTRAS)).isEqualTo("첫날 15:00 도착 · 피할 음식: 해산물");
    }

    @Test
    @DisplayName("수정 질문에 그 항목과 무관한 답을 하면 원래 값을 지우지 않는다")
    void unrelatedEditAnswerKeepsSlot() {
        TripChatResponse review = toReview();
        TripChatResponse editing = service.edit(USER, id(review), review.version(), Slot.COMPANY);
        given(interpreter.interpret(anyString(), eq("잠깐만"))).willReturn(result(
                new Interpretation(List.of(), "unclear", null, false)));

        TripChatResponse after = answer(editing, "잠깐만");

        assertThat(row(after, Slot.COMPANY)).isEqualTo("남자친구랑 둘이");
    }

    @Test
    @DisplayName("반영 중 동시 수정이 나면 충돌로 알리고 처리 중 표시를 남기지 않는다")
    void concurrentWriteDuringApplyIsConflict() {
        TripChatResponse start = service.create(USER, "req-1");
        given(interpreter.interpret(anyString(), eq("부산"))).willAnswer(inv -> {
            failNextSave = true;
            return result(new Interpretation(List.of(upd("DESTINATION", "부산", "user", "부산")), "answered", null, false));
        });

        assertThatThrownBy(() -> answer(start, "부산"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TRIP_CHAT_CONFLICT);
        assertThat(service.current(USER).pending()).isNull();
    }

    @Test
    @DisplayName("해석 횟수 상한을 넘으면 추가 조건은 메모로만 받고, 생성에 필요한 보완 답은 계속 해석한다")
    void haikuCapExemptsRequiredFixes() throws Exception {
        TripChatResponse review = reviewWithoutDates();
        TripChatSession session = db.get(id(review));
        ObjectMapper om = JsonMapper.builder().findAndAddModules().build();
        com.fasterxml.jackson.databind.node.ObjectNode state =
                (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(session.getState());
        state.put("haikuCalls", TripChatService.MAX_HAIKU_PER_SESSION);
        ReflectionTestUtils.setField(session, "state", om.writeValueAsString(state));

        service.answer(USER, id(review), new TripChatAnswerRequest(
                TripChatService.EXTRA_QUESTION_ID, "turn-extra", "text", "해산물은 못 먹어"));
        verify(interpreter, times(1)).interpret(anyString(), anyString());

        given(interpreter.interpret(anyString(), eq("11월 20일부터 2박"))).willReturn(result(new Interpretation(List.of(
                upd("START_DATE", "2026-11-20", "user", "11월 20일부터"),
                upd("NIGHTS", "2", "user", "2박")), "answered", null, false)));
        TripChatResponse fixed = service.answer(USER, id(review), new TripChatAnswerRequest(
                "fix:dates_missing", "turn-fix", "text", "11월 20일부터 2박"));

        verify(interpreter, times(2)).interpret(anyString(), anyString());
        assertThat(fixed.summary().ready()).isTrue();
    }

    @Test
    @DisplayName("만료된 대화에는 답할 수 없고 다시 불러오면 보관된다")
    void expiredSessionIsClosed() {
        TripChatResponse start = service.create(USER, "req-1");
        ReflectionTestUtils.setField(db.get(id(start)), "expiresAt", OffsetDateTime.parse("2026-11-01T00:00:00+09:00"));

        assertThatThrownBy(() -> answer(start, "부산"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TRIP_CHAT_CONFLICT);
        assertThat(service.current(USER)).isNull();
        verify(interpreter, never()).interpret(anyString(), anyString());
    }

    // ---- 생성 연결 ----

    @Test
    @DisplayName("확인하면 기존 생성 작업을 한 번만 시작하고, 같은 버전의 중복 확인은 같은 작업을 돌려준다")
    void confirmStartsGenerationOnce() {
        TripChatResponse review = toReview();
        given(travelPlannerService.startGeneration(any(), eq(USER))).willReturn(new GenerateJobResponse("job-1"));

        TripChatResponse confirmed = service.confirm(USER, id(review), review.version());
        TripChatResponse again = service.confirm(USER, id(review), review.version());

        ArgumentCaptor<ItineraryGenerateRequest> captor = ArgumentCaptor.forClass(ItineraryGenerateRequest.class);
        verify(travelPlannerService, times(1)).startGeneration(captor.capture(), eq(USER));
        assertThat(captor.getValue().destination()).isEqualTo("부산");
        assertThat(captor.getValue().transportPref()).isEqualTo("car");
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.jobId()).isEqualTo("job-1");
        assertThat(confirmed.days()).isEqualTo(6);
        assertThat(again.jobId()).isEqualTo("job-1");
    }

    @Test
    @DisplayName("확인 전에는 생성하지 않고, 날짜가 없으면 확인해도 생성하지 않는다")
    void noGenerationWithoutDates() {
        TripChatResponse review = reviewWithoutDates();

        assertThat(review.status()).isEqualTo("REVIEW");
        assertThat(review.summary().ready()).isFalse();
        assertThat(review.summary().fixes()).extracting(TripChatResponse.Fix::questionId)
                .containsExactly("fix:dates_missing");
        assertThatThrownBy(() -> service.confirm(USER, id(review), review.version()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(travelPlannerService, never()).startGeneration(any(), anyLong());
    }

    @Test
    @DisplayName("생성 한도 초과면 확인 화면에서 바로 알리고 처리 중 표시를 지운다")
    void quotaErrorSurfacesOnReview() {
        TripChatResponse review = toReview();
        given(travelPlannerService.startGeneration(any(), eq(USER)))
                .willThrow(new BusinessException(ErrorCode.GENERATION_QUOTA_EXCEEDED));

        assertThatThrownBy(() -> service.confirm(USER, id(review), review.version()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.GENERATION_QUOTA_EXCEEDED);
        TripChatResponse current = service.current(USER);
        assertThat(current.status()).isEqualTo("REVIEW");
        assertThat(current.pending()).isNull();
    }

    @Test
    @DisplayName("id로 보관하면 그 대화만 보관하고 다른 대화는 그대로 둔다")
    void archiveById() {
        TripChatResponse first = service.create(USER, "req-1");
        service.archive(USER, id(first));
        TripChatResponse second = service.create(USER, "req-2");

        service.archive(USER, id(first));
        service.archive(99L, id(second));

        assertThat(service.current(USER).id()).isEqualTo(second.id());
    }

    @Test
    @DisplayName("요약 행은 값이 있는지 플래그로 알려준다")
    void rowsCarryFilledFlag() {
        TripChatResponse review = toReview();

        assertThat(review.summary().rows()).allMatch(TripChatResponse.Row::filled);
        assertThat(service.create(USER, "req-new").summary().rows()).noneMatch(TripChatResponse.Row::filled);
    }

    @Test
    @DisplayName("다른 사용자의 대화는 찾을 수 없다")
    void otherUsersSessionIsNotFound() {
        TripChatResponse start = service.create(USER, "req-1");

        assertThatThrownBy(() -> service.answer(99L, id(start),
                new TripChatAnswerRequest(start.question().id(), "t", "text", "부산")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ---- 도우미 ----

    private TripChatResponse toReview() {
        TripChatResponse start = service.create(USER, "req-1");
        given(interpreter.interpret(anyString(), eq(BUSAN_ALL))).willReturn(result(new Interpretation(List.of(
                upd("DESTINATION", "부산", "user", "부산"),
                upd("START_DATE", "2026-11-20", "user", "11월 20일부터 25일까지"),
                upd("END_DATE", "2026-11-25", "user", "11월 20일부터 25일까지"),
                upd("COMPANION", "couple", "user", "남자친구랑 둘이"),
                upd("PRIORITIES", "바다 보면서 느긋하게 쉬기", "user", "바다 보면서 느긋하게 쉬고 싶고"),
                new Interpretation.Update("THEMES", "add", "ocean", "known", "inferred", "", "", ""),
                upd("PACE", "relaxed", "user", "느긋하게"),
                upd("TRANSPORT", "car", "user", "자동차 이용할 거임"),
                new Interpretation.Update("LODGING_STATE", "set", "", "none", "user", "정해둔 건 없어", "", "")),
                "answered", null, false)));
        return answer(start, BUSAN_ALL);
    }

    private TripChatResponse reviewWithoutDates() {
        TripChatResponse start = service.create(USER, "req-1");
        String text = "부산, 날짜는 아직 몰라, 둘이, 맡길게, 차, 정해둔 건 없어";
        given(interpreter.interpret(anyString(), eq(text))).willReturn(result(new Interpretation(List.of(
                upd("DESTINATION", "부산", "user", "부산"),
                new Interpretation.Update("START_DATE", "set", "", "undecided", "user", "날짜는 아직 몰라", "", ""),
                upd("COMPANION", "couple", "user", "둘이"),
                new Interpretation.Update("PRIORITIES", "set", "", "delegated", "user", "맡길게", "", ""),
                upd("TRANSPORT", "car", "user", "차"),
                new Interpretation.Update("LODGING_STATE", "set", "", "none", "user", "정해둔 건 없어", "", "")),
                "answered", null, false)));
        return answer(start, text);
    }

    private TripChatResponse answer(TripChatResponse at, String text) {
        return service.answer(USER, id(at), new TripChatAnswerRequest(at.question().id(),
                "turn-" + turnIds.incrementAndGet(), "text", text));
    }

    private static UUID id(TripChatResponse r) {
        return UUID.fromString(r.id());
    }

    private static String row(TripChatResponse r, Slot slot) {
        return r.summary().rows().stream().filter(x -> x.slot().equals(slot.name())).findFirst()
                .map(TripChatResponse.Row::value).orElse(null);
    }

    private static Interpretation.Update upd(String field, String value, String source, String raw) {
        return new Interpretation.Update(field, "set", value, "known", source, raw, "", "");
    }

    private static TripChatInterpreter.Result result(Interpretation it) {
        return new TripChatInterpreter.Result(it, new TripChatInterpreter.Usage(1000, 200, 0, 900));
    }

    private static class NoResourceTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
