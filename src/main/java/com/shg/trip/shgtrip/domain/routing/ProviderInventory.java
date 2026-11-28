package com.shg.trip.shgtrip.domain.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * 교통 데이터·경로 공급자 전체 목록(routing/providers.json). 코드에 연결된 공급자와 인증정보만 있는 공급자를 함께 적고,
 * 겹치는 기능은 역할(CORE·FALLBACK·VALIDATION·DEPRECATED)로 가른다. 파이프라인 스크립트도 같은 파일을 읽는다.
 */
public record ProviderInventory(List<Entry> providers) {

    public static final String RESOURCE = "routing/providers.json";

    public enum Role { CORE, FALLBACK, VALIDATION, DEPRECATED }

    public enum Integration { wired, keyOnly, none }

    /** @param runtimeIds application-routing.yml 공급자 id·검증기 id. '*'로 끝나면 접두사로 맞춘다 */
    public record Entry(String id, String name, String kind, Role role, Integration integration, String wiredIn,
                        List<String> runtimeIds, List<String> keyEnv, List<String> unusedKeyEnv, String note) {
        public Entry {
            runtimeIds = runtimeIds == null ? List.of() : List.copyOf(runtimeIds);
            keyEnv = keyEnv == null ? List.of() : List.copyOf(keyEnv);
            unusedKeyEnv = unusedKeyEnv == null ? List.of() : List.copyOf(unusedKeyEnv);
        }

        boolean matches(String runtimeId) {
            return runtimeIds.stream().anyMatch(r -> r.endsWith("*") ? runtimeId.startsWith(r.substring(0, r.length() - 1))
                    : r.equals(runtimeId));
        }
    }

    public static ProviderInventory load(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return mapper.readerFor(ProviderInventory.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(in);
        } catch (IOException e) {
            throw new IllegalStateException("공급자 목록을 읽지 못했다: " + RESOURCE, e);
        }
    }

    public Optional<Entry> forRuntime(String runtimeId) {
        return providers.stream().filter(e -> e.matches(runtimeId)).findFirst();
    }
}
