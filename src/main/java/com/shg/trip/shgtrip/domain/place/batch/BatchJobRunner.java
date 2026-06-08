package com.shg.trip.shgtrip.domain.place.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 배치 파이프라인 진입점.
 *
 * <p>실행 순서:
 * <ol>
 *   <li>Foursquare 장소 시딩 — 실패해도 이후 단계는 계속 진행 (격리)</li>
 *   <li>임베딩 배치 생성 (TODO: llm-optimization 스펙에서 구현)</li>
 *   <li>태그/설명 배치 보강 (TODO: llm-optimization 스펙에서 구현)</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class BatchJobRunner implements CommandLineRunner {

    private final FoursquareSeeder foursquareSeeder;

    @Override
    public void run(String... args) {
        log.info("=== 배치 파이프라인 시작 ===");

        // [1/3] Foursquare 시딩 — 예외 격리
        log.info("[1/3] Foursquare 장소 시딩 시작");
        try {
            foursquareSeeder.seed();
            log.info("[1/3] Foursquare 장소 시딩 완료");
        } catch (Exception e) {
            log.error("[1/3] Foursquare 장소 시딩 실패, 이후 단계 계속 진행: {}", e.getMessage(), e);
        }

        // [2/3] 임베딩 배치 — EmbeddingBatchJob 구현 후 연동 예정
        log.info("[2/3] 임베딩 배치 생성 건너뜀 (미구현)");

        // [3/3] 태그/설명 보강 — BatchEnrichScheduler 구현 후 연동 예정
        log.info("[3/3] 태그/설명 배치 보강 건너뜀 (미구현)");

        log.info("=== 배치 파이프라인 완료 ===");
    }
}
