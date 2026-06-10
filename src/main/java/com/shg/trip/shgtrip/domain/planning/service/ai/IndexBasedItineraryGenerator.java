package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.domain.planning.dto.IndexBasedItineraryOutput;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

/**
 * 인덱스 기반 일정 생성기.
 * Sonnet 1회 호출로 후보 장소 인덱스를 사용한 일정을 생성한다.
 * system 메시지에 cache_control ephemeral을 적용하여 반복 호출 시 입력 토큰 비용 절감.
 *
 */
@Slf4j
@Service
public class IndexBasedItineraryGenerator {

    private static final int MAX_RETRIES = 2;

    private final AnthropicClient anthropicClient;
    private final AnthropicProperties anthropicProperties;
    private final String systemPrompt;

    public IndexBasedItineraryGenerator(AnthropicClient anthropicClient,
                                         AnthropicProperties anthropicProperties) {
        this.anthropicClient = anthropicClient;
        this.anthropicProperties = anthropicProperties;
        this.systemPrompt = loadPromptTemplate("prompts/index-based-generate.txt");
    }

    /**
     * 인덱스 기반 일정을 생성한다.
     * Sonnet 1회 호출, system 메시지 캐싱 적용.
     * maxTokens = days × 800 + 500. 응답이 잘리면(stop_reason=max_tokens) 50% 증가하여 1회 재시도.
     *
     * @param input 확장된 enrichInput 결과
     * @param candidates 벡터 검색 후보 장소 목록 (1-based 인덱스)
     * @return 인덱스 기반 일정 output
     */
    public IndexBasedItineraryOutput generate(VectorEnrichedInput input, List<PlaceCandidate> candidates) {
        long days = ChronoUnit.DAYS.between(input.startDate(), input.endDate()) + 1;
        int maxTokens = calculateMaxTokens(days);
        log.info("IndexBasedItineraryGenerator.generate: days={}, maxTokens={}, candidates={}",
                days, maxTokens, candidates.size());

        String userMessage = buildUserMessage(input, candidates, days);
        Tool tool = IndexBasedToolSchema.buildIndexBasedItineraryTool();

        // 첫 시도
        Message message = callSonnet(userMessage, tool, maxTokens);

        // max_tokens 초과 시 50% 증가하여 1회 재시도
        if (isTruncated(message)) {
            int retryMaxTokens = (int) (maxTokens * 1.5);
            retryMaxTokens = Math.min(retryMaxTokens, anthropicProperties.maxOutputTokens());
            log.warn("응답 truncated (max_tokens={}). 50% 증가하여 재시도: retryMaxTokens={}",
                    maxTokens, retryMaxTokens);

            message = callSonnet(userMessage, tool, retryMaxTokens);

            if (isTruncated(message)) {
                log.error("재시도 후에도 truncated (retryMaxTokens={})", retryMaxTokens);
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 응답이 토큰 한도로 잘렸습니다. 재시도 후에도 불완전합니다.");
            }
        }

        // Tool Use 응답에서 IndexBasedItineraryOutput 추출
        IndexBasedItineraryOutput result = message.content().stream()
                .filter(ContentBlock::isToolUse)
                .findFirst()
                .map(block -> block.asToolUse()._input().convert(IndexBasedItineraryOutput.class))
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 응답에서 인덱스 기반 일정 데이터를 추출할 수 없습니다."));

        log.info("IndexBasedItineraryGenerator 완료: title='{}', steps={}, inputTokens={}, outputTokens={}",
                result.title(),
                result.steps() != null ? result.steps().size() : 0,
                message.usage().inputTokens(),
                message.usage().outputTokens());

        return result;
    }

    /**
     * hard validation 실패 시 1회 재생성을 수행한다.
     * generate와 동일한 구조이나, 실패 원인을 사용자 메시지에 추가하여 AI가 규칙을 준수하도록 유도한다.
     * 이 메서드는 호출자가 최대 1회만 호출하며, 재생성 후에도 validation 실패 시 에러를 반환한다.
     *
     * @param input 확장된 enrichInput 결과
     * @param candidates 벡터 검색 후보 장소 목록 (1-based 인덱스)
     * @param failureReason hard validation 실패 원인
     * @return 인덱스 기반 일정 output
     *
     * Validates: Requirements 11.3
     */
    public IndexBasedItineraryOutput regenerate(VectorEnrichedInput input, List<PlaceCandidate> candidates, String failureReason) {
        long days = ChronoUnit.DAYS.between(input.startDate(), input.endDate()) + 1;
        int maxTokens = calculateMaxTokens(days);
        log.info("IndexBasedItineraryGenerator.regenerate: days={}, maxTokens={}, candidates={}, failureReason={}",
                days, maxTokens, candidates.size(), failureReason);

        String userMessage = buildUserMessage(input, candidates, days);
        userMessage += "\n이전 생성 결과가 검증에 실패했습니다. 실패 원인: " + failureReason + ". 규칙을 준수하여 다시 생성하세요.\n";

        Tool tool = IndexBasedToolSchema.buildIndexBasedItineraryTool();

        // 첫 시도
        Message message = callSonnet(userMessage, tool, maxTokens);

        // max_tokens 초과 시 50% 증가하여 1회 재시도
        if (isTruncated(message)) {
            int retryMaxTokens = (int) (maxTokens * 1.5);
            retryMaxTokens = Math.min(retryMaxTokens, anthropicProperties.maxOutputTokens());
            log.warn("재생성 응답 truncated (max_tokens={}). 50% 증가하여 재시도: retryMaxTokens={}",
                    maxTokens, retryMaxTokens);

            message = callSonnet(userMessage, tool, retryMaxTokens);

            if (isTruncated(message)) {
                log.error("재생성 재시도 후에도 truncated (retryMaxTokens={})", retryMaxTokens);
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 재생성 응답이 토큰 한도로 잘렸습니다. 재시도 후에도 불완전합니다.");
            }
        }

        // Tool Use 응답에서 IndexBasedItineraryOutput 추출
        IndexBasedItineraryOutput result = message.content().stream()
                .filter(ContentBlock::isToolUse)
                .findFirst()
                .map(block -> block.asToolUse()._input().convert(IndexBasedItineraryOutput.class))
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                        "AI 재생성 응답에서 인덱스 기반 일정 데이터를 추출할 수 없습니다."));

        log.info("IndexBasedItineraryGenerator.regenerate 완료: title='{}', steps={}, inputTokens={}, outputTokens={}",
                result.title(),
                result.steps() != null ? result.steps().size() : 0,
                message.usage().inputTokens(),
                message.usage().outputTokens());

        return result;
    }

    /**
     * maxTokens를 동적으로 계산한다.
     * 인덱스 기반이므로 기존(ClaudeAIService: days*6000)보다 절감: days × 3000 + 500.
     * ClaudeAIService와의 균형: 직접 생성 vs 인덱스 기반 = 6000 vs 3000
     */
    int calculateMaxTokens(long days) {
        int estimated = (int) (days * 3000) + 500;
        return Math.min(estimated, anthropicProperties.maxOutputTokens());
    }

    /**
     * Sonnet을 호출하여 Message를 반환한다.
     * system 메시지에 CacheControlEphemeral 적용.
     */
    private Message callSonnet(String userMessage, Tool tool, int maxTokens) {
        // system 메시지에 cache_control: ephemeral 적용
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
                .toolToolChoice("generate_itinerary")
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

        // 여행 정보 섹션
        sb.append("## 여행 정보\n");
        sb.append("- 여행지: ").append(input.normalizedDestination()).append("\n");
        sb.append("- 테마: ").append(String.join(", ", input.themes())).append("\n");
        if (input.categories() != null && !input.categories().isEmpty()) {
            sb.append("- 카테고리: ").append(String.join(", ", input.categories())).append("\n");
        }
        sb.append("- 예산: ").append(input.budget().toPlainString()).append("원 (")
                .append(input.budgetRange()).append(")\n");
        sb.append("- 기간: ").append(input.startDate()).append(" ~ ").append(input.endDate())
                .append(" (").append(days).append("일)\n");
        sb.append("- 페이스: ").append(input.pace()).append("\n");
        sb.append("- 시즌: ").append(input.seasonContext() != null ? input.seasonContext() : "")
                .append("\n");
        if (input.description() != null && !input.description().isBlank()) {
            sb.append("- 요청사항: ").append(input.description()).append("\n");
        }
        if (input.enrichedContext() != null && !input.enrichedContext().isBlank()) {
            sb.append("- 현지 정보: ").append(input.enrichedContext()).append("\n");
        }

        // 지역 배분 (있을 경우)
        if (input.regionAllocation() != null && !input.regionAllocation().isEmpty()) {
            sb.append("- 지역 배분:\n");
            input.regionAllocation().forEach((dayRange, regions) ->
                    sb.append("  - ").append(dayRange).append("일차: ")
                            .append(String.join(", ", regions)).append("\n")
            );
        }

        // 후보 장소 목록 섹션
        sb.append("\n## 후보 장소 목록\n");
        for (PlaceCandidate candidate : candidates) {
            sb.append(candidate.index()).append(". ")
                    .append(candidate.name())
                    .append(" [").append(candidate.category()).append("]")
                    .append(" (").append(candidate.region() != null ? candidate.region() : "").append(")")
                    .append(" lat:").append(formatCoord(candidate.latitude()))
                    .append(" lng:").append(formatCoord(candidate.longitude()));
            if (candidate.rating() != null && candidate.rating().compareTo(BigDecimal.ZERO) > 0) {
                sb.append(" ★").append(candidate.rating());
            }
            if (candidate.priceLevel() != null) {
                sb.append(" 가격:").append("$".repeat(candidate.priceLevel()));
            }
            if (candidate.openingHours() != null && !candidate.openingHours().isBlank()) {
                sb.append(" 영업:").append(candidate.openingHours());
            }
            if (candidate.description() != null && !candidate.description().isBlank()) {
                String desc = candidate.description();
                if (desc.length() > 80) desc = desc.substring(0, 80) + "…";
                sb.append(" | ").append(desc);
            }
            sb.append("\n");
        }

        // 도구 호출 지시
        sb.append("\ngenerate_itinerary 도구를 호출하여 인덱스 기반 일정을 생성하세요.\n");

        return sb.toString();
    }

    private String formatCoord(BigDecimal coord) {
        if (coord == null) return "N/A";
        return coord.stripTrailingZeros().toPlainString();
    }

    private String loadPromptTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 템플릿 로드 실패: " + path, e);
        }
    }

    /**
     * 재시도 로직. 최대 maxRetries회까지 재시도하며 exponential backoff 적용.
     */
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
