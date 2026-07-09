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
    private final GooglePlaceSyncScheduler googlePlaceSyncScheduler;

    @Value("${batch.enrich.enabled:false}")
    private boolean enrichEnabled;

    @Value("${batch.google-sync.enabled:false}")
    private boolean googleSyncEnabled;

    @Value("${batch.tourapi.enabled:false}")
    private boolean tourApiEnabled;

    @Override
    public void run(String... args) {
        log.info("=== 배치 파이프라인 시작 (enrich.enabled={}) ===", enrichEnabled);

        log.info("[1/4] Foursquare 장소 시딩 시작");
        try {
            foursquareSeeder.seed();
            log.info("[1/4] Foursquare 장소 시딩 완료");
        } catch (Exception e) {
            log.error("[1/4] Foursquare 장소 시딩 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
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

        // 보강(enrich)을 임베딩보다 먼저 실행한다 — 보강이 tags/description을 채우고 임베딩을
        // 초기화(resetEmbeddings)하므로, 이 순서여야 같은 실행 안에서 보강 내용이 임베딩에 반영된다.
        if (enrichEnabled) {
            log.info("[2/4] 태그/설명 배치 보강 시작");
            try {
                batchEnrichScheduler.enrich();
                log.info("[2/4] 태그/설명 배치 보강 완료");
            } catch (Exception e) {
                log.error("[2/4] 태그/설명 배치 보강 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
            }
        } else {
            log.info("[2/4] 태그/설명 배치 보강 건너뜀 (batch.enrich.enabled=false)");
        }

        log.info("[3/4] 임베딩 배치 생성 시작");
        try {
            embeddingBatchJob.execute();
            log.info("[3/4] 임베딩 배치 생성 완료");
        } catch (Exception e) {
            log.error("[3/4] 임베딩 배치 생성 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
        }

        if (googleSyncEnabled) {
            log.info("[4/4] Google Places 사전채움 시작");
            try {
                googlePlaceSyncScheduler.sync();
                log.info("[4/4] Google Places 사전채움 완료");
            } catch (Exception e) {
                log.error("[4/4] Google Places 사전채움 실패: {}", e.getMessage(), e);
            }
        } else {
            log.info("[4/4] Google Places 사전채움 건너뜀 (batch.google-sync.enabled=false)");
        }

        log.info("=== 배치 파이프라인 완료 ===");
    }
}
