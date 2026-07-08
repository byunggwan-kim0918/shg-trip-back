package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.shg.trip.shgtrip.domain.planning.dto.AssemblyItineraryOutput;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
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

        log.info("AssemblyCallGenerator 완료: title='{}', steps={}, inputTokens={}, outputTokens={}",
                result.title(),
                result.steps() != null ? result.steps().size() : 0,
                message.usage().inputTokens(),
                message.usage().outputTokens());

        return result;
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
        sb.append("- 여행지: ").append(input.normalizedDestination()).append("\n\n");

        sb.append("## 확정된 일정 (순서·시간 변경 절대 불가, story만 작성)\n");
        for (StepData step : fixedSteps) {
            sb.append("[stepOrder ").append(step.stepOrder()).append("] ")
                    .append(step.dayNumber()).append("일차 ")
                    .append(step.startTime()).append("-").append(step.endTime()).append(" ")
                    .append(step.place().name())
                    .append(" (").append(step.place().region()).append(")\n");
        }

        sb.append("\n주어진 concept을 관통하는 하나의 여행 이야기로서, 각 stepOrder에 대해 ")
                .append("장소 사이를 잇는 짧은 가이드북 문장(story)을 작성해 assemble_itinerary 도구를 호출하세요.\n")
                .append("일반적인 감성 문구가 아니라, 위 concept에 종속된 narrative여야 합니다.\n");

        return sb.toString();
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
