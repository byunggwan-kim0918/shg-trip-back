package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 배로만 갈 수 있는 부속섬(예: 제주의 우도) 장소를 후보에서 기본 제외한다.
 *
 * <p>스케줄러는 모든 구간을 차량 이동으로 계산하므로, 섬 장소가 하나 끼면 배 왕복·대기(반나절)가
 * 통째로 빠진 일정이 된다(실측 115: 별방진 → 우도 식당 → 성산일출봉이 각 13분). 반나절 블록으로
 * 모델링하기 전까지는 사용자가 직접 원했을 때만 남긴다.
 *
 * <p>섬 목록은 enrich(LLM)가 주는 주소 토큰이라 검증되지 않은 값이다. 두 가지 가드를 건다.
 * <ul>
 *   <li>{@value #MIN_TOKEN_LENGTH}자 미만 토큰은 버린다("리"·"면" 같은 토큰은 모든 주소에 걸린다).</li>
 *   <li>한 토큰이 후보 풀의 {@value #MAX_POOL_SHARE} 이상을 지우면 그 토큰은 무시한다 —
 *       부속섬이 풀의 대부분일 리 없으므로, 그렇다면 토큰이 틀린 것이다.</li>
 * </ul>
 */
@Slf4j
final class FerryAreaFilter {

    static final int MIN_TOKEN_LENGTH = 3;
    static final double MAX_POOL_SHARE = 0.2;

    private FerryAreaFilter() {}

    /**
     * 실제로 적용할 토큰. 사용자가 요청 문장·직접 입력 장소에서 그 섬을 언급했으면 빼고(가고 싶다는 뜻),
     * 가드를 통과한 것만 남긴다.
     *
     * @param pool 비율 가드를 잴 후보 풀(검색 결과 전체)
     */
    static List<String> activeTokens(List<String> ferryOnlyAreas, String description,
                                     List<String> customPlaceNames, List<PlaceCandidate> pool) {
        if (ferryOnlyAreas == null || ferryOnlyAreas.isEmpty()) return List.of();
        List<String> active = new ArrayList<>();
        for (String raw : ferryOnlyAreas) {
            if (raw == null) continue;
            String token = raw.trim();
            if (token.length() < MIN_TOKEN_LENGTH) continue;
            if (mentioned(token, description, customPlaceNames)) {
                log.info("도선 섬 제외 해제: '{}' — 사용자가 요청에서 언급", token);
                continue;
            }
            long hits = pool.stream().filter(c -> containsToken(c.address(), token)).count();
            if (!pool.isEmpty() && (double) hits / pool.size() >= MAX_POOL_SHARE) {
                log.warn("도선 섬 토큰 무시: '{}'가 후보 {}개 중 {}개에 걸림 — 부속섬 토큰이 아닐 가능성",
                        token, pool.size(), hits);
                continue;
            }
            active.add(token);
        }
        return active;
    }

    /** 토큰에 걸리는 후보를 뺀다. 사용자가 직접 고른 장소는 남긴다. */
    static List<PlaceCandidate> exclude(List<PlaceCandidate> candidates, List<String> tokens) {
        if (tokens.isEmpty()) return candidates;
        List<PlaceCandidate> kept = new ArrayList<>(candidates.size());
        for (PlaceCandidate c : candidates) {
            String hit = c.userSelected() ? null
                    : tokens.stream().filter(t -> containsToken(c.address(), t)).findFirst().orElse(null);
            if (hit == null) {
                kept.add(c);
            } else {
                log.info("도선 섬 장소 제외: {} ({})", c.name(), hit);
            }
        }
        return kept;
    }

    private static boolean containsToken(String address, String token) {
        return address != null && address.contains(token);
    }

    /**
     * 요청에서 섬을 언급했는지. "우도면"은 보통 "우도"로 말하므로 행정 접미사(면·리·동·읍)를 뗀
     * 이름도 본다 — 단 남는 이름이 2자 이상일 때만(한 글자는 아무 문장에나 걸린다).
     */
    private static boolean mentioned(String token, String description, List<String> customPlaceNames) {
        String stem = token;
        char last = token.charAt(token.length() - 1);
        if ((last == '면' || last == '리' || last == '동' || last == '읍') && token.length() - 1 >= 2) {
            stem = token.substring(0, token.length() - 1);
        }
        List<String> texts = new ArrayList<>();
        if (description != null) texts.add(description);
        if (customPlaceNames != null) texts.addAll(customPlaceNames);
        for (String text : texts) {
            if (text != null && (text.contains(token) || text.contains(stem))) return true;
        }
        return false;
    }
}
