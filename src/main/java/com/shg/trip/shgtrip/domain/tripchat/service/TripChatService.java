package com.shg.trip.shgtrip.domain.tripchat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.service.GenerationPolicyService;
import com.shg.trip.shgtrip.domain.planning.service.TravelPlannerService;
import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatAnswerRequest;
import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatResponse;
import com.shg.trip.shgtrip.domain.tripchat.entity.TripChatSession;
import com.shg.trip.shgtrip.domain.tripchat.entity.TripChatStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.ChatState;
import com.shg.trip.shgtrip.domain.tripchat.model.Draft;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftEvent;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.EventOp;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldSource;
import com.shg.trip.shgtrip.domain.tripchat.model.FieldStatus;
import com.shg.trip.shgtrip.domain.tripchat.model.Interpretation;
import com.shg.trip.shgtrip.domain.tripchat.model.OpenQuestion;
import com.shg.trip.shgtrip.domain.tripchat.model.PendingTurn;
import com.shg.trip.shgtrip.domain.tripchat.model.PlaceMatch;
import com.shg.trip.shgtrip.domain.tripchat.model.QuestionKind;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import com.shg.trip.shgtrip.domain.tripchat.model.Turn;
import com.shg.trip.shgtrip.domain.tripchat.repository.TripChatSessionRepository;
import com.shg.trip.shgtrip.domain.user.service.ActiveUserGuard;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 대화형 여행 입력. 답 하나마다 상태를 읽고, 필요하면 Haiku로 해석하고, 코드 정책으로 다음 질문을 정해 저장한다.
 * Haiku 호출과 장소 검색은 트랜잭션 밖에서 하고, 그동안 세션에 처리 중 표시를 남겨 같은 답이 두 번 해석되거나
 * 늦게 끝난 해석이 그 사이 바뀐 상태를 덮어쓰지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripChatService {

    public static final String EXTRA_QUESTION_ID = "extra";
    public static final String FIX_PREFIX = "fix:";
    private static final String CONFIRM_TURN = "__confirm";
    private static final Duration SESSION_TTL = Duration.ofDays(14);
    /** 해석 호출 상한(요청 10초 × 2회 + 대기)보다 길게 둔다. 이보다 오래된 표시는 서버가 죽어 남은 것으로 본다. */
    private static final Duration PENDING_STALE = Duration.ofSeconds(30);
    static final int MAX_HAIKU_PER_SESSION = 20;
    /** 수정·버튼 답을 반복해도 상태 JSON이 끝없이 커지지 않게 막는다. */
    static final int MAX_TURNS = 80;

    private final TripChatSessionRepository repository;
    private final TripChatInterpreter interpreter;
    private final PlaceMatcher placeMatcher;
    private final GenerationPolicyService generationPolicyService;
    private final TravelPlannerService travelPlannerService;
    private final ActiveUserGuard activeUserGuard;
    private final Validator validator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public TripChatResponse current(Long userId) {
        return inTx(() -> repository.findActive(userId).map(session -> {
            ChatState st = read(session);
            if (st == null || session.getExpiresAt().isBefore(now())) {
                session.archive();
                return null;
            }
            return view(session, st);
        }).orElse(null));
    }

    public TripChatResponse create(Long userId, String clientRequestId) {
        try {
            return inTx(() -> {
                // 탈퇴와 동시에 만들어진 대화가 탈퇴 계정에 원문을 남기지 않게 사용자 행을 잠근다.
                activeUserGuard.lockActive(userId);
                Optional<TripChatSession> active = repository.findActive(userId);
                if (active.isPresent() && active.get().getClientRequestId().equals(clientRequestId)) {
                    ChatState st = read(active.get());
                    if (st != null) return view(active.get(), st);
                }
                active.ifPresent(a -> {
                    a.archive();
                    repository.flush();
                });
                ChatState st = new ChatState();
                open(st, QuestionBank.base(Slot.CITY, DraftFolder.fold(List.of()), today(), null));
                TripChatSession session = repository.saveAndFlush(
                        TripChatSession.start(userId, clientRequestId, write(st), now().plus(SESSION_TTL)));
                return view(session, st);
            });
        } catch (DataIntegrityViolationException | BusinessException e) {
            if (e instanceof BusinessException be && be.getErrorCode() != ErrorCode.TRIP_CHAT_CONFLICT) throw be;
            // 다른 탭이 같은 순간 새 대화를 만들면 그쪽을 이어 쓴다.
            TripChatResponse existing = current(userId);
            if (existing != null) return existing;
            throw conflict();
        }
    }

    public void archiveCurrent(Long userId) {
        inTx(() -> {
            repository.findActive(userId).ifPresent(TripChatSession::archive);
            return null;
        });
    }

    /** 생성이 끝난 대화만 보관한다. 그 사이 다른 탭에서 시작한 새 대화는 건드리지 않는다. */
    public void archive(Long userId, UUID id) {
        inTx(() -> {
            repository.findByIdAndUserId(id, userId).filter(TripChatSession::isActive)
                    .ifPresent(TripChatSession::archive);
            return null;
        });
    }

    public TripChatResponse answer(Long userId, UUID id, TripChatAnswerRequest req) {
        String text = req.text() == null ? null : req.text().strip();
        boolean free = "text".equals(req.kind());
        if (free && (text == null || text.isEmpty())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "답변을 입력해주세요.");
        }
        if (free) generationPolicyService.checkTripChatAllowed(userId);

        Prepared prepared = inTx(() -> {
            TripChatSession session = loadActive(userId, id);
            ChatState st = readOrConflict(session);
            if (st.hasTurn(req.clientTurnId())) return Prepared.done(view(session, st));
            ensureNotPending(st);
            if (st.getTurns().size() >= MAX_TURNS) {
                throw new BusinessException(ErrorCode.TRIP_CHAT_RATE_LIMITED,
                        "대화가 길어져 더 받을 수 없어요. 확인 화면에서 마무리하거나 새로 시작해 주세요.");
            }
            OpenQuestion question = resolveQuestion(session, st, req.questionId());
            if (!free) {
                applyButton(session, st, question, req.kind(), req.clientTurnId());
                return Prepared.done(view(session, st));
            }
            // 생성에 꼭 필요한 정보의 보완 답은 상한에서 뺀다. 막히면 대화를 끝낼 수 없다.
            if (st.getHaikuCalls() >= MAX_HAIKU_PER_SESSION && question.kind() != QuestionKind.FIX) {
                st.getEvents().add(new DraftEvent(st.nextSeq(), Slot.EXTRAS, DraftField.NOTES, EventOp.ADD,
                        TextNormalizer.cap(text, 60), FieldStatus.KNOWN, FieldSource.USER, text, null, null));
                recordTurn(st, question, text, "text", req.clientTurnId());
                st.setOpenQuestion(null);
                save(session, TripChatStatus.REVIEW, st);
                return Prepared.done(view(session, st));
            }
            String context = TripChatContext.build(DraftFolder.fold(st.getEvents()), st, question, today());
            st.setPending(new PendingTurn(req.clientTurnId(), text, now()));
            save(session, session.getStatus(), st);
            return Prepared.interpret(question, context, session.getState());
        });
        if (prepared.view() != null) return prepared.view();

        TripChatInterpreter.Result result;
        try {
            result = interpreter.interpret(prepared.context(), text);
        } catch (RuntimeException e) {
            releasePending(userId, id, req.clientTurnId());
            log.warn("대화 턴 실패 session={} q={}:{} 원인={}", id, prepared.question().slot(),
                    prepared.question().kind(), e.getMessage());
            throw new BusinessException(ErrorCode.TRIP_CHAT_UNAVAILABLE);
        }
        Matches matches = previewMatches(prepared, text, result.interpretation());

        try {
            return inTx(() -> {
                TripChatSession session = loadActive(userId, id);
                ChatState st = readOrConflict(session);
                PendingTurn pending = st.getPending();
                if (pending == null || !pending.clientTurnId().equals(req.clientTurnId())) throw conflict();
                st.setPending(null);
                st.setHaikuCalls(st.getHaikuCalls() + 1);
                List<DraftEvent> events = appendEvents(st, prepared.question(), text, result.interpretation());
                boolean datesTouched = prepared.question().slot() == Slot.DATES
                        || events.stream().anyMatch(e -> e.slot() == Slot.DATES);
                recordTurn(st, prepared.question(), text, "text", req.clientTurnId());
                advance(session, st, prepared.question(), result.interpretation(), "text", datesTouched, matches);
                TripChatInterpreter.Usage u = result.usage();
                log.info("대화 턴 session={} q={}:{} haiku=1 in={} out={} cacheRead={} ms={} 결과={}", id,
                        prepared.question().slot(), prepared.question().kind(), u.inputTokens(), u.outputTokens(),
                        u.cacheReadTokens(), u.millis(), result.interpretation().offTopic() ? "offTopic" : events.size() + "건");
                return view(session, st);
            });
        } catch (RuntimeException e) {
            // 반영이 실패해도 처리 중 표시는 지워야 같은 답을 다시 보낼 수 있다.
            releasePending(userId, id, req.clientTurnId());
            if (e instanceof BusinessException be) throw be;
            log.warn("대화 턴 반영 실패 session={}: {}", id, e.getMessage());
            throw new BusinessException(ErrorCode.TRIP_CHAT_UNAVAILABLE);
        }
    }

    public TripChatResponse edit(Long userId, UUID id, long expectedVersion, Slot slot) {
        if (slot == null) throw new BusinessException(ErrorCode.INVALID_INPUT);
        return inTx(() -> {
            TripChatSession session = loadActive(userId, id);
            checkVersion(session, expectedVersion);
            ChatState st = readOrConflict(session);
            ensureNotPending(st);
            Draft draft = DraftFolder.fold(st.getEvents());
            String prefill = draft.latestRaw(slot).orElseGet(() -> lastAnswer(st, slot));
            boolean fromReview = session.getStatus() != TripChatStatus.ASKING;
            OpenQuestion current = st.getOpenQuestion();
            if (current == null || current.kind() != QuestionKind.EDIT) st.setResume(current);
            st.setOpenQuestion(QuestionBank.edit(slot, draft, today(), prefill, fromReview));
            save(session, TripChatStatus.ASKING, st);
            return view(session, st);
        });
    }

    public TripChatResponse cancelEdit(Long userId, UUID id, long expectedVersion) {
        return inTx(() -> {
            TripChatSession session = loadActive(userId, id);
            checkVersion(session, expectedVersion);
            ChatState st = readOrConflict(session);
            ensureNotPending(st);
            OpenQuestion editing = st.getOpenQuestion();
            if (editing == null || editing.kind() != QuestionKind.EDIT) return view(session, st);
            OpenQuestion resume = st.getResume();
            st.setResume(null);
            if (editing.returnToReview()) {
                st.setOpenQuestion(null);
                save(session, TripChatStatus.REVIEW, st);
            } else if (resume != null) {
                st.setOpenQuestion(resume);
                save(session, TripChatStatus.ASKING, st);
            } else {
                advance(session, st, editing, null, "unknown", false, null);
            }
            return view(session, st);
        });
    }

    /**
     * 확인한 조건으로 기존 생성 작업을 시작한다. 같은 버전으로 다시 오면(중복 클릭·다른 탭) 같은 작업을 돌려준다.
     */
    public TripChatResponse confirm(Long userId, UUID id, long expectedVersion) {
        Confirming c = inTx(() -> {
            TripChatSession session = loadActive(userId, id);
            ChatState st = readOrConflict(session);
            if (session.getStatus() == TripChatStatus.CONFIRMED && session.getJobId() != null
                    && Long.valueOf(expectedVersion).equals(st.getConfirmedVersion())) {
                return Confirming.done(view(session, st));
            }
            checkVersion(session, expectedVersion);
            ensureNotPending(st);
            if (session.getStatus() == TripChatStatus.ASKING) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "대화를 마친 뒤 확인 화면에서 만들 수 있어요.");
            }
            TripRequestMapper.Result mapping = TripRequestMapper.map(DraftFolder.fold(st.getEvents()),
                    st.getMatches(), today());
            if (!mapping.ready()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, mapping.blockers().get(0).description());
            }
            Set<ConstraintViolation<ItineraryGenerateRequest>> violations = validator.validate(mapping.request());
            if (!violations.isEmpty()) {
                ConstraintViolation<ItineraryGenerateRequest> v = violations.iterator().next();
                log.warn("대화 생성 요청 검증 실패 session={} field={} message={}", id, v.getPropertyPath(), v.getMessage());
                throw new BusinessException(ErrorCode.INVALID_INPUT, v.getMessage());
            }
            st.setPending(new PendingTurn(CONFIRM_TURN, null, now()));
            save(session, session.getStatus(), st);
            return Confirming.start(mapping.request());
        });
        if (c.view() != null) return c.view();

        String jobId;
        try {
            activeUserGuard.requireActive(userId);
            jobId = travelPlannerService.startGeneration(c.request(), userId).jobId();
        } catch (RuntimeException e) {
            releasePending(userId, id, CONFIRM_TURN);
            throw e;
        }
        return inTx(() -> {
            TripChatSession session = loadActive(userId, id);
            ChatState st = readOrConflict(session);
            st.setPending(null);
            st.setConfirmedVersion(expectedVersion);
            session.confirm(jobId, write(st), now());
            repository.saveAndFlush(session);
            log.info("대화 확인 session={} 질문={} 턴={} haiku={} 선택장소={} jobId={}", id, st.questionCount(),
                    st.getTurns().size(), st.getHaikuCalls(), c.request().selectedPlaceIds().size(), jobId);
            return view(session, st);
        });
    }

    private void applyButton(TripChatSession session, ChatState st, OpenQuestion q, String kind, String clientTurnId) {
        boolean unknown = "unknown".equals(kind) && OpenQuestion.TOOL_UNKNOWN.equals(q.tool());
        boolean none = "none".equals(kind) && OpenQuestion.TOOL_NONE.equals(q.tool());
        if (!unknown && !none) throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (q.kind() == QuestionKind.EDIT) clearSlot(st, q.slot());
        String label = unknown ? "아직 모르겠어요" : "정해둔 건 없어요";
        st.getEvents().add(DraftEvent.slotOutcome(st.nextSeq(), q.slot(),
                unknown ? FieldStatus.UNDECIDED : FieldStatus.NONE, label));
        recordTurn(st, q, label, kind, clientTurnId);
        advance(session, st, q, null, kind, false, null);
        log.info("대화 턴 session={} q={}:{} haiku=0 결과={}", session.getId(), q.slot(), q.kind(), kind);
    }

    /** 수정·보완 질문은 새 답에 그 항목 정보가 있을 때만 기존 값을 지운다. 무관한 답으로 원래 값이 사라지면 안 된다. */
    private static List<DraftEvent> appendEvents(ChatState st, OpenQuestion q, String text, Interpretation it) {
        if (it.offTopic()) return List.of();
        AtomicLong probeSeq = new AtomicLong();
        List<DraftEvent> probe = UpdateSanitizer.toEvents(it, q.slot(), text, probeSeq::incrementAndGet);
        if (q.kind() == QuestionKind.EDIT && probe.stream().anyMatch(e -> e.slot() == q.slot())) {
            clearSlot(st, q.slot());
        }
        if (q.kind() == QuestionKind.FIX && q.askKey() != null) {
            List<DraftField> replace = TripRequestMapper.fieldsToReplace(q.askKey().substring(FIX_PREFIX.length()));
            if (probe.stream().anyMatch(e -> replace.contains(e.field()))) {
                replace.forEach(f -> st.getEvents().add(DraftEvent.clearField(st.nextSeq(), f)));
            }
        }
        List<DraftEvent> events = UpdateSanitizer.toEvents(it, q.slot(), text, st::nextSeq);
        st.getEvents().addAll(events);
        return events;
    }

    /**
     * 반영 후 상태의 장소 이름을 트랜잭션 밖에서 DB에 맞춰 둔다. 반영 트랜잭션 안에서 조회하다 실패하면
     * 그 트랜잭션 전체가 롤백 전용이 돼 답이 버려진다.
     */
    private Matches previewMatches(Prepared prepared, String text, Interpretation it) {
        try {
            ChatState preview = objectMapper.readValue(prepared.state(), ChatState.class);
            appendEvents(preview, prepared.question(), text, it);
            Draft draft = DraftFolder.fold(preview.getEvents());
            String signature = PlaceMatcher.signature(draft);
            if (signature.equals(preview.getMatchSignature())) return null;
            return new Matches(signature, placeMatcher.match(draft));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("대화 장소 매칭 건너뜀: {}", e.getMessage());
            return null;
        }
    }

    private void advance(TripChatSession session, ChatState st, OpenQuestion answered, Interpretation it,
                         String answerKind, boolean datesTouched, Matches matches) {
        Draft draft = DraftFolder.fold(st.getEvents());
        if (matches != null && matches.signature().equals(PlaceMatcher.signature(draft))) {
            st.setMatches(new LinkedHashMap<>(matches.byKey()));
            st.setMatchSignature(matches.signature());
        }
        NextStepPolicy.Decision d = NextStepPolicy.decide(draft, st.getAskedKeys(), answered, it, answerKind,
                datesTouched, today());
        st.setResume(null);
        if (d.review()) {
            st.setOpenQuestion(null);
            save(session, TripChatStatus.REVIEW, st);
        } else {
            open(st, d.question());
            save(session, TripChatStatus.ASKING, st);
        }
    }

    private static void clearSlot(ChatState st, Slot slot) {
        st.getEvents().add(DraftEvent.clearSlot(st.nextSeq(), slot));
        for (DraftField f : DraftField.values()) {
            if (f.slot() == slot) st.getEvents().add(DraftEvent.clearField(st.nextSeq(), f));
        }
    }

    private void recordTurn(ChatState st, OpenQuestion q, String answer, String kind, String clientTurnId) {
        st.getTurns().add(new Turn(clientTurnId, q.id(), q.slot(), q.kind(), q.title(), answer, kind, now()));
    }

    private static void open(ChatState st, OpenQuestion q) {
        st.setOpenQuestion(q);
        if ((q.kind() == QuestionKind.BASE || q.kind() == QuestionKind.FOLLOW_UP) && q.askKey() != null) {
            st.getAskedKeys().add(q.askKey());
        }
    }

    /** 확인 화면에서는 "더 알려주고 싶은 게 있어요"와 생성에 필요한 보완 질문에도 답할 수 있다. */
    private OpenQuestion resolveQuestion(TripChatSession session, ChatState st, String questionId) {
        OpenQuestion open = st.getOpenQuestion();
        if (open != null && open.id().equals(questionId)) return open;
        if (session.getStatus() == TripChatStatus.ASKING) throw stale();
        if (EXTRA_QUESTION_ID.equals(questionId)) {
            return new OpenQuestion(EXTRA_QUESTION_ID, Slot.EXTRAS, QuestionKind.EXTRA, null,
                    "더 알려주고 싶은 게 있어요", null, null, List.of(), false, null, null, null, true);
        }
        if (questionId.startsWith(FIX_PREFIX)) {
            String key = questionId.substring(FIX_PREFIX.length());
            TripRequestMapper.Result mapping = TripRequestMapper.map(DraftFolder.fold(st.getEvents()),
                    st.getMatches(), today());
            return mapping.blockers().stream().filter(b -> b.key().equals(key)).findFirst()
                    .map(b -> new OpenQuestion(questionId, b.slot(), QuestionKind.FIX, null, b.title(),
                            b.description(), b.placeholder(), b.examples(), true, null, FIX_PREFIX + b.key(),
                            null, true))
                    .orElseThrow(TripChatService::stale);
        }
        throw stale();
    }

    TripChatResponse view(TripChatSession session, ChatState st) {
        LocalDate today = today();
        Draft draft = DraftFolder.fold(st.getEvents());
        TripRequestMapper.Result mapping = TripRequestMapper.map(draft, st.getMatches(), today);
        TripChatStatus status = session.getStatus();
        OpenQuestion q = status == TripChatStatus.ASKING ? st.getOpenQuestion() : null;

        List<TripChatResponse.Exchange> history = st.getTurns().stream()
                .map(t -> new TripChatResponse.Exchange(t.question(), t.answer())).toList();
        TripChatResponse.Exchange last = history.isEmpty() ? null : history.get(history.size() - 1);
        PendingTurn pending = freshPending(st);

        return new TripChatResponse(
                session.getId().toString(),
                session.getVersion() == null ? 0 : session.getVersion(),
                status.name(),
                q == null ? null : new TripChatResponse.Question(q.id(), q.slot().name(), q.kind().name(), q.lead(),
                        q.title(), q.description(), q.placeholder(), q.examples(), q.examplesOpen(), q.tool(),
                        q.prefill()),
                last,
                history,
                SummaryBuilder.build(draft, mapping, today),
                back(st, q),
                pending == null ? null : pending.text(),
                status == TripChatStatus.CONFIRMED ? session.getJobId() : null,
                mapping.days());
    }

    /** 기본 질문에서는 직전에 답한 항목을, 되묻기에서는 그 항목의 원래 질문을 다시 연다. */
    private static String back(ChatState st, OpenQuestion q) {
        if (q == null) return null;
        if (q.kind() == QuestionKind.EDIT) return "CANCEL";
        if (q.kind() == QuestionKind.FOLLOW_UP) return q.slot().name();
        for (int i = st.getTurns().size() - 1; i >= 0; i--) {
            Slot slot = st.getTurns().get(i).slot();
            if (slot != Slot.EXTRAS) return slot.name();
        }
        return null;
    }

    private static String lastAnswer(ChatState st, Slot slot) {
        for (int i = st.getTurns().size() - 1; i >= 0; i--) {
            Turn t = st.getTurns().get(i);
            if (t.slot() == slot && "text".equals(t.answerKind())) return t.answer();
        }
        return null;
    }

    private TripChatSession loadActive(Long userId, UUID id) {
        TripChatSession session = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "대화를 찾을 수 없어요."));
        if (!session.isActive() || session.getExpiresAt().isBefore(now())) throw conflict();
        return session;
    }

    /** best-effort. 남은 표시는 PENDING_STALE이 지나면 무시된다. */
    private void releasePending(Long userId, UUID id, String clientTurnId) {
        try {
            inTx(() -> {
                repository.findByIdAndUserId(id, userId).ifPresent(session -> {
                    ChatState st = read(session);
                    if (st == null || st.getPending() == null || !st.getPending().clientTurnId().equals(clientTurnId)) {
                        return;
                    }
                    st.setPending(null);
                    save(session, session.getStatus(), st);
                });
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("대화 처리 중 표시 해제 실패 session={}: {}", id, e.getMessage());
        }
    }

    private void ensureNotPending(ChatState st) {
        if (freshPending(st) != null) {
            throw new BusinessException(ErrorCode.TRIP_CHAT_CONFLICT, "앞선 답변을 정리하고 있어요. 잠시만 기다려주세요.");
        }
        st.setPending(null);
    }

    private PendingTurn freshPending(ChatState st) {
        PendingTurn p = st.getPending();
        return p != null && p.startedAt().plus(PENDING_STALE).isAfter(now()) ? p : null;
    }

    private static void checkVersion(TripChatSession session, long expected) {
        if (session.getVersion() == null || session.getVersion() != expected) throw conflict();
    }

    private void save(TripChatSession session, TripChatStatus status, ChatState st) {
        session.update(status, write(st), now().plus(SESSION_TTL));
        repository.saveAndFlush(session);
    }

    /** 읽을 수 없는 상태면 충돌로 돌려보낸다. 다시 불러오면 current()가 보관하고 새로 시작하게 한다. */
    private ChatState readOrConflict(TripChatSession session) {
        ChatState st = read(session);
        if (st == null) throw conflict();
        return st;
    }

    /** 구조가 바뀐 이전 버전 상태는 null. 이어하지 않고 새로 시작하게 한다. */
    private ChatState read(TripChatSession session) {
        try {
            ChatState st = objectMapper.readValue(session.getState(), ChatState.class);
            return st.getSchemaVersion() == ChatState.SCHEMA_VERSION ? st : null;
        } catch (JsonProcessingException e) {
            log.warn("대화 상태 읽기 실패 session={}: {}", session.getId(), e.getMessage());
            return null;
        }
    }

    private String write(ChatState st) {
        try {
            return objectMapper.writeValueAsString(st);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("대화 상태 저장 실패", e);
        }
    }

    /** 동시 수정은 일정 편집 오류가 아니라 대화 충돌로 알린다. 프론트는 다시 불러온다. */
    private <T> T inTx(Supplier<T> work) {
        try {
            return transactionTemplate.execute(s -> work.get());
        } catch (ConcurrencyFailureException e) {
            throw conflict();
        }
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.TRIP_CHAT_CONFLICT);
    }

    private static BusinessException stale() {
        return new BusinessException(ErrorCode.TRIP_CHAT_CONFLICT, "질문이 바뀌었어요. 최신 내용으로 다시 불러올게요.");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private record Matches(String signature, Map<String, PlaceMatch> byKey) {}

    private record Prepared(TripChatResponse view, OpenQuestion question, String context, String state) {
        static Prepared done(TripChatResponse view) {
            return new Prepared(view, null, null, null);
        }

        static Prepared interpret(OpenQuestion question, String context, String state) {
            return new Prepared(null, question, context, state);
        }
    }

    private record Confirming(TripChatResponse view, ItineraryGenerateRequest request) {
        static Confirming done(TripChatResponse view) {
            return new Confirming(view, null);
        }

        static Confirming start(ItineraryGenerateRequest request) {
            return new Confirming(null, request);
        }
    }
}
