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
    private final EmbeddingBatchJob embeddingBatchJob;
    private final BatchEnrichScheduler batchEnrichScheduler;

    @Value("${batch.enrich.enabled:false}")
    private boolean enrichEnabled;

    @Override
    public void run(String... args) {
        log.info("=== 배치 파이프라인 시작 (enrich.enabled={}) ===", enrichEnabled);

        log.info("[1/3] Foursquare 장소 시딩 시작");
        try {
            foursquareSeeder.seed();
            log.info("[1/3] Foursquare 장소 시딩 완료");
        } catch (Exception e) {
            log.error("[1/3] Foursquare 장소 시딩 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
        }

        log.info("[2/3] 임베딩 배치 생성 시작");
        embeddingBatchJob.execute();
        log.info("[2/3] 임베딩 배치 생성 완료");

        if (enrichEnabled) {
            log.info("[3/3] 태그/설명 배치 보강 시작");
            batchEnrichScheduler.enrich();
            log.info("[3/3] 태그/설명 배치 보강 완료");
        } else {
            log.info("[3/3] 태그/설명 배치 보강 건너뜀 (batch.enrich.enabled=false)");
        }

        log.info("=== 배치 파이프라인 완료 ===");
    }
}
