package com.shg.trip.shgtrip.domain.planning.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.shg.trip.shgtrip.domain.planning.eval.TraceFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** {@code src/test/resources/fixtures}의 생성 기록 JSON을 읽는다. */
final class TraceFixtures {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private TraceFixtures() {}

    static TraceFile load(String resource) {
        try (InputStream in = TraceFixtures.class.getResourceAsStream(resource)) {
            return TraceFile.parse(MAPPER, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
