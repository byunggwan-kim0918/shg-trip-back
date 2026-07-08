package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DestinationCoordCacheTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private DestinationCoordCache cache;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("캐시된 좌표를 lat,lng 배열로 반환한다")
    void returnsCachedCoord() {
        when(valueOperations.get("destination-coord:제주")).thenReturn("33.5,126.5");

        Optional<double[]> result = cache.get("제주");

        assertThat(result).isPresent();
        assertThat(result.get()[0]).isEqualTo(33.5);
        assertThat(result.get()[1]).isEqualTo(126.5);
    }

    @Test
    @DisplayName("키는 공백/대소문자를 정규화한다")
    void normalizesKey() {
        when(valueOperations.get("destination-coord:jeju")).thenReturn("33.5,126.5");

        assertThat(cache.get("  Jeju ")).isPresent();
    }

    @Test
    @DisplayName("캐시 미스 시 empty를 반환한다")
    void returnsEmptyOnMiss() {
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThat(cache.get("제주")).isEmpty();
    }

    @Test
    @DisplayName("저장된 값이 손상됐으면 미스로 처리하고 엔트리를 삭제한다 (자가 치유)")
    void treatsCorruptValueAsMissAndEvicts() {
        when(valueOperations.get(anyString())).thenReturn("not-a-coord");

        assertThat(cache.get("제주")).isEmpty();
        verify(redisTemplate).delete("destination-coord:제주");
    }

    @Test
    @DisplayName("숫자로 파싱 불가능한 손상 값도 미스 처리 후 삭제한다")
    void treatsUnparsableValueAsMissAndEvicts() {
        when(valueOperations.get(anyString())).thenReturn("abc,def");

        assertThat(cache.get("제주")).isEmpty();
        verify(redisTemplate).delete("destination-coord:제주");
    }

    @Test
    @DisplayName("Redis 장애 시에도 예외를 전파하지 않고 미스로 처리한다")
    void treatsRedisFailureAsMiss() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(cache.get("제주")).isEmpty();
    }

    @Test
    @DisplayName("put은 lat,lng 문자열을 TTL 30일로 저장한다")
    void putsWithTtl() {
        cache.put("제주", 33.5, 126.5);

        verify(valueOperations).set("destination-coord:제주", "33.5,126.5", Duration.ofDays(30));
    }
}
