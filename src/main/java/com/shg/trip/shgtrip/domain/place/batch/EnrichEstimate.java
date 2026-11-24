package com.shg.trip.shgtrip.domain.place.batch;

import java.util.List;
import java.util.Locale;

/**
 * v1 보강 실행 전 견적. 요청당 토큰은 프롬프트 갈래별 실측 평균이고 단가는 Haiku 5.5 Batch 기준이다.
 * 같은 요청 묶기로 줄어드는 몫은 빼지 않아 상한 쪽 값이다.
 */
final class EnrichEstimate {

    private EnrichEstimate() {}

    /** 프롬프트 갈래별 요청당 토큰. 규칙은 system 캐시로 읽힌다. */
    record Profile(int placeTokens, int ruleTokens, int outputTokens) {}

    static final Profile KOREAN_DINING = new Profile(110, 840, 42);
    static final Profile KOREAN_SIGHT = new Profile(110, 840, 240);
    // 해외 프롬프트는 한국어 이름·알려진 정도 규칙이 붙어 규칙이 두 배 넘게 길다.
    static final Profile OVERSEAS_DINING = new Profile(160, 2_100, 70);
    static final Profile OVERSEAS_SIGHT = new Profile(150, 1_850, 280);

    // Haiku 5.5 Batch 단가(USD / 백만 토큰). 캐시 읽기는 입력의 0.1배다.
    private static final double INPUT_USD = 0.05;
    private static final double CACHE_READ_USD = 0.005;
    private static final double OUTPUT_USD = 0.25;

    /** 국가·식당 여부별 대상 수. */
    record Line(String country, boolean dining, long targets) {
        Profile profile() {
            boolean overseas = OverseasEnrichment.isOverseas(country);
            if (dining) return overseas ? OVERSEAS_DINING : KOREAN_DINING;
            return overseas ? OVERSEAS_SIGHT : KOREAN_SIGHT;
        }
    }

    /**
     * @param cachedUsd    규칙이 캐시로 읽힐 때
     * @param uncachedUsd  캐시가 하나도 맞지 않을 때(Batch 캐시는 보장되지 않는다)
     */
    record Total(long requests, long inputTokens, long outputTokens, double cachedUsd, double uncachedUsd) {
        String summary() {
            return String.format(Locale.ROOT, "요청 %,d건, 입력 약 %,d토큰(규칙 포함), 출력 약 %,d토큰, 예상 $%.2f(캐시 미적중 시 $%.2f)",
                    requests, inputTokens, outputTokens, cachedUsd, uncachedUsd);
        }
    }

    static Total of(List<Line> lines, long maxRequests) {
        long budget = maxRequests > 0 ? maxRequests : Long.MAX_VALUE;
        long requests = 0, placeTokens = 0, ruleTokens = 0, outputTokens = 0;
        for (Line line : lines) {
            long n = Math.min(line.targets(), budget - requests);
            if (n <= 0) break;
            Profile p = line.profile();
            requests += n;
            placeTokens += n * p.placeTokens();
            ruleTokens += n * p.ruleTokens();
            outputTokens += n * p.outputTokens();
        }
        double output = outputTokens * OUTPUT_USD / 1e6;
        double cached = placeTokens * INPUT_USD / 1e6 + ruleTokens * CACHE_READ_USD / 1e6 + output;
        double uncached = (placeTokens + ruleTokens) * INPUT_USD / 1e6 + output;
        return new Total(requests, placeTokens + ruleTokens, outputTokens, cached, uncached);
    }
}
