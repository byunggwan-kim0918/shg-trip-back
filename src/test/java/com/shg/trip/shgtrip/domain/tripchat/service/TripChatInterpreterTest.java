package com.shg.trip.shgtrip.domain.tripchat.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.tripchat.model.DraftField;
import com.shg.trip.shgtrip.domain.tripchat.model.Interpretation;
import com.shg.trip.shgtrip.global.config.AnthropicProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripChatInterpreterTest {

    @Test
    @DisplayName("출력 스키마 필드 enum은 DraftField와 같고 모든 객체가 닫혀 있다")
    @SuppressWarnings("unchecked")
    void schemaMatchesDraftFields() {
        JsonOutputFormat.Schema schema = TripChatInterpreter.schema();
        Map<String, JsonValue> root = schema._additionalProperties();

        assertThat(root.get("additionalProperties").convert(Boolean.class)).isFalse();
        Map<String, Object> props = root.get("properties").convert(Map.class);
        Map<String, Object> updates = (Map<String, Object>) props.get("updates");
        Map<String, Object> item = (Map<String, Object>) updates.get("items");
        Map<String, Object> field = (Map<String, Object>) ((Map<String, Object>) item.get("properties")).get("field");

        assertThat((List<String>) field.get("enum"))
                .containsExactlyElementsOf(Arrays.stream(DraftField.values()).map(Enum::name).toList());
        assertThat(item.get("additionalProperties")).isEqualTo(false);
        assertThat((List<String>) root.get("required").convert(List.class))
                .containsExactlyInAnyOrder("followUp", "offTopic", "slotOutcome", "updates");
    }

    @Test
    @DisplayName("구조화 출력 JSON을 읽고, 깨진 JSON은 실패로 본다")
    void parsesJson() {
        TripChatInterpreter interpreter = interpreter();
        Interpretation it = interpreter.parseJson("""
                {"updates":[{"field":"DESTINATION","op":"set","value":"부산","status":"known","source":"user",
                "raw":"부산","date":"","time":""}],"slotOutcome":"answered",
                "followUp":{"needed":false,"reason":"none","lead":"","title":"","desc":"","examples":[],"placeholder":""},
                "offTopic":false}""");

        assertThat(it.updates()).singleElement().extracting(Interpretation.Update::value).isEqualTo("부산");
        assertThatThrownBy(() -> interpreter.parseJson("{\"updates\": [")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("구조화 출력 요청을 보내고 한 번 실패하면 다시 시도한다")
    void retriesOnceThenParses() {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.withOptions(any())).thenReturn(client);
        when(client.messages()).thenReturn(messages);
        Message ok = message(StopReason.END_TURN, VALID);
        when(messages.create(any(MessageCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(ok);

        TripChatInterpreter.Result r = interpreter(client).interpret("<context/>", "부산");

        assertThat(r.interpretation().updates()).hasSize(1);
        assertThat(r.usage().inputTokens()).isEqualTo(1200);
        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages, times(2)).create(captor.capture(), any(RequestOptions.class));
        assertThat(captor.getValue().model().asString()).isEqualTo("claude-haiku-5-5");
        assertThat(captor.getValue().outputConfig()).isPresent();
    }

    @Test
    @DisplayName("거절·잘림 응답은 두 번 모두 실패하면 예외로 끝난다")
    void refusalFails() {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.withOptions(any())).thenReturn(client);
        when(client.messages()).thenReturn(messages);
        Message refusal = message(StopReason.REFUSAL, "");
        when(messages.create(any(MessageCreateParams.class), any(RequestOptions.class))).thenReturn(refusal);

        assertThatThrownBy(() -> interpreter(client).interpret("<context/>", "부산"))
                .isInstanceOf(TripChatInterpreter.InterpretException.class);
    }

    private static final String VALID = """
            {"updates":[{"field":"DESTINATION","op":"set","value":"부산","status":"known","source":"user",
            "raw":"부산","date":"","time":""}],"slotOutcome":"answered",
            "followUp":{"needed":false,"reason":"none","lead":"","title":"","desc":"","examples":[],"placeholder":""},
            "offTopic":false}""";

    private static Message message(StopReason stop, String json) {
        Message m = mock(Message.class);
        ContentBlock block = mock(ContentBlock.class);
        TextBlock text = mock(TextBlock.class);
        Usage usage = mock(Usage.class);
        when(m.stopReason()).thenReturn(Optional.of(stop));
        org.mockito.Mockito.lenient().when(m.content()).thenReturn(List.of(block));
        org.mockito.Mockito.lenient().when(block.isText()).thenReturn(true);
        org.mockito.Mockito.lenient().when(block.asText()).thenReturn(text);
        org.mockito.Mockito.lenient().when(text.text()).thenReturn(json);
        org.mockito.Mockito.lenient().when(m.usage()).thenReturn(usage);
        org.mockito.Mockito.lenient().when(usage.inputTokens()).thenReturn(1200L);
        org.mockito.Mockito.lenient().when(usage.outputTokens()).thenReturn(150L);
        org.mockito.Mockito.lenient().when(usage.cacheReadInputTokens()).thenReturn(Optional.of(900L));
        return m;
    }

    private static TripChatInterpreter interpreter() {
        AnthropicClient client = mock(AnthropicClient.class);
        when(client.withOptions(any())).thenReturn(client);
        return interpreter(client);
    }

    private static TripChatInterpreter interpreter(AnthropicClient client) {
        return new TripChatInterpreter(client, new AnthropicProperties("claude-haiku-5-5", "claude-sonnet-5-5", 64000),
                new ObjectMapper());
    }
}
