package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

class GenerationStreamRelayTest {

    private final GenerationEventLog eventLog = mock(GenerationEventLog.class);
    private final GenerationStreamRelay relay = new GenerationStreamRelay(eventLog);

    @AfterEach
    void tearDown() {
        relay.shutdown();
    }

    @Test
    @DisplayName("Redis 기록의 프레임을 순서대로 그대로 보내고 끝 표시를 만나면 닫는다")
    void forwardsFramesUntilEnd() throws Exception {
        String progress = "event:progress\ndata:{\"progress\":50}\n\n";
        when(eventLog.readFrom(eq("job"), anyLong())).thenReturn(List.of(progress, GenerationEventLog.END));
        SseEmitter emitter = mock(SseEmitter.class);

        relay.forwardTo("job", emitter);

        verify(emitter, timeout(2_000)).complete();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<DataWithMediaType>> sent = ArgumentCaptor.forClass(Set.class);
        verify(emitter).send(sent.capture());
        assertThat(sent.getValue()).extracting(DataWithMediaType::getData).containsExactly(progress);
    }

    @Test
    @DisplayName("다음 주기에는 이미 보낸 프레임 뒤부터 읽는다")
    void resumesAfterLastFrame() throws Exception {
        when(eventLog.readFrom("job", 0)).thenReturn(List.of("a\n\n", "b\n\n"));
        when(eventLog.readFrom("job", 2)).thenReturn(List.of(GenerationEventLog.END));
        SseEmitter emitter = mock(SseEmitter.class);

        relay.forwardTo("job", emitter);

        verify(emitter, timeout(3_000)).complete();
        verify(emitter, times(2)).send(anySet());
    }

    @Test
    @DisplayName("중계한 프레임의 한글이 실제 SSE 응답에서 깨지지 않는다")
    void relayedFrameKeepsKorean() throws Exception {
        String frame = "event:step-stream\ndata:{\"name\":\"센소지\"}\n\n";
        when(eventLog.readFrom(eq("job"), anyLong())).thenReturn(List.of(frame, GenerationEventLog.END));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RelayController(relay)).build();

        MvcResult result = mvc.perform(get("/stream")).andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(3_000);

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(frame);
    }

    @RestController
    static class RelayController {
        private final GenerationStreamRelay relay;

        RelayController(GenerationStreamRelay relay) {
            this.relay = relay;
        }

        /** 운영 스트림 엔드포인트(PlanningController)와 같은 응답 헤더를 쓴다. */
        @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream(HttpServletResponse response) {
            response.setContentType("text/event-stream;charset=UTF-8");
            return relay.relay("job", 5_000);
        }
    }

    @Test
    @DisplayName("SSE 프레임은 SseEmitter가 내보내는 문자와 같다")
    void frameMatchesSseWireFormat() throws Exception {
        GenerationEventLog log = new GenerationEventLog(null, new ObjectMapper());

        String frame = log.frameOf(SseEmitter.event().name("progress").data(Map.of("progress", 50)).build());

        assertThat(frame).isEqualTo("event:progress\ndata:{\"progress\":50}\n\n");
    }
}
