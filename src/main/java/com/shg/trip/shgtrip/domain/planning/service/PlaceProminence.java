package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.math.BigDecimal;

/**
 * 장소의 인기도(0.0~1.0) — <b>Wikidata 언어판 수</b>를 주 신호로, 평점·리뷰 수를 보조로 본다.
 *
 * <p>검색 랭킹과 일정 충전 경로가 같은 기준을 써야 한다. 검색을 고쳐 명소가 후보 풀에 들어와도,
 * 충전 경로가 거리만 보면 결과는 그대로다(실측: 후보 풀 관광지 리뷰 중간값이 645로 올라갔는데
 * 일정에 들어간 관광지는 192에 머물렀다 — 리뷰 17,085건의 용담해안도로는 대안 목록에만 들어가고
 * 본문은 리뷰 22건의 바늘오름이 차지했다).
 *
 * <h2>왜 Wikidata인가</h2>
 * 원래는 Google 리뷰 수를 썼다. 그런데 Google Maps 약관 3.2.3(a)가 평점·리뷰를 로컬 DB에
 * 캐싱해 재사용하는 것을 금지해(예외는 Place ID 하나) 2026-10-20 전량 삭제했다. Wikidata의
 * sitelinks는 CC0라 저장 제약이 없고, SPARQL이 무료·무인증이며, 전 세계를 덮는다
 * (TourAPI는 국내 전용이라 일본 34,033곳을 못 덮는다).
 *
 * <p>실측 제주: 한라산 46, 우도 17, 성산일출봉 16, 만장굴 11. 랭킹에서 계속 이기던
 * 열안지오름·사라봉은 Wikidata에 항목 자체가 없다. 교토: 기요미즈데라 77, 로쿠온지 57.
 *
 * <p>로그를 쓰는 이유: 언어판 80개가 40개보다 2배 좋은 장소는 아니다. 선형으로 쓰면
 * 초대형 명소 하나가 다른 모든 후보를 눌러 일정이 단조로워진다.
 */
public final class PlaceProminence {

    private PlaceProminence() {
    }

    /**
     * sitelinks 포화 기준. 이 수를 넘으면 인기도 가점이 더 늘지 않는다.
     *
     * <p>실측 분포(제주 bbox 750건)가 극단적으로 치우쳐 있다 — 중간값 1, 83%가 1~2개,
     * p95가 5, p99가 28, 최대 80. 50이면 한라산(46)이 거의 포화하고 성산일출봉(16)이 0.72,
     * 흔한 1~2개가 0.18~0.28로 떨어져 구분이 선명하다. 그 위(교토시 260 등)는 굳이
     * 구분할 필요가 없다.
     */
    private static final int SITELINK_SATURATION = 50;

    /** 리뷰 수 포화 기준. Google 데이터는 삭제됐지만 컬럼이 살아 있어 보조 경로로 남겨둔다. */
    private static final int REVIEW_SATURATION_COUNT = 10_000;

    /**
     * 조회해 봤지만 Wikidata에 항목이 없는 장소의 인기도.
     *
     * <p>"모른다"가 아니라 <b>"유명하지 않음이 확인됐다"</b>는 뜻이라 낮게 둔다. 열안지오름·
     * 사라봉이 여기 해당한다.
     */
    private static final double NOT_NOTABLE = 0.10;

    /**
     * 아무 신호도 없을 때의 상한 — "확인된 명소"보다 아래, "확인된 무명"보다 위에 둔다.
     *
     * <p>검색 랭킹({@code VectorSearchQueryService})은 슬롯 후보의 데이터 커버리지로 이 값을
     * 조정하지만, 충전 경로는 비교 대상이 몇 개뿐이라 커버리지를 신뢰할 수 없어 고정값을 쓴다.
     * 아직 매칭하지 않은 장소를 충전 후보에서 통째로 밀어내지 않기 위함이다.
     */
    private static final double UNKNOWN_CEILING = 0.35;

    /** 평점조차 없을 때의 평점비 — "모른다"를 "나쁘다"로 단정하지 않는다. */
    private static final double NEUTRAL_RATING_RATIO = 0.7;

    /** 후보의 인기도. */
    public static double of(PlaceCandidate c) {
        return c == null ? 0.0 : of(c.rating(), c.userRatingCount(), c.wikidataSitelinks());
    }

    /**
     * 인기도 계산. 신호 우선순위는 <b>sitelinks → 리뷰 수 → 평점</b>이다.
     *
     * <p>{@code sitelinks}의 세 상태를 구분한다.
     * <ul>
     *   <li><b>null</b> — 아직 매칭을 안 해봤다. 판단 보류({@value #UNKNOWN_CEILING} 이하)</li>
     *   <li><b>0</b> — 매칭했는데 항목이 없다. 유명하지 않음이 확인된 것이므로
     *       {@value #NOT_NOTABLE}</li>
     *   <li><b>1 이상</b> — 언어판 수를 로그 스케일로</li>
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
