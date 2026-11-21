package com.shg.trip.shgtrip.domain.tripchat.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatAnswerRequest;
import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatResponse;
import com.shg.trip.shgtrip.domain.tripchat.entity.TripChatSession;
import com.shg.trip.shgtrip.domain.tripchat.model.ChatState;
import com.shg.trip.shgtrip.domain.tripchat.model.Slot;
import com.shg.trip.shgtrip.domain.tripchat.model.Turn;
import com.shg.trip.shgtrip.domain.tripchat.repository.TripChatSessionRepository;
import com.shg.trip.shgtrip.domain.tripchat.service.DraftFolder;
import com.shg.trip.shgtrip.domain.tripchat.service.TripChatService;
import com.shg.trip.shgtrip.domain.tripchat.service.TripRequestMapper;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 적어 둔 여행자 답으로 대화를 실제 서비스 경로대로 진행하고, 확인 직전의 생성 요청을 JSON 줄로 남긴다.
 * 결과는 {@code DraftEvalRunner}의 {@code --eval.draft-requests}로 그대로 넣는다.
 *
 * <p>{@code --eval.mode=chat --eval.conversations=<대화 JSON 줄> --eval.user-id=<id> --eval.out=<폴더>}.
 * 대화 한 줄은 {@code {"id", "answers": {"CITY": "...", "FOLLOW_UP:DATES": ["..."], "FOLLOW_UP": "...", "FIX:DATES": "..."},
 * "review": [{"extra": "..."}, {"edit": "MOOD", "text": "..."}], "expect": {...}}}이다.
 * 적어 둔 답이 없는 질문은 버튼이 있으면 누르고, 묻지 않고 넘어간 항목의 답은 확인 화면에서 덧붙인다.
 * 거절된 답(욕설·뜻 모를 말)은 같은 질문에 적어 둔 다음 답으로 이어 가고, 대화가 잠기면 멈춘다.
 */
@Slf4j
@Component
@Profile("eval")
@RequiredArgsConstructor
class ChatEvalRunner implements CommandLineRunner {

    private static final int MAX_STEPS = 30;
    private static final String NO_IDEA = "음 그건 잘 모르겠어요";
    /** 글 답은 분당 20회 제한을 받는다. */
    private static final long TEXT_ANSWER_GAP_MS = 3500;

    @Value("${eval.mode:trip}")
    private String mode;

    @Value("${eval.conversations:}")
    private String conversations;

    /** 이 사용자의 진행 중 대화를 보관하고 새로 시작한다. 실제 사용자와 겹치지 않는 id를 직접 고른다. */
    @Value("${eval.user-id:}")
    private Long userId;

    @Value("${eval.out:build/eval-chat}")
    private String outDir;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.data.redis.host}")
    private String redisHost;

    private final List<Map<String, String>> rejected = new ArrayList<>();
    private boolean locked;

    private final TripChatService chat;
    private final StringRedisTemplate redisTemplate;
    private final TripChatSessionRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApplicationContext context;

    @Override
    public void run(String... args) throws IOException {
        if (!"chat".equals(mode)) return;
        if (conversations.isBlank()) throw new IllegalArgumentException("--eval.conversations=<대화 JSON 줄>이 필요하다");
        if (userId == null) throw new IllegalArgumentException("--eval.user-id=<대화를 진행할 사용자 id>가 필요하다");
        // 사용자의 대화를 보관하고 경고·호출 횟수를 지우므로 운영 저장소를 가리키면 실제 사용자의 잠금이 풀린다.
        if (!isLocal(datasourceUrl) || !isLocal(redisHost)) {
            throw new IllegalStateException("대화 평가는 로컬 DB·Redis에서만 돌린다: " + datasourceUrl + ", " + redisHost);
        }
        Path out = Path.of(outDir);
        Files.createDirectories(out);
        List<String> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(conversations), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode conv = objectMapper.readTree(line);
            try {
                rows.add(objectMapper.writeValueAsString(converse(conv)));
            } catch (RuntimeException e) {
                log.warn("대화 평가 실패 {}: {}", conv.path("id").asText(), e.toString());
            }
        }
        Path file = out.resolve("requests.jsonl");
        Files.writeString(file, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        log.info("대화 평가 {}건 → {}", rows.size(), file);
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private Map<String, Object> converse(JsonNode conv) throws IOException {
        String id = conv.get("id").asText();
        Map<String, Deque<String>> answers = answersOf(conv.path("answers"));
        Deque<JsonNode> review = new ArrayDeque<>();
        conv.path("review").forEach(review::add);
        // 대화마다 경고·호출 횟수를 비워 앞 대화의 욕설 경고가 다음 대화를 잠그지 않게 한다.
        redisTemplate.delete(List.of("tripchat:abuse:" + userId, "tripchat:rate:min:" + userId,
                "tripchat:rate:day:" + userId));
        chat.archiveCurrent(userId);
        TripChatResponse view = chat.create(userId, UUID.randomUUID().toString());
        List<String> unscripted = new ArrayList<>();
        List<String> late = new ArrayList<>();
        rejected.clear();
        locked = false;
        List<Map<String, String>> asked = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int step = 0; step < MAX_STEPS && !locked; step++) {
            if ("ASKING".equals(view.status())) {
                TripChatResponse.Question q = view.question();
                if (seen.add(q.id() + "|" + q.lead())) {
                    Map<String, String> a = new LinkedHashMap<>();
                    a.put("kind", q.kind());
                    a.put("slot", q.slot());
                    a.put("title", q.title());
                    a.put("lead", q.lead());
                    asked.add(a);
                }
                boolean base = q.kind().equals("BASE") || q.kind().equals("EDIT");
                String text = poll(answers, base ? q.slot() : q.kind() + ":" + q.slot());
                if (text == null && "FOLLOW_UP".equals(q.kind())) text = poll(answers, "FOLLOW_UP");
                if (text != null) {
                    view = answer(view, q.id(), "text", text);
                } else if (q.tool() != null) {
                    view = answer(view, q.id(), "UNKNOWN".equals(q.tool()) ? "unknown" : "none", null);
                } else {
                    unscripted.add(q.kind() + ":" + q.slot() + " " + q.title());
                    view = answer(view, q.id(), "text", NO_IDEA);
                }
                continue;
            }
            if (!"REVIEW".equals(view.status())) break;
            List<TripChatResponse.Fix> fixes = view.summary().fixes();
            if (!fixes.isEmpty()) {
                TripChatResponse.Fix fix = fixes.get(0);
                String text = poll(answers, "FIX:" + fix.slot());
                if (text == null) {
                    unscripted.add("FIX:" + fix.slot() + " " + fix.title());
                    break;
                }
                view = answer(view, fix.questionId(), "text", text);
                continue;
            }
            String unasked = pollUnasked(answers);
            if (unasked != null) {
                late.add(unasked);
                view = answer(view, TripChatService.EXTRA_QUESTION_ID, "text", unasked);
                continue;
            }
            JsonNode action = review.poll();
            if (action == null) break;
            if (action.has("edit")) {
                Slot slot = Slot.valueOf(action.get("edit").asText());
                answers.computeIfAbsent(slot.name(), k -> new ArrayDeque<>()).addFirst(action.get("text").asText());
                view = chat.edit(userId, UUID.fromString(view.id()), view.version(), slot);
            } else {
                view = answer(view, TripChatService.EXTRA_QUESTION_ID, "text", action.get("extra").asText());
            }
        }

        TripChatSession session = repository.findByIdAndUserId(UUID.fromString(view.id()), userId).orElseThrow();
        ChatState st = objectMapper.readValue(session.getState(), ChatState.class);
        TripRequestMapper.Result mapping = TripRequestMapper.map(DraftFolder.fold(st.getEvents()), st.getMatches(),
                LocalDate.now(clock));
        chat.archive(userId, session.getId());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("split", "chat");
        row.put("request", mapping.request());
        row.put("expect", conv.path("expect"));
        row.put("status", view.status());
        row.put("blockers", mapping.blockers().stream().map(TripRequestMapper.Blocker::key).toList());
        row.put("unscripted", unscripted);
        row.put("late", late);
        row.put("asked", asked);
        row.put("rejected", new ArrayList<>(rejected));
        row.put("locked", locked);
        row.put("haikuCalls", st.getHaikuCalls());
        List<Map<String, String>> turns = new ArrayList<>();
        for (Turn t : st.getTurns()) turns.add(Map.of("q", t.question(), "a", t.answer(), "kind", t.answerKind()));
        row.put("turns", turns);
        row.put("events", st.getEvents());
        log.info("대화 {} {} 턴 {} haiku {} 막힘 {}", id, view.status(), turns.size(), st.getHaikuCalls(),
                row.get("blockers"));
        return row;
    }

    /** jdbc URL이면 호스트 부분만 본다. 쿼리에 localhost가 든 원격 주소를 로컬로 보지 않는다. */
    private static boolean isLocal(String target) {
        java.util.regex.Matcher m = JDBC_HOST.matcher(target);
        String host = m.find() ? m.group(1) : target;
        return host.equals("localhost") || host.equals("127.0.0.1");
    }

    private static final java.util.regex.Pattern JDBC_HOST = java.util.regex.Pattern.compile("^jdbc:[a-z]+://([^:/?]+)");

    private TripChatResponse answer(TripChatResponse view, String questionId, String kind, String text) {
        TripChatAnswerRequest req = new TripChatAnswerRequest(questionId, UUID.randomUUID().toString(), kind, text);
        if ("text".equals(kind)) sleep(TEXT_ANSWER_GAP_MS);
        try {
            return chat.answer(userId, UUID.fromString(view.id()), req);
        } catch (BusinessException e) {
            ErrorCode code = e.getErrorCode();
            if (code == ErrorCode.TRIP_CHAT_NOT_UNDERSTOOD || code == ErrorCode.TRIP_CHAT_INAPPROPRIATE
                    || code == ErrorCode.TRIP_CHAT_LOCKED) {
                rejected.add(Map.of("text", String.valueOf(text), "code", code.getCode(), "message", e.getMessage()));
                locked = code == ErrorCode.TRIP_CHAT_LOCKED;
                return chat.current(userId);
            }
            log.warn("대화 답 실패, 한 번 더 보낸다: {}", e.getMessage());
            return chat.answer(userId, UUID.fromString(view.id()), req);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, Deque<String>> answersOf(JsonNode node) {
        Map<String, Deque<String>> out = new HashMap<>();
        node.fields().forEachRemaining(e -> {
            Deque<String> q = new ArrayDeque<>();
            if (e.getValue().isArray()) e.getValue().forEach(v -> q.add(v.asText()));
            else q.add(e.getValue().asText());
            out.put(e.getKey(), q);
        });
        return out;
    }

    private static String pollUnasked(Map<String, Deque<String>> answers) {
        for (Slot slot : Slot.ASK_ORDER) {
            String text = poll(answers, slot.name());
            if (text != null) return text;
        }
        return null;
    }

    private static String poll(Map<String, Deque<String>> answers, String key) {
        Deque<String> q = answers.get(key);
        return q == null ? null : q.poll();
    }
}
