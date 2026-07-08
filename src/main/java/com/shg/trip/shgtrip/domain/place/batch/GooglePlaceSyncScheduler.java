package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.service.PlaceRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Google Places 데이터 월배치 사전채움.
 * <p>
 * 최근 벡터 검색 후보로 등장한(=인기) 장소 중 동기화가 필요한 것을 오프피크에 미리 채워,
 * 실제 일정 생성 시점(OptimizedGenerationExecutor.syncAllPlaces)의 실시간 Google 호출을 급감시킨다.
 * refresh 로직은 {@link PlaceRefreshService#refreshSync}를 재사용 — place_id 있으면 Details 직조회,
 * 없으면 Text Search 후 place_id backfill, 이미지 S3 업로드까지 동일 경로.
 * <p>
 * BatchEnrichScheduler와 같은 {@code @Profile("batch")} + ECS Fargate Scheduled Task 패턴.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class GooglePlaceSyncScheduler {

    private final PlaceRepository placeRepository;
    private final PlaceRefreshService placeRefreshService;

    /** 최근 N일 내 후보로 등장한 장소만 대상 (오래전 등장 = 더 이상 인기 없음). */
    @Value("${batch.google-sync.recent-days:90}")
    private int recentDays;

    /** 배치 1회 실행당 사전채움 상한 (Google 호출 폭주 방지, 무료쿼터 내 흡수). */
    @Value("${batch.google-sync.daily-limit:500}")
    private int dailyLimit;

    public void sync() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime recentThreshold = now.minusDays(recentDays);
        OffsetDateTime staleThreshold = now.minusDays(Place.STALENESS_DAYS);

        List<Place> targets = placeRepository.findPrefetchTargets(
                recentThreshold, staleThreshold, PageRequest.of(0, dailyLimit));

        if (targets.isEmpty()) {
            log.info("Google Places 사전채움 대상 없음");
            return;
        }

        log.info("Google Places 사전채움 시작: {}건 (recentDays={}, limit={})", targets.size(), recentDays, dailyLimit);
        int success = 0;
        for (Place place : targets) {
            if (place.getId() == null) continue;
            try {
                placeRefreshService.refreshSync(place.getId(), place.getName());
                success++;
            } catch (Exception e) {
                log.warn("Google Places 사전채움 실패: placeId={}, error={}", place.getId(), e.getMessage());
            }
        }
        log.info("Google Places 사전채움 완료: {}/{}건", success, targets.size());
    }
}
