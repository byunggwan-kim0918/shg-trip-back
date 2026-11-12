package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.math.BigDecimal;

/**
 * 장소의 인기도(0.0~1.0). Wikidata 언어판 수를 주 신호로, 평점·리뷰 수를 보조로 본다.
 *
 * <p>검색 랭킹과 일정 충전 경로가 같은 기준을 써야 한다.
 * 충전 경로가 거리만 보면 후보 풀에 들어온 명소가 본문에 들어가지 못한다.
 *
 * <p>Google Maps 약관 3.2.3(a)가 평점·리뷰 캐싱을 금지해 Wikidata를 쓴다.
 * sitelinks는 CC0라 저장 제약이 없고, SPARQL이 무료·무인증이며 국외도 덮는다.
 *
 * <p>언어판 80개가 40개보다 2배 좋은 장소는 아니라 로그를 쓴다.
 * 선형이면 초대형 명소 하나가 다른 후보를 모두 눌러 일정이 단조로워진다.
 */
public final class PlaceProminence {

    private PlaceProminence() {
    }

    /**
     * sitelinks 포화 기준. 이 수를 넘으면 인기도 가점이 더 늘지 않는다.
     *
     * <p>제주 sitelinks 분포(중간값 1, p99 28)에서 흔한 1~2개와 대표 명소가 선명하게 갈리는 값.
     */
    private static final int SITELINK_SATURATION = 50;

    /** 리뷰 수 포화 기준. Google 데이터는 없지만 컬럼이 남아 있어 보조 경로로 둔다. */
    private static final int REVIEW_SATURATION_COUNT = 10_000;

    /**
     * 조회했지만 Wikidata에 항목이 없는 장소의 인기도.
     * "모른다"가 아니라 유명하지 않음이 확인됐다는 뜻이라 낮게 둔다.
     */
    private static final double NOT_NOTABLE = 0.10;

    /**
     * 아무 신호도 없을 때의 상한. "확인된 명소"보다 아래, "확인된 무명"보다 위에 둔다.
     * 충전 경로는 비교 대상이 몇 개뿐이라 커버리지 조정 없이 고정값을 써 미매칭 장소를 밀어내지 않는다.
     */
    private static final double UNKNOWN_CEILING = 0.35;

    /** 평점조차 없을 때의 평점비. "모른다"를 "나쁘다"로 단정하지 않는다. */
    private static final double NEUTRAL_RATING_RATIO = 0.7;

    /** 알려진 정도(0~2)를 맞추는 언어판 수. 2는 가이드북 대표 명소·맛집이라 "인기도 높음"(3 이상) 눈금에 둔다. */
    private static final int[] FAME_SITELINKS = {0, 2, 8};

    public static double of(PlaceCandidate c) {
        return c == null ? 0.0
                : of(c.rating(), c.userRatingCount(), effectiveSitelinks(c.wikidataSitelinks(), c.travelerFame()));
    }

    /**
     * 순위와 선택 프롬프트가 보는 언어판 수. 알려진 정도를 언어판 수로 바꿔 큰 쪽을 쓴다. 언어판이 없는 해외 식당은
     * 이 값이 없으면 미쉐린·노포와 평범한 식당이 같은 인기도가 된다.
     */
    public static Integer effectiveSitelinks(Integer sitelinks, Short travelerFame) {
        if (travelerFame == null || travelerFame < 0 || travelerFame >= FAME_SITELINKS.length) return sitelinks;
        int byFame = FAME_SITELINKS[travelerFame];
        return sitelinks == null ? byFame : Math.max(sitelinks, byFame);
    }

    /**
     * 인기도 계산. 신호 우선순위는 <b>sitelinks → 리뷰 수 → 평점</b>이다.
     *
     * <p>{@code sitelinks}의 세 상태를 구분한다.
     * <ul>
     *   <li><b>null</b>: 아직 매칭하지 않음. 판단 보류({@value #UNKNOWN_CEILING} 이하)</li>
     *   <li><b>0</b>: 매칭했는데 항목이 없음. {@value #NOT_NOTABLE}</li>
     *   <li><b>1 이상</b>: 언어판 수를 로그 스케일로</li>
     * </ul>
     *
     * <p>결과 스케일 예시:
     * <pre>
     *   sitelinks 46 (한라산)        → 0.98
     *   sitelinks 16 (성산일출봉)     → 0.72
     *   sitelinks  5                → 0.46
     *   sitelinks  1 (흔한 경우)      → 0.18
     *   sitelinks  0 (항목 없음)      → 0.10
     *   미매칭 + ⭐4.6                → 0.32
     *   미매칭 + 평점없음              → 0.25
     * </pre>
     */
    public static double of(BigDecimal rating, Integer reviewCount, Integer sitelinks) {
        if (sitelinks != null) {
            if (sitelinks <= 0) return NOT_NOTABLE;
            return Math.min(1.0,
                    Math.log10(1.0 + sitelinks) / Math.log10(1.0 + SITELINK_SATURATION));
        }

        double ratingRatio = rating != null
                ? Math.min(1.0, rating.doubleValue() / 5.0)
                : NEUTRAL_RATING_RATIO;

        if (reviewCount == null) return UNKNOWN_CEILING * ratingRatio;

        double popularity = Math.min(1.0,
                Math.log10(1.0 + Math.max(0, reviewCount))
                        / Math.log10(1.0 + REVIEW_SATURATION_COUNT));

        return ratingRatio * popularity;
    }

    /** sitelinks를 모르는 호출부용 호환 오버로드. */
    public static double of(BigDecimal rating, Integer reviewCount) {
        return of(rating, reviewCount, null);
    }
}
