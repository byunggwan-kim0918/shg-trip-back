package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.service.GeoCluster;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

/**
 * Call 1 Sonnet: 날짜별 장소 선택 전문 생성기.
 * Sonnet 1회 호출로 후보 장소 인덱스를 사용한 날짜별 선택을 수행한다.
 * 시간/교통/비용은 출력하지 않음 (가벼운 호출).
 */
@Slf4j
@Service
public class SelectionCallGenerator {

    private static final int MAX_RETRIES = 2;

    private final AnthropicClient anthropicClient;
    private final AnthropicProperties anthropicProperties;
    private final String systemPrompt;

    public SelectionCallGenerator(AnthropicClient anthropicClient,
                                  AnthropicProperties anthropicProperties) {
        this.anthropicClient = anthropicClient;
        this.anthropicProperties = anthropicProperties;
        this.systemPrompt = loadPromptTemplate("prompts/select-places.txt");
    }

    /**
     * Call 1: Sonnet이 날짜별 장소를 선택한다.
     * 장소 선택만 수행하고, 시간/교통/비용은 계산하지 않는다.
     * maxTokens = days × 150 + 300 (매우 가벼움).
     *
     * @param input 확장된 enrichInput 결과
     * @param candidates 벡터 검색 후보 장소 목록 (1-based 인덱스)
     * @return 날짜별 선택 출력 (DayPlan 목록 + spare 인덱스)
     */
    public SelectionOutput selectPlaces(VectorEnrichedInput input, List<PlaceCandidate> candidates) {
        long days = ChronoUnit.DAYS.between(input.startDate(), input.endDate()) + 1;
        int maxTokens = calculateMaxTokens(days);
        log.info("SelectionCallGenerator.selectPlaces: days={}, maxTokens={}, candidates={}",
                days, maxTokens, candidates.size());

        String userMessage = buildUserMessage(input, candidates, days);

        // Tool Use 생성
        Tool selectionTool = SelectionToolSchema.buildSelectionTool();

        // 첫 시도
        Message message = callSonnet(userMessage, selectionTool, maxTokens);
        int usedMaxTokens = maxTokens;

        // max_tokens 초과 시 50% 증가하여 1회 재시도
        if (isTruncated(message)) {
            int retryMaxTokens = (int) (maxTokens * 1.5);
            retryMaxTokens = Math.min(retryMaxTokens, anthropicProperties.maxOutputTokens());
            log.warn("선택 응답 truncated (max_tokens={}). 50% 증가하여 재시도: retryMaxTokens={}",
                    maxTokens, retryMaxTokens);

            message = callSonnet(userMessage, selectionTool, retryMaxTokens);
            usedMaxTokens = retryMaxTokens;

            if (isTruncated(message)) {
                log.error("선택 재시도 후에도 truncated (retryMaxTokens={})", retryMaxTokens);
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 선택 응답이 토큰 한도로 잘렸습니다.");
            }
        }

        // 모르는 필드는 무시하고 읽으므로(SelectionOutput), 필드명을 틀린 day는 예외 없이 빈 날이 된다.
        // 읽은 뒤 날짜 수와 placeIndices 유무를 확인하고, 어긋나면 한 번 다시 묻는다.
        SelectionOutput result = parseValid(message, days);
        if (result == null) {
            log.warn("선택 응답 형식 오류 — 1회 재호출 (버린 응답 inputTokens={}, outputTokens={})",
                    message.usage().inputTokens(), message.usage().outputTokens());
            message = callSonnet(userMessage, selectionTool, usedMaxTokens);
            result = isTruncated(message) ? null : parseValid(message, days);
            if (result == null) {
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI 선택 응답 형식이 올바르지 않습니다.");
            }
        }

        log.info("SelectionCallGenerator 완료: days={}, dayCount={}, spareCount={}, inputTokens={}, outputTokens={}, 캐시읽음/쓰기={}",
                days,
                result.days() != null ? result.days().size() : 0,
                result.spareIndices() != null ? result.spareIndices().size() : 0,
                message.usage().inputTokens(),
                message.usage().outputTokens(),
                cacheInfo(message));
        logSelection(result, candidates);

        return result;
    }

    /** 응답을 읽어 날짜 수와 각 날의 placeIndices가 갖춰졌으면 반환, 아니면 null. */
    private SelectionOutput parseValid(Message message, long days) {
        SelectionOutput out;
        try {
            out = extractSelection(message);
        } catch (IllegalArgumentException | BusinessException e) {
            log.warn("선택 응답 파싱 실패: {}", e.getMessage());
            return null;
        }
        if (out.days() == null || out.days().size() != days
                || out.days().stream().anyMatch(d -> d == null || d.placeIndices() == null)) {
            log.warn("선택 응답 구조 불일치: 기대 {}일, 받은 days={}", days, out.days() == null ? null : out.days().size());
            return null;
        }
        return out;
    }

    private SelectionOutput extractSelection(Message message) {
        return message.content().stream()
                .filter(ContentBlock::isToolUse)
                .findFirst()
                .map(block -> block.asToolUse()._input().convert(SelectionOutput.class))
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 응답에서 장소 선택 데이터를 추출할 수 없습니다."));
    }

    /**
     * Sonnet이 실제로 고른 결과를 이름으로 남긴다. 이게 없으면 나쁜 일정이 선택 단계에서 왔는지
     * RouteOptimizer의 보정에서 왔는지 가릴 수 없다(최종 일정과 비교해야 보정량이 보인다).
     */
    private void logSelection(SelectionOutput result, List<PlaceCandidate> candidates) {
        log.info("Sonnet 선택 concept: {}", result.concept());
        if (result.days() != null) {
            for (SelectionOutput.DayPlan day : result.days()) {
                log.info("Sonnet 선택 day={}: {}", day.dayNumber(), namesOf(day.placeIndices(), candidates));
            }
        }
        log.info("Sonnet 선택 spare: {}", namesOf(result.spareIndices(), candidates));
        log.info("Sonnet 선택 highlight: {} / timing: {}", namesOf(result.highlightIndices(), candidates),
                result.timing() == null ? "[]" : result.timing().stream()
                        .map(t -> namesOf(List.of(t.index()), candidates) + "=" + t.when())
                        .collect(java.util.stream.Collectors.joining(", ", "[", "]")));
    }

    private static String namesOf(List<Integer> indices, List<PlaceCandidate> candidates) {
        if (indices == null) return "[]";
        return indices.stream()
                .map(i -> i != null && i >= 1 && i <= candidates.size()
                        ? i + ":" + candidates.get(i - 1).name() : i + ":?")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    /**
     * maxTokens를 동적으로 계산한다.
     * 선택만 수행하므로 매우 가벼움: days × 150 + 300.
     */
    int calculateMaxTokens(long days) {
        int estimated = (int) (days * 150) + 300;
        return Math.min(estimated, anthropicProperties.maxOutputTokens());
    }

    /**
     * Sonnet을 호출하여 Message를 반환한다.
     * system 메시지에 CacheControlEphemeral 적용.
     */
    private Message callSonnet(String userMessage, Tool tool, int maxTokens) {
        TextBlockParam systemBlock = TextBlockParam.builder()
                .text(systemPrompt)
                .cacheControl(CacheControlEphemeral.builder().build())
                .build();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(anthropicProperties.sonnet())
                .maxTokens((long) maxTokens)
                .systemOfTextBlockParams(List.of(systemBlock))
                .addUserMessage(userMessage)
                .addTool(tool)
                .toolToolChoice("select_places")
                .build();

        return executeWithRetry(() -> anthropicClient.messages().create(params), MAX_RETRIES);
    }

    /**
     * 응답이 max_tokens로 잘렸는지 확인한다.
     */
    private boolean isTruncated(Message message) {
        return message.stopReason().isPresent()
                && String.valueOf(message.stopReason().get()).contains("max_tokens");
    }

    /**
     * 사용자 메시지를 빌드한다.
     * 여행 정보 + 번호 매긴 후보 장소 목록 + 도구 호출 지시.
     */
    String buildUserMessage(VectorEnrichedInput input, List<PlaceCandidate> candidates, long days) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 여행 정보\n");
        sb.append("- 여행지: ").append(input.normalizedDestination()).append("\n");
        sb.append("- 테마: ").append(String.join(", ", input.themes())).append("\n");
        if (input.categories() != null && !input.categories().isEmpty()) {
            sb.append("- 카테고리: ").append(String.join(", ", input.categories())).append("\n");
        }
        // 예산은 선택 입력 — null이면 프롬프트에서 생략 (categories·description과 동일 패턴)
        if (input.budget() != null) {
            sb.append("- 예산: ").append(input.budget().toPlainString()).append("원\n");
        }
        sb.append("- 기간: ").append(input.startDate()).append(" ~ ").append(input.endDate())
                .append(" (").append(days - 1).append("박").append(days).append("일)\n");
        sb.append("- 여행 일수: ").append(days).append("일 (마지막날 dayNumber=").append(days).append(")\n");
        // 요일을 준다 — 장이 서는 날·휴무 같은 판단은 요일을 알아야 한다.
        if (input.startDate() != null) {
            StringBuilder dows = new StringBuilder();
            for (int d = 0; d < days; d++) {
                java.time.LocalDate date = input.startDate().plusDays(d);
                if (d > 0) dows.append(", ");
                dows.append(d + 1).append("일차 ").append(date.getMonthValue()).append("/").append(date.getDayOfMonth())
                        .append("(").append(date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT,
                                java.util.Locale.KOREAN)).append(")");
            }
            sb.append("- 날짜별 요일: ").append(dows).append("\n");
        }
        // 도착·출발 허브 — 첫날은 도착지 근처에서, 마지막날은 출발지 쪽으로 끝나게 고를 수 있다.
        if (input.transportationHub() != null && input.transportationHub().arrivalHub() != null) {
            sb.append("- 도착/출발: ").append(input.transportationHub().arrivalHub());
            if (input.transportationHub().departureHub() != null
                    && !input.transportationHub().departureHub().equals(input.transportationHub().arrivalHub())) {
                sb.append(" → ").append(input.transportationHub().departureHub());
            }
            sb.append("\n");
        }
        sb.append("- 페이스: ").append(input.pace()).append("\n");
        sb.append("- 이동수단 선호: ").append(input.transportPref())
                .append(" (walk=도보/버스로 갈 만한 가까운 동선, car=차량 이동 전제로 넉넉한 동선, any=상관없음)\n");
        if (input.description() != null && !input.description().isBlank()) {
            sb.append("- 요청사항: ").append(input.description()).append("\n");
        }
        String companion = companionLabel(input);
        if (companion != null) {
            sb.append("- 동행: ").append(companion).append("\n");
        }

        // 좌표 기반 지리 구역. 주소에서 뽑은 세부지역은 제주에서 "제주시/서귀포시" 둘뿐이라
        // (각각 섬의 절반) "같은 지역으로 묶어라"가 사실상 아무 제약이 아니었다. GeoCluster 주석 참고.
        GeoCluster.Result geo = GeoCluster.of(candidates);
        if (geo.isEmpty()) {
            log.info("지리 구역 미적용 — 후보가 적거나 여행지가 좁다 (candidates={})", candidates.size());
        } else {
            log.info("지리 구역 {}개: {}", geo.zones().size(), geo.zones().stream()
                    .map(z -> z.label() + "(" + z.count() + ")").collect(java.util.stream.Collectors.joining(", ")));
        }

        sb.append("\n## 후보 장소 목록 (").append(candidates.size()).append("개)\n");
        sb.append("형식: ID | 이름 | 역할·카테고리 | 평점 · 인기도 | #태그 | ")
                .append(geo.isEmpty() ? "세부지역" : "구역 · 세부지역")
                .append(" | 체류시간 | 시간대 | 휴무 (값이 없는 항목은 생략됨)\n");
        boolean hasUserSelected = false;
        boolean hasVia = false;
        for (PlaceCandidate candidate : candidates) {
            if (candidate.userSelected()) {
                sb.append("★");
                hasUserSelected = true;
            }
            // 경유형(해안도로·드라이브코스·둘레길 등)은 "머무는 방문지"가 아니라 지나가는 구간이다.
            // 코드가 마킹해두고, 프롬프트가 전체 일정 최대 1개로 제한한다(B2).
            boolean via = PlaceCategoryConstants.isViaRoute(
                    candidate.name(), candidate.category(), candidate.tags());
            if (via) {
                sb.append("[경유]");
                hasVia = true;
            }
            sb.append(candidate.index()).append(" | ")
                    .append(candidate.name()).append(" | ")
                    // 역할(식사·카페·관광…)과 음식 종류를 앞에 붙인다 — 이름과 태그 4개만으로는 흑돼지집인지,
                    // 카페인지 알 수 없어 같은 종류 끼니가 반복됐다(실측 115~118).
                    .append(roleLabel(candidate)).append(summarizeCategory(candidate.category())).append(" | ")
                    .append(formatRating(candidate.rating(), candidate.wikidataSitelinks())).append(" | ")
                    .append(formatTags(candidate.tags())).append(" | ");
            String zone = geo.labelByIndex().get(candidate.index());
            if (zone != null) sb.append(zone).append(" · ");
            sb.append(candidate.displayRegion() != null ? candidate.displayRegion() : "");
            appendIfPresent(sb, formatDuration(candidate.recommendedDurationMinutes()));
            appendIfPresent(sb, formatTimeSlots(candidate.recommendedTimeSlots()));
            appendIfPresent(sb, formatClosedDay(candidate.openingHours()));
            sb.append("\n");
        }

        // 구역 간 실제 거리를 함께 준다. 라벨만 주면 "동부와 서부가 얼마나 먼지"를 모르는 채
        // 묶게 되고, 실측에서 그게 하루 155km로 나타났다.
        if (!geo.isEmpty()) {
            sb.append("\n### 지리 구역 (좌표 기준)\n");
            for (GeoCluster.Zone z : geo.zones()) {
                sb.append("- ").append(z.label()).append(": 후보 ").append(z.count()).append("곳\n");
            }
            List<GeoCluster.Zone> zs = geo.zones();
            StringBuilder pairs = new StringBuilder();
            for (int i = 0; i < zs.size(); i++) {
                for (int j = i + 1; j < zs.size(); j++) {
                    double km = geo.distanceKm(zs.get(i).label(), zs.get(j).label());
                    if (km < 1) continue;
                    if (pairs.length() > 0) pairs.append(", ");
                    pairs.append(zs.get(i).label()).append("↔").append(zs.get(j).label())
                            .append(" 약 ").append(Math.round(km)).append("km");
                }
            }
            if (pairs.length() > 0) {
                sb.append("구역 간 거리(중심 기준 직선): ").append(pairs).append("\n");
            }
            sb.append("**하루는 한 구역 안에서 묶으세요.** 구역을 건너뛰면 위 거리가 그대로 이동시간이 되고, ")
                    .append("그만큼 그날 관광 시간이 사라집니다. 구역 이동이 꼭 필요하면 하루에 한 번만, ")
                    .append("이동 중 들를 수 있는 장소를 사이에 두세요.\n");
        }

        if (hasVia) {
            sb.append("\n[경유] 표시 후보는 도로·산책길 같은 **경유 구간**입니다. 머무는 방문지가 아니므로 ")
                    .append("pairs나 동선 힌트로만 쓰고, placeIndices에 넣더라도 **전체 일정에서 최대 1개**까지만 넣으세요.\n");
        }

        if (hasUserSelected) {
            sb.append("\n★ 표시된 후보는 사용자가 직접 선택한 **필수 방문 장소**입니다. ")
                    .append("숙소(LODGING)면 백엔드가 숙소로 배정하므로 그대로 두고, 그 외에는 반드시 어느 날의 placeIndices에 포함하고 ")
                    .append("spareIndices에 넣지 마세요. 동선이 다소 불리해도 포함이 우선입니다.\n");
        }

        if (input.categories() != null && !input.categories().isEmpty()) {
            sb.append("\n사용자가 고른 카테고리(").append(String.join(", ", input.categories()))
                    .append(") 각각이 전체 일정에 **최소 1회**는 등장하도록 선택하세요. ")
                    .append("후보에 해당 유형이 아예 없으면 억지로 넣지 말고 넘어가세요.\n");
        }

        sb.append("\nconcept을 먼저 정의한 뒤, select_places 도구를 호출하여 날짜별 장소를 선택하세요.\n");

        return sb.toString();
    }

    /** 후보 줄의 역할 표시. 숙소·교통은 카테고리로 충분해 생략한다. */
    static String roleLabel(PlaceCandidate c) {
        com.shg.trip.shgtrip.domain.planning.service.CandidatePolicy.Role role =
                com.shg.trip.shgtrip.domain.planning.service.CandidatePolicy.roleOf(c);
        String base = switch (role) {
            case MEAL -> "식사";
            case CAFE -> "카페·디저트";
            case BAR -> "술집";
            case SIGHT -> "관광";
            case ACTIVITY -> "체험";
            case SHOPPING -> "쇼핑";
            case MARKET -> "시장";
            default -> null;
        };
        if (base == null) return "";
        if (role == com.shg.trip.shgtrip.domain.planning.service.CandidatePolicy.Role.MEAL
                && c.cuisine() != null && CUISINE_KO.containsKey(c.cuisine())) {
            base += "·" + CUISINE_KO.get(c.cuisine());
        }
        return "[" + base + "] ";
    }

    private static final java.util.Map<String, String> CUISINE_KO = java.util.Map.of(
            "MEAT", "고기", "SEAFOOD", "해산물", "NOODLE", "면", "SOUP", "국물", "KOREAN_SET", "한식",
            "JAPANESE", "일식", "CHINESE", "중식", "WESTERN", "양식", "SNACK", "분식");

    /** 동행 표시 문구. 모르면 null(줄 자체를 생략). */
    private static String companionLabel(VectorEnrichedInput input) {
        String base = switch (input.companion()) {
            case SOLO -> "혼자";
            case COUPLE -> "커플";
            case FAMILY -> "가족";
            case FRIENDS -> "친구";
            case UNKNOWN -> null;
        };
        if (base == null) return null;
        return input.partySize() != null ? base + "(" + input.partySize() + "인)" : base;
    }

    /** 값이 있을 때만 " | 값"으로 덧붙인다(없는 항목은 생략 — 토큰 절감). */
    private void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) sb.append(" | ").append(value);
    }

    /** 권장 체류시간(분). 없으면 null. */
    private String formatDuration(Integer minutes) {
        return minutes != null && minutes > 0 ? minutes + "분" : null;
    }

    /** 추천 시간대 — 영어 어휘를 한국어 한 단어로. 없으면 null. */
    private String formatTimeSlots(List<String> slots) {
        if (slots == null || slots.isEmpty()) return null;
        return slots.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> switch (s.trim().toLowerCase()) {
                    case "morning" -> "오전";
                    case "afternoon" -> "오후";
                    case "evening" -> "저녁";
                    case "night" -> "야간";
                    case "all_day" -> "종일";
                    default -> s.trim();
                })
                .distinct()
                .limit(2)
                .collect(java.util.stream.Collectors.joining("/"));
    }

    /**
     * 영업시간 문자열에서 정기휴무 요일을 뽑아 "목휴무" 형태로. 고신뢰 신호(휴무/closed)가 있는
     * 줄에서 요일을 찾고, 없으면 null(RouteOptimizer.repairClosedDayPlaces와 같은 보수적 기준).
     */
    private String formatClosedDay(String openingHours) {
        if (openingHours == null || openingHours.isBlank()) return null;
        String lower = openingHours.toLowerCase();
        if (!lower.contains("휴무") && !lower.contains("closed") && !lower.contains("정기휴")) return null;

        String[] korean = {"월", "화", "수", "목", "금", "토", "일"};
        String[] english = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
        for (String line : openingHours.split("[\\r\\n,]")) {
            String l = line.toLowerCase();
            if (!l.contains("휴무") && !l.contains("closed")) continue;
            for (int i = 0; i < korean.length; i++) {
                if (line.contains(korean[i] + "요일") || line.contains(korean[i] + "휴")
                        || l.contains(english[i])) {
                    return korean[i] + "휴무";
                }
            }
        }
        return null;
    }

    /**
     * Foursquare 풀 경로에서 리프 노드만 추출하여 토큰을 절감한다.
     * "Dining and Drinking > Restaurant > Korean Restaurant" → "Korean Restaurant"
     */
    /**
     * 평점과 인기도를 "⭐4.4 · 인기도 높음" 형태로.
     *
     * <p>인기도 신호가 없으면 모델이 평점만 보고 무명 장소를 명소보다 위에 둔다(실측: 바늘오름
     * ⭐4.6이 용담해안도로 ⭐4.3을 이겨 일정에 들어갔다). 신호는 Wikidata 언어판 수인데,
     * 숫자를 그대로 주면 모델이 척도를 모른다 — 46과 16 중 뭐가 큰지는 알아도 "16이 명소인가"는
     * 판단하지 못한다. 그래서 등급 문구로 바꿔 준다.
     *
     * <p>실측 분포(제주 750건): 중간값 1, 83%가 1~2, p95가 5, p99가 28. 그래서 경계를
     * 10/3/1에 둔다 — 한라산 46·성산일출봉 16이 "매우 높음", 만장굴 11이 "높음"이다.
     */
    private String formatRating(java.math.BigDecimal rating, Integer sitelinks) {
        String base = rating != null ? "⭐" + rating.toPlainString() : "평점없음";
        String fame = formatFame(sitelinks);
        return fame == null ? base : base + " · " + fame;
    }

    /** sitelinks를 모델이 읽을 수 있는 등급으로. null(미매칭)이면 아무 말도 하지 않는다. */
    private String formatFame(Integer sitelinks) {
        if (sitelinks == null) return null;
        if (sitelinks >= 10) return "인기도 매우 높음";
        if (sitelinks >= 3) return "인기도 높음";
        if (sitelinks >= 1) return "인기도 보통";
        return "인기도 낮음";
    }

    private String formatTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "";
        return tags.stream()
                .limit(4)
                .map(t -> "#" + t)
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private String summarizeCategory(String category) {
        if (category == null || category.isBlank()) return "기타";
        int lastArrow = category.lastIndexOf(">");
        return lastArrow >= 0 ? category.substring(lastArrow + 1).trim() : category;
    }

    private String loadPromptTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 템플릿 로드 실패: " + path, e);
        }
    }

    private <T> T executeWithRetry(Supplier<T> action, int maxRetries) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (com.anthropic.errors.InternalServerException e) {
                attempt++;
                if (attempt > maxRetries) {
                    throw e;
                }
                long waitMs = 1000L * attempt;
                log.warn("Anthropic API 일시 오류 (시도 {}/{}), {}ms 후 재시도: {}",
                        attempt, maxRetries, waitMs, e.getMessage());
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * 프롬프트 캐시 적중 현황을 "읽음/쓰기" 형태로 포맷한다.
     *
     * <p>적중 없는 캐시는 쓰기 프리미엄(1.25배)만 내고 이득이 없다. 생성이 희박해서 기본 TTL
     * 안에 재사용되지 않으면 캐시가 순손실인데, 지금은 적중률 로그가 없어 이득인지 손실인지
     * 판단할 수 없었다. 읽음이 계속 0이면 캐시를 떼는 게 맞다.
     */
    private String cacheInfo(Message message) {
        long read = message.usage().cacheReadInputTokens().orElse(0L);
        long write = message.usage().cacheCreationInputTokens().orElse(0L);
        return read + "/" + write;
    }
}
