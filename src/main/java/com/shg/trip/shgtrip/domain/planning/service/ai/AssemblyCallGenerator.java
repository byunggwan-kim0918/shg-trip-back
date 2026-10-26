package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.shg.trip.shgtrip.domain.planning.dto.AssemblyItineraryOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * Call 2 Haiku: 백엔드(RouteOptimizer)가 day·순서·시간·교통·대안을 전부 확정한 일정 위에,
 * Sonnet이 정의한 concept을 반영한 story 텍스트만 생성한다. 순서/시간은 절대 변경하지 않음
 * (스키마에 구조 필드 자체가 없음). 비동기로 호출되며 critical path 밖에서 동작한다.
 */
@Slf4j
@Service
public class AssemblyCallGenerator {

    private static final int MAX_RETRIES = 2;

    private final AnthropicClient anthropicClient;
    private final AnthropicProperties anthropicProperties;
    private final String systemPrompt;

    public AssemblyCallGenerator(AnthropicClient anthropicClient,
                                 AnthropicProperties anthropicProperties) {
        this.anthropicClient = anthropicClient;
        this.anthropicProperties = anthropicProperties;
        this.systemPrompt = loadPromptTemplate("prompts/assemble-itinerary.txt");
    }

    /**
     * Call 2: Haiku가 확정된 일정 위에 concept을 반영한 story를 생성한다.
     * maxTokens = steps × 80 + 300 (구조 필드가 없어 매우 가벼움).
     *
     * @param fixedSteps 백엔드가 day·순서·시간·교통·대안을 확정한 최종 step 목록
     * @param concept    Sonnet이 정의한 여행 전체 컨셉
     * @param input      여행 정보 (확장된 enrichInput 결과)
     */
    public AssemblyItineraryOutput assembleItinerary(List<StepData> fixedSteps, String concept,
                                                      VectorEnrichedInput input) {
        int maxTokens = calculateMaxTokens(fixedSteps.size());
        log.info("AssemblyCallGenerator.assembleItinerary: steps={}, maxTokens={}", fixedSteps.size(), maxTokens);

        String userMessage = buildUserMessage(fixedSteps, concept, input);

        Tool assemblyTool = AssemblyToolSchema.buildAssemblyTool();

        Message message = callHaiku(userMessage, assemblyTool, maxTokens);

        if (isTruncated(message)) {
            int retryMaxTokens = (int) (maxTokens * 1.5);
            retryMaxTokens = Math.min(retryMaxTokens, anthropicProperties.maxOutputTokens());
            log.warn("스토리 응답 truncated (max_tokens={}). 50% 증가하여 재시도: retryMaxTokens={}",
                    maxTokens, retryMaxTokens);

            message = callHaiku(userMessage, assemblyTool, retryMaxTokens);

            if (isTruncated(message)) {
                log.error("스토리 재시도 후에도 truncated (retryMaxTokens={})", retryMaxTokens);
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 스토리 응답이 토큰 한도로 잘렸습니다.");
            }
        }

        AssemblyItineraryOutput result = message.content().stream()
                .filter(ContentBlock::isToolUse)
                .findFirst()
                .map(block -> block.asToolUse()._input().convert(AssemblyItineraryOutput.class))
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 응답에서 스토리 데이터를 추출할 수 없습니다."));

        log.info("AssemblyCallGenerator 완료: title='{}', steps={}, inputTokens={}, outputTokens={}, 캐시읽음/쓰기={}",
                result.title(),
                result.steps() != null ? result.steps().size() : 0,
                message.usage().inputTokens(),
                message.usage().outputTokens(),
                cacheInfo(message));

        return result;
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

    /** 구조 필드가 없어 매우 가벼움: step당 대략 80토큰 가정. */
    int calculateMaxTokens(int stepCount) {
        int estimated = stepCount * 80 + 300;
        return Math.min(estimated, anthropicProperties.maxOutputTokens());
    }

    private Message callHaiku(String userMessage, Tool tool, int maxTokens) {
        TextBlockParam systemBlock = TextBlockParam.builder()
                .text(systemPrompt)
                .cacheControl(CacheControlEphemeral.builder().build())
                .build();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(anthropicProperties.haiku())
                .maxTokens((long) maxTokens)
                .systemOfTextBlockParams(List.of(systemBlock))
                .addUserMessage(userMessage)
                .addTool(tool)
                .toolToolChoice("assemble_itinerary")
                .build();

        return executeWithRetry(() -> anthropicClient.messages().create(params), MAX_RETRIES);
    }

    private boolean isTruncated(Message message) {
        return message.stopReason().isPresent()
                && String.valueOf(message.stopReason().get()).contains("max_tokens");
    }

    /**
     * 확정된 일정(장소·순서·시간·지역) + concept을 컨텍스트로 제공.
     * 출력은 stepOrder별 story뿐 — 순서/시간을 바꿀 수 있는 필드가 스키마에 없음.
     */
    String buildUserMessage(List<StepData> fixedSteps, String concept, VectorEnrichedInput input) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 여행 컨셉\n").append(concept).append("\n\n");
        sb.append("## 여행 정보\n");
        sb.append("- 여행지: ").append(input.normalizedDestination()).append("\n");
        if (input.themes() != null && !input.themes().isEmpty()) {
            sb.append("- 테마: ").append(String.join(", ", input.themes())).append("\n");
        }
        // 계절·시즌은 story가 "지금이 어느 계절인지"를 지어내지 않도록 입력으로 명시한다.
        if (input.seasonContext() != null && !input.seasonContext().isBlank()) {
            sb.append("- 시즌: ").append(input.seasonContext()).append("\n");
        }
        sb.append("\n");

        sb.append("## 확정된 일정 (순서·시간 변경 절대 불가, story만 작성)\n");
        sb.append("형식: [stepOrder N] D일차(날짜, 요일) 시작-종료 장소명 (세부지역) · 역할 · 카테고리 · 설명\n");
        for (StepData step : fixedSteps) {
            sb.append("[stepOrder ").append(step.stepOrder()).append("] ")
                    .append(step.dayNumber()).append("일차");
            // 날짜·요일을 준다. 없으면 모델이 11:30 점심을 "아침을 깨우고"로 쓰고,
            // 18:43 호텔 체크인을 "야경"으로 쓴다(실측 79번).
            appendDayDate(sb, input, step.dayNumber());
            sb.append(" ").append(step.startTime()).append("-").append(step.endTime()).append(" ")
                    .append(step.place().name())
                    .append(" (").append(displayRegion(step)).append(")");
            // 슬롯 역할을 명시한다 — 같은 시각이라도 "점심 식사"와 "숙소 체크인"은 문장이 달라야 한다.
            String role = slotRole(step);
            if (role != null) sb.append(" · ").append(role);
            String category = leafCategory(step.place().category());
            if (category != null) sb.append(" · ").append(category);
            String description = shortDescription(step.place().description());
            if (description != null) sb.append(" · ").append(description);
            sb.append("\n");
        }

        appendSunsetHint(sb, input);
        sb.append("\n주어진 concept을 관통하는 하나의 여행 이야기로서, 각 stepOrder에 대해 ")
                .append("장소 사이를 잇는 짧은 가이드북 문장(story)을 작성해 assemble_itinerary 도구를 호출하세요.\n")
                .append("일반적인 감성 문구가 아니라, 위 concept에 종속된 narrative여야 합니다.\n");

        return sb.toString();
    }

    /** 표시 지역: 세부지역(시/군/구) 우선, 없으면 상위 region. */
    /** D일차의 실제 날짜와 요일. {@code startDate}가 없으면 아무것도 붙이지 않는다. */
    private void appendDayDate(StringBuilder sb, VectorEnrichedInput input, int dayNumber) {
        if (input == null || input.startDate() == null) return;
        java.time.LocalDate date = input.startDate().plusDays(dayNumber - 1L);
        String dow = date.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN);
        sb.append("(").append(date.getMonthValue()).append("월 ")
                .append(date.getDayOfMonth()).append("일 ").append(dow).append(")");
    }

    /**
     * 스텝의 역할(점심/저녁/숙소/도착·출발). 시각만 주면 모델이 역할을 추측하다 틀린다 —
     * 실측 79번: 11:30 점심을 "아침을 깨우고", 18:43 호텔 체크인을 "해변의 야경"으로 썼다.
     */
    private String slotRole(StepData step) {
        if (step.place() == null) return null;
        String category = step.place().category();
        if (PlaceCategoryConstants.isAccommodation(category)) return "숙소 체크인";
        if ("TRANSIT_HUB".equals(PlaceCategoryConstants.majorCategory(category))) return "교통 거점";
        int start = startMinutes(step.startTime());
        if (!"DINING".equals(PlaceCategoryConstants.majorCategory(category))) return null;
        if (start < 10 * 60 + 30) return "아침 식사";
        if (start < 15 * 60) return "점심 식사";
        return "저녁 식사";
    }

    /** "HH:mm" → 분. 파싱 실패 시 0. (ScheduleTimes는 패키지 전용이라 여기서 따로 둔다) */
    private int startMinutes(String hhmm) {
        if (hhmm == null || !hhmm.contains(":")) return 0;
        String[] parts = hhmm.split(":");
        try {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 일몰 시각 안내. 없으면 모델이 "해질녘", "야경"을 시각과 무관하게 쓴다
     * (실측 79번: 5월 제주 일몰은 19:30경인데 18:16을 "저녁이 물드는"으로 썼다).
     * 위도별 정밀 계산 대신 월별 대략값을 준다 — 틀린 단정을 막는 것이 목적이다.
     */
    private void appendSunsetHint(StringBuilder sb, VectorEnrichedInput input) {
        if (input == null || input.startDate() == null) return;
        int month = input.startDate().getMonthValue();
        String sunset = switch (month) {
            case 12, 1 -> "17시 20분경";
            case 11, 2 -> "17시 50분경";
            case 10, 3 -> "18시 20분경";
            case 4, 9 -> "18시 50분경";
            case 5, 8 -> "19시 20분경";
            default -> "19시 40분경";
        };
        sb.append("\n## 일몰\n- 이 시기 일몰은 ").append(sunset)
                .append("입니다. 그 이전 시각을 '해질녘·노을·야경'으로 쓰지 마세요.\n");
    }

    private String displayRegion(StepData step) {
        String sub = step.place().subRegion();
        if (sub != null && !sub.isBlank()) return sub;
        return step.place().region() != null ? step.place().region() : "";
    }

    /** Foursquare 계층 경로에서 리프만 (토큰 절감 + 장소 성격 전달). */
    private String leafCategory(String category) {
        if (category == null || category.isBlank()) return null;
        int lastArrow = category.lastIndexOf('>');
        String leaf = lastArrow >= 0 ? category.substring(lastArrow + 1).trim() : category.trim();
        return leaf.isEmpty() ? null : leaf;
    }

    /** 장소 설명 한 줄 — 너무 길면 잘라 토큰을 아낀다. URL만 있는 값은 버린다. */
    private String shortDescription(String description) {
        if (description == null || description.isBlank()) return null;
        String trimmed = description.trim().replaceAll("\\s+", " ");
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return null;
        return trimmed.length() > 60 ? trimmed.substring(0, 60) + "…" : trimmed;
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
}
