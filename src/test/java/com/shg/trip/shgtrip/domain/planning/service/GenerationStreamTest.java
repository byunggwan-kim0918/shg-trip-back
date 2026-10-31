package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.*;

class GenerationStreamTest {

    private static SseEmitter.SseEventBuilder event(String name) {
        return SseEmitter.event().name(name).data(name);
    }

    @Test
    @DisplayName("연결이 없어도 send는 실패하지 않고, 나중에 붙은 연결이 지난 이벤트를 순서대로 받는다")
    void replaysHistoryToLateSubscriber() throws IOException {
        GenerationStream stream = new GenerationStream("job");
        stream.send(event("progress"));
        stream.send(event("step-stream"));

        SseEmitter emitter = mock(SseEmitter.class);
        stream.attach(emitter);

        verify(emitter, times(2)).send(anySet());
        verify(emitter, never()).complete();
    }

    @Test
    @DisplayName("새 연결이 붙으면 이전 연결을 닫고 이후 이벤트는 새 연결로만 간다")
    void replacesPreviousEmitter() throws IOException {
        GenerationStream stream = new GenerationStream("job");
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        stream.attach(first);
        stream.attach(second);

        stream.send(event("progress"));

        verify(first).complete();
        verify(first, never()).send(anySet());
        verify(second).send(anySet());
    }

    @Test
    @DisplayName("끊긴 연결로 보내다 실패해도 예외를 밖으로 던지지 않는다")
    void swallowsBrokenConnection() throws IOException {
        GenerationStream stream = new GenerationStream("job");
        SseEmitter broken = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(broken).send(anySet());
        stream.attach(broken);

        assertThatCode(() -> stream.send(event("progress"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("끝난 작업에 붙으면 기록을 받고 바로 닫힌다. 종료 콜백은 한 번만 실행된다")
    void attachAfterFinishReplaysAndCompletes() throws IOException {
        AtomicInteger finished = new AtomicInteger();
        GenerationStream stream = new GenerationStream("job", finished::incrementAndGet);
        stream.send(event("complete"));
        stream.complete();
        stream.complete();

        SseEmitter late = mock(SseEmitter.class);
        stream.attach(late);

        verify(late).send(anySet());
        verify(late).complete();
        assertThat(finished.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("detach는 현재 연결일 때만 떼어낸다")
    void detachOnlyCurrent() throws IOException {
        GenerationStream stream = new GenerationStream("job");
        SseEmitter old = mock(SseEmitter.class);
        SseEmitter current = mock(SseEmitter.class);
        stream.attach(old);
        stream.attach(current);
        stream.detach(old);

        stream.send(event("progress"));

        verify(current).send(anySet());
    }
}
