package com.shg.trip.shgtrip.domain.place.s3;

import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.service.PlaceRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * imageUrl이 없는 장소에 대해 비동기로 S3 업로드를 시도한다.
 * Redis 분산 락으로 동일 장소 동시 업로드를 방지한다.
 * 락 값에 UUID를 사용하여 다른 인스턴스의 락을 잘못 삭제하는 것을 방지한다.
 * 만료된 photoReference로 실패 시 Google API 재조회 후 재시도한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlaceImageAsyncRecovery {

    private final PlaceImageUploader uploader;
    private final PlaceRepository placeRepository;
    private final PlaceRefreshService placeRefreshService;
    private final StringRedisTemplate redis;

    private static final String LOCK_PREFIX = "img:upload:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    /**
     * imageUrl이 없는 장소에 대해 비동기로 S3 업로드를 시도한다.
     * Redis SETNX로 분산 락을 획득한 후 업로드를 진행하며,
     * 락 미획득 시 이미 다른 요청이 진행 중이므로 즉시 반환한다.
     * @Transactional은 updateImageUrl()이 DB에 커밋되도록 보장한다.
     */
    @Async
    @Transactional
    public void tryUploadAsync(Long placeId, String photoReference) {
        String lockKey = LOCK_PREFIX + placeId;
        String lockValue = UUID.randomUUID().toString();

        // Redis SETNX — 이미 다른 스레드/인스턴스가 업로드 중이면 스킵
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(lockKey, lockValue, LOCK_TTL);

        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Lock not acquired for placeId={}, skipping async upload", placeId);
            return;
        }

        log.debug("Lock acquired for placeId={}, starting async S3 upload", placeId);

        try {
            Optional<String> url = uploader.uploadIfAbsent(placeId, photoReference);
            if (url.isPresent()) {
                placeRepository.updateImageUrl(placeId, url.get());
                log.info("Async S3 upload succeeded for placeId={}, imageUrl={}", placeId, url.get());
            } else {
                log.info("photoReference 만료 추정, Place {} 전체 갱신 시도", placeId);
                placeRepository.findById(placeId)
                        .ifPresent(place -> placeRefreshService.refreshSync(placeId, place.getName()));
            }
        } finally {
            // 자신이 설정한 락만 삭제 (TTL 만료 후 다른 요청이 획득한 락을 삭제하지 않음)
            String currentValue = redis.opsForValue().get(lockKey);
            if (lockValue.equals(currentValue)) {
                redis.delete(lockKey);
            }
        }
    }
}
