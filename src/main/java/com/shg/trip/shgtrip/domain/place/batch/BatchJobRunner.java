package com.shg.trip.shgtrip.domain.place.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 배치 파이프라인 진입점.
 * SPRING_PROFILES_ACTIVE=batch 프로필로 구동 시 실행되며,
 * 시딩 → 임베딩 → 태그보강 순서로 배치 작업을 수행한 뒤 컨테이너가 자동 종료된다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class BatchJobRunner implements CommandLineRunner {

    private final FoursquareSeeder foursquareSeeder;
    private final TourApiSeeder tourApiSeeder;
    private final EmbeddingBatchJob embeddingBatchJob;
    private final BatchEnrichScheduler batchEnrichScheduler;
    private final WikidataSitelinksJob wikidataSitelinksJob;
    private final WikidataLandmarkSeeder wikidataLandmarkSeeder;
    private final PlaceRoleBackfillJob placeRoleBackfillJob;

    @Value("${batch.enrich.enabled:false}")
    private boolean enrichEnabled;

    /** Wikidata 인기도 매칭. 기본 false — 켜야만 외부 호출이 나간다. */
    @Value("${batch.wikidata.enabled:false}")
    private boolean wikidataEnabled;

    @Value("${batch.tourapi.enabled:false}")
    private boolean tourApiEnabled;

    /** Wikidata 대표 명소 적재. 기본 false — 켜야만 SPARQL 호출이 나간다. */
    @Value("${batch.wikidata.landmarks.enabled:false}")
    private boolean wikidataLandmarksEnabled;

    /**
     * Foursquare CSV 시딩 실행 여부. 기본 true(운영 전체 파이프라인 유지)지만, 이미 적재된 행의
     * 임베딩만 채우는 재실행에서는 꺼야 한다 — 52,000행 upsert에 20분 이상 걸리고 얻는 게 없다.
     */
    @Value("${batch.foursquare.enabled:true}")
    private boolean foursquareEnabled;

    /** 카테고리로 확정되는 비방문 장소 역할 기록. 외부 호출 없음 — 보강 전에 돌아야 보강 대상에서 빠진다. */
    @Value("${batch.role-backfill.enabled:true}")
    private boolean roleBackfillEnabled;

    @Override
    public void run(String... args) {
        log.info("=== 배치 파이프라인 시작 (enrich.enabled={}) ===", enrichEnabled);

        if (foursquareEnabled) {
            log.info("[1/4] Foursquare 장소 시딩 시작");
            try {
                foursquareSeeder.seed();
                log.info("[1/4] Foursquare 장소 시딩 완료");
            } catch (Exception e) {
                log.error("[1/4] Foursquare 장소 시딩 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
            }
        } else {
            log.info("[1/4] Foursquare 장소 시딩 건너뜀 (batch.foursquare.enabled=false)");
        }

        // 관광지(Landmarks) 커버리지 보완 — Foursquare가 빈약한 지역의 관광지를 TourAPI로 채운다.
        if (tourApiEnabled) {
            log.info("[1/4-보완] TourAPI 관광지 시딩 시작");
            try {
                tourApiSeeder.seed();
                log.info("[1/4-보완] TourAPI 관광지 시딩 완료");
            } catch (Exception e) {
                log.error("[1/4-보완] TourAPI 관광지 시딩 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
            }
        } else {
            log.info("[1/4-보완] TourAPI 관광지 시딩 건너뜀 (batch.tourapi.enabled=false)");
        }

        // 대표 명소(경복궁·청수사·도쿄타워 등)가 기존 소스에 없는 경우를 Wikidata로 채운다.
        // 인기도 매칭보다 먼저 돌아야 새로 넣은 명소도 같은 실행에서 인기도·보강 대상이 된다.
        if (wikidataLandmarksEnabled) {
            log.info("[1/4-명소] Wikidata 대표 명소 적재 시작");
            try {
                wikidataLandmarkSeeder.seed();
                log.info("[1/4-명소] Wikidata 대표 명소 적재 완료");
            } catch (Exception e) {
                log.error("[1/4-명소] Wikidata 대표 명소 적재 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
            }
        } else {
            log.info("[1/4-명소] Wikidata 대표 명소 적재 건너뜀 (batch.wikidata.landmarks.enabled=false)");
        }

        // 인기도(Wikidata)를 보강보다 먼저 채운다. 보강은 수만 건을 1,000건씩 순차로 Batch API에
        // 넣고 기다려 하루 가까이 걸릴 수 있는데, 인기도가 있어야 유명한 장소부터 보강할 수 있다 —
        // 중간에 끊겨도 일정에 자주 나오는 곳은 이미 끝나 있다. (Google Places 사전채움은 제거됐다 —
        // Maps 약관 3.2.3(a)가 세부정보·좌표·평점 캐싱을 금지한다. 인기도는 Wikidata가 대신한다.)
        if (wikidataEnabled) {
            log.info("[2/4] Wikidata 인기도 매칭 시작");
            try {
                wikidataSitelinksJob.run();
                log.info("[2/4] Wikidata 인기도 매칭 완료");
            } catch (Exception e) {
                log.error("[2/4] Wikidata 인기도 매칭 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
            }
        } else {
            log.info("[2/4] Wikidata 인기도 매칭 건너뜀 (batch.wikidata.enabled=false)");
        }

        if (roleBackfillEnabled) {
            log.info("[2/4-역할] 비방문 장소 역할 기록 시작");
            try {
                placeRoleBackfillJob.run();
            } catch (Exception e) {
                log.error("[2/4-역할] 비방문 장소 역할 기록 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
            }
        }

        // 보강(enrich)을 임베딩보다 먼저 실행한다 — 보강이 tags/description을 채우고 임베딩을
        // 초기화(resetEmbeddings)하므로, 이 순서여야 같은 실행 안에서 보강 내용이 임베딩에 반영된다.
        if (enrichEnabled) {
            log.info("[3/4] 태그/설명 배치 보강 시작");
            try {
                batchEnrichScheduler.enrich();
                log.info("[3/4] 태그/설명 배치 보강 완료");
            } catch (Exception e) {
                log.error("[3/4] 태그/설명 배치 보강 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
            }
        } else {
            log.info("[3/4] 태그/설명 배치 보강 건너뜀 (batch.enrich.enabled=false)");
        }

        log.info("[4/4] 임베딩 배치 생성 시작");
        try {
            embeddingBatchJob.execute();
            log.info("[4/4] 임베딩 배치 생성 완료");
        } catch (Exception e) {
            log.error("[4/4] 임베딩 배치 생성 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
        }

        log.info("=== 배치 파이프라인 완료 ===");
    }
}
