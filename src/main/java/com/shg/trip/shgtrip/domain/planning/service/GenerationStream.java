package com.shg.trip.shgtrip.domain.planning.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SseEmitter는 첫 GET 응답에 묶여 새로고침 뒤 재사용할 수 없다.
 * 그래서 보낸 이벤트를 기록해 두고 새 연결이 붙으면 다시 흘려 진행 상태를 복원한다.
 */
@Slf4j
public class GenerationStream {

    /** 한 작업의 이벤트는 수십 개라 넘칠 일이 없지만 상한은 둔다. */
    private static final int MAX_HISTORY = 200;

    private final String jobId;
    private final Runnable onFinished;
    private final Mirror mirror;
    private final List<Set<DataWithMediaType>> history = new ArrayList<>();
    private SseEmitter current;
    private boolean finished;

    public GenerationStream(String jobId) {
        this(jobId, () -> { });
    }

    public GenerationStream(String jobId, Runnable onFinished) {
        this(jobId, onFinished, Mirror.NONE);
    }

    /** @param mirror 이벤트를 인스턴스 밖에도 남기는 통로. 다른 인스턴스로 온 연결이 여기서 이어 받는다 */
    public GenerationStream(String jobId, Runnable onFinished, Mirror mirror) {
        this.jobId = jobId;
        this.onFinished = onFinished;
        this.mirror = mirror;
    }

    /** 보낸 이벤트와 작업 끝을 인스턴스 밖 기록에 남긴다. 실패해도 생성을 멈추지 않는다. */
    public interface Mirror {
        Mirror NONE = new Mirror() {
            @Override
            public void append(Set<DataWithMediaType> items) {
            }

            @Override
            public void end() {
            }
        };

        void append(Set<DataWithMediaType> items);

        void end();
    }

    /**
     * 새 연결을 붙인다. 이전 연결은 닫고, 지금까지의 이벤트를 다시 보낸다.
     * 작업이 이미 끝났으면 기록만 보내고 바로 닫는다.
     */
    public synchronized void attach(SseEmitter emitter) {
        SseEmitter previous = current;
        current = emitter;
        if (previous != null && previous != emitter) {
            completeQuietly(previous);
        }
        for (Set<DataWithMediaType> items : history) {
            if (!deliver(items)) return;
        }
        if (finished) {
            completeQuietly(emitter);
            current = null;
        }
    }

    /** 끊긴 연결을 떼어낸다. 그사이 다른 연결이 붙었으면 건드리지 않는다. */
    public synchronized void detach(SseEmitter emitter) {
        if (current == emitter) {
            current = null;
        }
    }

    /**
     * 연결이 없거나 끊겨 있어도 예외를 던지지 않는다.
     * 클라이언트가 나가도 작업은 끝까지 돌고, 다시 붙으면 기록으로 따라잡는다.
     */
    public synchronized void send(SseEmitter.SseEventBuilder event) throws IOException {
        if (finished) return;
        Set<DataWithMediaType> items = event.build();
        if (history.size() < MAX_HISTORY) {
            history.add(items);
            mirror.append(items);
        }
        deliver(items);
    }

    public void complete() {
        synchronized (this) {
            if (finished) return;
            finished = true;
            mirror.end();
            if (current != null) {
                completeQuietly(current);
                current = null;
            }
        }
        onFinished.run();
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    private boolean deliver(Set<DataWithMediaType> items) {
        if (current == null) return false;
        try {
            current.send(items);
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 전송 실패 — 연결 해제 (jobId={}): {}", jobId, e.getMessage());
            current = null;
            return false;
        }
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 이미 닫힌 연결
        }
    }

    /** 존재하지 않거나 남의 작업을 요청한 연결에 보내는 1회성 error 스트림. */
    public static SseEmitter rejected(String errorCode, String message) {
        SseEmitter emitter = new SseEmitter();
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(java.util.Map.of("status", "error", "errorCode", errorCode, "message", message),
                            MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 거절 이벤트 전송 실패: {}", e.getMessage());
        }
        emitter.complete();
        return emitter;
    }
}
