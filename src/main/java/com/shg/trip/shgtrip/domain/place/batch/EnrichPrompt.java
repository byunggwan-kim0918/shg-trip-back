package com.shg.trip.shgtrip.domain.place.batch;

import java.util.List;
import java.util.Map;

/**
 * 태그·설명 보강(v1) 요청 한 건. 규칙은 장소와 무관해 system에 두고 캐시한다. 장소 정보만 user로 보낸다.
 *
 * @param system 응답 형식·규칙. 같은 프롬프트 갈래(국내·해외, 업종, 물을 항목)끼리 글자까지 같아야 캐시가 맞는다
 * @param user   장소 정보
 */
public record EnrichPrompt(String system, String user) {

    public static final int MAX_TOKENS = 1024;

    Map<String, Object> requestParams(String model) {
        return Map.of(
                "model", model,
                "max_tokens", MAX_TOKENS,
                // Haiku 5.5는 thinking이 기본으로 켜져 max_tokens를 잡아먹고 JSON이 잘린다.
                "thinking", Map.of("type", "disabled"),
                "system", List.of(Map.of(
                        "type", "text",
                        "text", system,
                        "cache_control", Map.of("type", "ephemeral"))),
                "messages", List.of(Map.of("role", "user", "content", user)));
    }
}
