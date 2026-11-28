package com.shg.trip.shgtrip.domain.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 공급자 목록(providers.json)이 실제 설정·코드와 어긋나지 않는지 본다. */
class ProviderInventoryTest {

    private final ProviderInventory inventory = ProviderInventory.load(new ObjectMapper());

    private static Map<String, RoutingProperties.ProviderSettings> configured() throws IOException {
        var env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("routing", new ClassPathResource("application-routing.yml"))
                .forEach(env.getPropertySources()::addFirst);
        return Binder.get(env).bind("routing", RoutingProperties.class).get().providers();
    }

    @Test
    void 설정의_모든_공급자는_목록에_연결된_공급자로_있다() throws IOException {
        for (String id : configured().keySet()) {
            assertThat(inventory.forRuntime(id)).as(id).get()
                    .extracting(ProviderInventory.Entry::integration).isEqualTo(ProviderInventory.Integration.wired);
        }
        assertThat(inventory.forRuntime("tago")).get().extracting(ProviderInventory.Entry::role)
                .isEqualTo(ProviderInventory.Role.VALIDATION);
    }

    @Test
    void 연결된_실행_공급자는_설정에_있고_연결되지_않은_공급자는_실행_id가_없다() throws IOException {
        var ids = configured().keySet();
        for (var e : inventory.providers()) {
            if (e.integration() != ProviderInventory.Integration.wired) {
                assertThat(e.runtimeIds()).as(e.id()).isEmpty();
                assertThat(e.wiredIn()).as(e.id()).isNull();
                continue;
            }
            for (String r : e.runtimeIds()) {
                if (r.equals("tago")) continue;
                assertThat(ids).as(e.id() + " " + r)
                        .anyMatch(id -> r.endsWith("*") ? id.startsWith(r.substring(0, r.length() - 1)) : id.equals(r));
            }
        }
    }

    @Test
    void 요구한_공급자를_모두_역할과_함께_적는다() {
        List<String> required = List.of("mobility-database", "transitland", "tago", "ktdb", "odpt", "kakao-mobility",
                "tmap", "tfl", "lta", "db", "prim", "ors");
        assertThat(inventory.providers()).extracting(ProviderInventory.Entry::id).containsAll(required);
        assertThat(inventory.providers()).allSatisfy(e -> {
            assertThat(e.role()).isNotNull();
            assertThat(e.keyEnv()).allMatch(k -> k.matches("[A-Z][A-Z0-9_]*"));
        });
    }
}
