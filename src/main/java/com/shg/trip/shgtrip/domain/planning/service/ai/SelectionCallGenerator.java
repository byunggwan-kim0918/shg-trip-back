package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
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

        // max_tokens 초과 시 50% 증가하여 1회 재시도
        if (isTruncated(message)) {
            int retryMaxTokens = (int) (maxTokens * 1.5);
            retryMaxTokens = Math.min(retryMaxTokens, anthropicProperties.maxOutputTokens());
            log.warn("선택 응답 truncated (max_tokens={}). 50% 증가하여 재시도: retryMaxTokens={}",
                    maxTokens, retryMaxTokens);

            message = callSonnet(userMessage, selectionTool, retryMaxTokens);

            if (isTruncated(message)) {
                log.error("선택 재시도 후에도 truncated (retryMaxTokens={})", retryMaxTokens);
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 선택 응답이 토큰 한도로 잘렸습니다.");
            }
        }

        // Tool Use 응답에서 SelectionOutput 추출
        SelectionOutput result = message.content().stream()
                .filter(ContentBlock::isToolUse)
                .findFirst()
                .map(block -> block.asToolUse()._input().convert(SelectionOutput.class))
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 응답에서 장소 선택 데이터를 추출할 수 없습니다."));

        log.info("SelectionCallGenerator 완료: days={}, dayCount={}, spareCount={}, inputTokens={}, outputTokens={}",
                days,
                result.days() != null ? result.days().size() : 0,
                result.spareIndices() != null ? result.spareIndices().size() : 0,
                message.usage().inputTokens(),
                message.usage().outputTokens());

        return result;
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
        sb.append("- 예산: ").append(input.budget().toPlainString()).append("원\n");
        sb.append("- 기간: ").append(input.startDate()).append(" ~ ").append(input.endDate())
                .append(" (").append(days - 1).append("박").append(days).append("일)\n");
        sb.append("- 여행 일수: ").append(days).append("일 (마지막날 dayNumber=").append(days).append(")\n");
        sb.append("- 페이스: ").append(input.pace()).append("\n");
        sb.append("- 이동수단 선호: ").append(input.transportPref())
                .append(" (walk=도보/버스로 갈 만한 가까운 동선, car=차량 이동 전제로 넉넉한 동선, any=상관없음)\n");
        if (input.description() != null && !input.description().isBlank()) {
            sb.append("- 요청사항: ").append(input.description()).append("\n");
        }

        sb.append("\n## 후보 장소 목록 (").append(candidates.size()).append("개)\n");
        sb.append("형식: ID | 이름 | 카테고리 | #태그 | 지역\n");
        for (PlaceCandidate candidate : candidates) {
            sb.append(candidate.index()).append(" | ")
                    .append(candidate.name()).append(" | ")
                    .append(summarizeCategory(candidate.category())).append(" | ")
                    .append(formatTags(candidate.tags())).append(" | ")
                    .append(candidate.region() != null ? candidate.region() : "")
                    .append("\n");
        }

        sb.append("\nconcept을 먼저 정의한 뒤, select_places 도구를 호출하여 날짜별 장소를 선택하세요.\n");

        return sb.toString();
    }

    /**
     * Foursquare 풀 경로에서 리프 노드만 추출하여 토큰을 절감한다.
     * "Dining and Drinking > Restaurant > Korean Restaurant" → "Korean Restaurant"
     */
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
}
