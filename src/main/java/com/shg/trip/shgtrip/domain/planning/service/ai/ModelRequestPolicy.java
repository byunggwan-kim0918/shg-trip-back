package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.ToolChoiceAuto;

/**
 * Claude 5.5 세대(Sonnet 5.5 / Haiku 5.5) 요청 공통 설정.
 */
final class ModelRequestPolicy {

    private ModelRequestPolicy() {}

    /**
     * Sonnet 5.5는 강제 tool_choice(any/tool)를 400으로 거부한다. auto로 두고 프롬프트의 "도구를 호출하라"
     * 지시로 유도한다 — 호출부는 tool_use 블록이 없으면 형식 오류로 보고 재시도한다.
     */
    static final ToolChoiceAuto SONNET_TOOL_CHOICE = ToolChoiceAuto.builder().build();

    /**
     * Sonnet 5.5는 thinking을 끌 수 없고(disabled는 400) 기본 effort가 high다. 선택·생성은 한 번의 도구 호출이라
     * low로 두어 지연을 줄인다.
     */
    static final OutputConfig SONNET_OUTPUT = OutputConfig.builder().effort(OutputConfig.Effort.LOW).build();

    /** thinking 토큰도 max_tokens 안에서 쓰인다. 응답 몫으로 계산한 상한에 이만큼 더한다. */
    static final int SONNET_THINKING_HEADROOM = 4000;

    /**
     * Haiku 5.5는 adaptive thinking이 기본으로 켜진다. 실시간 호출은 짧은 JSON·텍스트라 생각할 거리가 없고,
     * 켜두면 thinking 토큰이 작은 max_tokens를 먹어 JSON이 잘린다.
     */
    static final ThinkingConfigDisabled HAIKU_THINKING = ThinkingConfigDisabled.builder().build();

    static long sonnetMaxTokens(long replyTokens, int ceiling) {
        return Math.min(replyTokens + SONNET_THINKING_HEADROOM, ceiling);
    }
}
