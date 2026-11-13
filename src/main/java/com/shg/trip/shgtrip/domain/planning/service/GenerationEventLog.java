package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 생성 진행 이벤트를 SSE 프레임 그대로 Redis에도 남긴다. 스트림 요청이 작업을 돌리는 인스턴스가 아닌 곳으로 와도
 * 이 기록을 이어 보내 진행 화면이 끊기지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationEventLog {

    private static final String EVENTS_PREFIX = "gen:events:";
    private static final String OWNER_PREFIX = "gen:owner:";
    /** 실제 프레임은 비어 있지 않아 빈 문자열을 작업 끝 표시로 쓴다. */
    static final String END = "";
    /** SSE 타임아웃(10분)보다 길어야 늦게 붙은 연결도 기록을 처음부터 받는다. */
    private static final Duration TTL = Duration.ofMinutes(12);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void register(String jobId, Long userId) {
        redisTemplate.opsForValue().set(OWNER_PREFIX + jobId, String.valueOf(userId), TTL);
    }

    /** 작업을 시작한 사용자. 기록이 없거나 만료됐으면 null. */
    public Long ownerOf(String jobId) {
        String value = redisTemplate.opsForValue().get(OWNER_PREFIX + jobId);
        return value == null ? null : Long.valueOf(value);
    }

    /** 이 작업의 기록 통로. Redis 오류는 진행 중인 생성을 멈추지 않게 삼킨다. */
    GenerationStream.Mirror mirrorFor(String jobId) {
        return new GenerationStream.Mirror() {
            @Override
            public void append(Set<DataWithMediaType> items) {
                try {
                    push(jobId, frameOf(items));
                } catch (JsonProcessingException e) {
                    log.warn("SSE 프레임 직렬화 실패 (jobId={}): {}", jobId, e.getMessage());
                }
            }

            @Override
            public void end() {
                push(jobId, END);
            }
        };
    }

    /** index번째부터의 프레임. 끝 표시({@link #END})가 섞여 있을 수 있다. */
    List<String> readFrom(String jobId, long index) {
        List<String> frames = redisTemplate.opsForList().range(EVENTS_PREFIX + jobId, index, -1);
        return frames != null ? frames : List.of();
    }

    private void push(String jobId, String frame) {
        try {
            redisTemplate.opsForList().rightPush(EVENTS_PREFIX + jobId, frame);
            redisTemplate.expire(EVENTS_PREFIX + jobId, TTL);
        } catch (RuntimeException e) {
            log.warn("SSE 이벤트 기록 실패 (jobId={}): {}", jobId, e.getMessage());
        }
    }

    /** SseEmitter가 내보낼 문자 그대로. 데이터 객체는 SSE 메시지 변환과 같은 JSON으로 쓴다. */
    String frameOf(Set<DataWithMediaType> items) throws JsonProcessingException {
        StringBuilder frame = new StringBuilder();
        for (DataWithMediaType item : items) {
            Object data = item.getData();
            frame.append(data instanceof CharSequence text ? text : objectMapper.writeValueAsString(data));
        }
        return frame.toString();
    }
}
