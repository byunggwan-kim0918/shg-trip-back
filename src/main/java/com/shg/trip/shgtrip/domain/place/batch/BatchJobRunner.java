package com.shg.trip.shgtrip.domain.place.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** SPRING_PROFILES_ACTIVE=batch에서만 실행되고, 작업을 마치면 컨테이너가 종료된다. */
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
    private final TourApiDetailJob tourApiDetailJob;

    @Value("${batch.enrich.enabled:false}")
    private boolean enrichEnabled;

    /** 켜면 Wikidata 외부 호출이 나간다. */
    @Value("${batch.wikidata.enabled:false}")
    private boolean wikidataEnabled;

    @Value("${batch.tourapi.enabled:false}")
    private boolean tourApiEnabled;

    /** 켜면 TourAPI 하루 호출 한도(개발 계정 1,000건)를 쓴다. */
    @Value("${batch.tourapi.detail.enabled:false}")
    private boolean tourApiDetailEnabled;

    /** 켜면 Wikidata SPARQL 호출이 나간다. */
    @Value("${batch.wikidata.landmarks.enabled:false}")
    private boolean wikidataLandmarksEnabled;

    /** 임베딩만 채우는 재실행에서는 끈다. 52,000행 upsert에 20분 이상 걸리고 얻는 게 없다. */
    @Value("${batch.foursquare.enabled:true}")
    private boolean foursquareEnabled;

    /** 보강 전에 돌아야 비방문 장소가 보강 대상에서 빠진다. */
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

        // Foursquare가 빈약한 지역의 관광지를 TourAPI로 채운다.
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

        if (tourApiDetailEnabled) {
            log.info("[1/4-공식] TourAPI 공식 영업시간 연결 시작");
            try {
                tourApiDetailJob.run();
            } catch (Exception e) {
                log.error("[1/4-공식] TourAPI 공식 영업시간 연결 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
            }
        }

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

        // 보강은 하루 가까이 걸릴 수 있어 인기도를 먼저 채워야 중간에 끊겨도 유명한 장소부터 끝나 있다.
        // Maps 약관 3.2.3(a)가 세부정보·좌표·평점 캐싱을 금지해 인기도는 Wikidata로 채운다.
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

        // 보강이 tags/description을 채우고 임베딩을 초기화하므로 임베딩보다 먼저 돌아야 같은 실행에 반영된다.
        if (enrichEnabled) {
            log.info("[3/4] {} 배치 보강 시작", batchEnrichScheduler.attributeMode() ? "속성(v2)" : "태그/설명");
            try {
                batchEnrichScheduler.enrich();
                log.info("[3/4] 배치 보강 완료");
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
