package com.shg.trip.shgtrip.domain.planning.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class GenerationEventLogIntegrationTest {

    @MockBean
    S3Client s3Client;

    @Autowired
    GenerationEventLog eventLog;

    @Autowired
    GenerationStreamRelay relay;

    @Test
    @DisplayName("작업 인스턴스가 보낸 이벤트를 다른 인스턴스의 연결이 Redis에서 이어 받고, 작업이 끝나면 닫힌다")
    void relaysEventsAcrossInstances() throws Exception {
        String jobId = UUID.randomUUID().toString();
        eventLog.register(jobId, 7L);
        GenerationStream stream = new GenerationStream(jobId, () -> { }, eventLog.mirrorFor(jobId));
        stream.send(SseEmitter.event().name("progress").data(Map.of("progress", 30)));
        stream.send(SseEmitter.event().name("step-stream").data(Map.of("dayNumber", 1)));
        stream.complete();

        SseEmitter elsewhere = mock(SseEmitter.class);
        relay.forwardTo(jobId, elsewhere);

        verify(elsewhere, timeout(3_000)).complete();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<DataWithMediaType>> sent = ArgumentCaptor.forClass(Set.class);
        verify(elsewhere, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).flatExtracting(items -> items.stream().map(DataWithMediaType::getData).toList())
                .containsExactly("event:progress\ndata:{\"progress\":30}\n\n", "event:step-stream\ndata:{\"dayNumber\":1}\n\n");
        assertThat(eventLog.ownerOf(jobId)).isEqualTo(7L);
    }
}
