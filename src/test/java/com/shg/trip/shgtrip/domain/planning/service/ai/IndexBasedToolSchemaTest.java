package com.shg.trip.shgtrip.domain.planning.service.ai;

import com.anthropic.models.messages.Tool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IndexBasedToolSchema 단위 테스트.
 * Tool 스키마가 올바른 구조로 빌드되는지 검증한다.
 */
class IndexBasedToolSchemaTest {

    @Test
    void buildIndexBasedItineraryTool_returnsToolWithCorrectName() {
        Tool tool = IndexBasedToolSchema.buildIndexBasedItineraryTool();

        assertThat(tool.name()).isEqualTo("generate_itinerary");
    }

    @Test
    void buildIndexBasedItineraryTool_hasDescription() {
        Tool tool = IndexBasedToolSchema.buildIndexBasedItineraryTool();

        assertThat(tool.description()).isPresent();
        assertThat(tool.description().get()).contains("인덱스");
    }

    @Test
    void buildIndexBasedItineraryTool_hasInputSchema() {
        Tool tool = IndexBasedToolSchema.buildIndexBasedItineraryTool();

        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.inputSchema().required()).isPresent();
        assertThat(tool.inputSchema().required().get()).containsExactly(
                "title", "destination", "estimatedCost", "tags", "steps");
    }

    @Test
    void buildIndexBasedItineraryTool_propertiesContainExpectedFields() {
        Tool tool = IndexBasedToolSchema.buildIndexBasedItineraryTool();

        assertThat(tool.inputSchema().properties()).isPresent();
        Tool.InputSchema.Properties properties = tool.inputSchema().properties().get();
        assertThat(properties._additionalProperties()).containsKey("title");
        assertThat(properties._additionalProperties()).containsKey("destination");
        assertThat(properties._additionalProperties()).containsKey("estimatedCost");
        assertThat(properties._additionalProperties()).containsKey("tags");
        assertThat(properties._additionalProperties()).containsKey("steps");
    }

    @Test
    void buildIndexBasedItineraryTool_isIdempotent() {
        Tool tool1 = IndexBasedToolSchema.buildIndexBasedItineraryTool();
        Tool tool2 = IndexBasedToolSchema.buildIndexBasedItineraryTool();

        assertThat(tool1.name()).isEqualTo(tool2.name());
        assertThat(tool1.inputSchema().required().get()).isEqualTo(tool2.inputSchema().required().get());
    }
}
