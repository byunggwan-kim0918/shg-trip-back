package com.shg.trip.shgtrip.domain.planning.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * 여행지 목적지명 → 기준 좌표(lat,lng) Redis 캐시.
 * 도시 좌표는 사실상 불변 데이터이므로 TTL 30일로 길게 잡아
 * 같은 목적지의 반복 일정 생성 시 Google Places API 호출을 생략한다.
 * Redis 장애 시에는 캐시 미스로 간주하고 호출자가 API 조회로 진행한다 (생성 파이프라인 중단 방지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DestinationCoordCache {

    private static final String KEY_PREFIX = "destination-coord:";
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;

    public Optional<double[]> get(String destination) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(key(destination));
        } catch (Exception e) {
            log.warn("목적지 좌표 캐시 조회 실패 (미스로 처리): destination={}, error={}", destination, e.getMessage());
            return Optional.empty();
        }
        if (value == null) return Optional.empty();

        Optional<double[]> parsed = parse(value);
        if (parsed.isEmpty()) {
            // 손상 엔트리는 TTL 내내 미스+재호출만 반복하므로 삭제해 다음 put에서 정상화
            log.warn("손상된 목적지 좌표 캐시 삭제: destination={}, value={}", destination, value);
            try {
                redisTemplate.delete(key(destination));
            } catch (Exception ignored) {
            }
        }
        return parsed;
    }

    private Optional<double[]> parse(String value) {
        String[] parts = value.split(",");
        if (parts.length != 2) return Optional.empty();
        try {
            return Optional.of(new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])});
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public void put(String destination, double lat, double lng) {
        try {
            redisTemplate.opsForValue().set(key(destination), lat + "," + lng, TTL);
        } catch (Exception e) {
            log.warn("목적지 좌표 캐시 저장 실패 (무시): destination={}, error={}", destination, e.getMessage());
        }
    }

    /** "제주", " 제주 ", "제주도"를 구분 없이 맞추진 않되, 공백/대소문자 차이만 정규화한다. */
    private String key(String destination) {
        return KEY_PREFIX + destination.trim().toLowerCase(Locale.ROOT);
    }
}
