package com.shg.trip.shgtrip.domain.planning.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 작업을 돌리지 않는 인스턴스로 온 스트림 요청에 Redis 기록({@link GenerationEventLog})을 이어 보낸다.
 * 연결마다 블로킹 읽기를 잡지 않도록 짧은 주기로 새 프레임만 읽는다.
 */
@Slf4j
@Component
public class GenerationStreamRelay {

    /** 진행률 갱신이 늦어 보이지 않을 만큼 짧고, 연결 수만큼 Redis를 두드려도 부담이 없을 만큼 길다. */
    static final long POLL_INTERVAL_MS = 500;
    /** SseEmitter가 텍스트에 쓰는 것과 같다. 문자셋이 없으면 문자열 변환기가 ISO-8859-1로 써 한글이 깨진다. */
    private static final MediaType FRAME_TYPE = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final GenerationEventLog eventLog;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "sse-relay");
        thread.setDaemon(true);
        return thread;
    });

    public GenerationStreamRelay(GenerationEventLog eventLog) {
        this.eventLog = eventLog;
    }

    public SseEmitter relay(String jobId, long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        forwardTo(jobId, emitter);
        return emitter;
    }

    void forwardTo(String jobId, SseEmitter emitter) {
        AtomicLong cursor = new AtomicLong();
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();
        Runnable stop = () -> {
            done.set(true);
            ScheduledFuture<?> running = task.get();
            if (running != null) running.cancel(false);
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(e -> stop.run());

        task.set(scheduler.scheduleWithFixedDelay(() -> forward(jobId, emitter, cursor, done, stop),
                0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS));
        if (done.get()) task.get().cancel(false);
    }

    private void forward(String jobId, SseEmitter emitter, AtomicLong cursor, AtomicBoolean done, Runnable stop) {
        if (done.get()) return;
        List<String> frames;
        try {
            frames = eventLog.readFrom(jobId, cursor.get());
        } catch (RuntimeException e) {
            // 일시적인 Redis 오류는 다음 주기에 다시 읽는다
            log.debug("SSE 중계 읽기 실패 (jobId={}): {}", jobId, e.getMessage());
            return;
        }
        try {
            for (String frame : frames) {
                cursor.incrementAndGet();
                if (GenerationEventLog.END.equals(frame)) {
                    stop.run();
                    emitter.complete();
                    return;
                }
                emitter.send(Set.of(new DataWithMediaType(frame, FRAME_TYPE)));
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 중계 전송 실패 — 연결 해제 (jobId={}): {}", jobId, e.getMessage());
            stop.run();
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
