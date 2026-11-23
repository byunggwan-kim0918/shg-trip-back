package com.shg.trip.shgtrip.domain.planning.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.service.DraftPlaceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 초안 장소 이름을 DB 장소에 잇는 규칙만 다시 돌린다(LLM·임베딩 호출 없음).
 * 입력은 {@code [{"id","regions","name","kind"}]} JSON 배열이고, 이름마다 결과 한 줄을 TSV로 남긴다.
 *
 * <p>{@code --eval.mode=resolve --eval.names=<JSON> --eval.out=<결과 폴더>}로 jar를 한 번 실행한다.
 */
@Slf4j
@Component
@Profile("eval")
@RequiredArgsConstructor
class ResolveEvalRunner implements CommandLineRunner {

    @Value("${eval.mode:trip}")
    private String mode;

    @Value("${eval.names:}")
    private String names;

    @Value("${eval.out:build/eval}")
    private String outDir;

    private final ObjectMapper objectMapper;
    private final DraftPlaceResolver resolver;
    private final Destinations destinations;
    private final ApplicationContext context;

    @Override
    public void run(String... args) throws IOException {
        if (!"resolve".equals(mode)) return;
        if (names.isBlank()) throw new IllegalArgumentException("--eval.names=<이름 JSON>이 필요하다");
        Path out = Path.of(outDir);
        Files.createDirectories(out);
        Map<String, Integer> counts = new TreeMap<>();
        try (Writer w = Files.newBufferedWriter(out.resolve("resolve.tsv"), StandardCharsets.UTF_8)) {
            w.write("id\tregions\tname\tkind\tstatus\tmatch\tplace_id\tplace_name\tplace_name_ko\trole\n");
            for (JsonNode row : objectMapper.readTree(Path.of(names).toFile())) {
                List<String> regions = new ArrayList<>();
                row.path("regions").forEach(r -> regions.add(r.asText()));
                String name = row.path("name").asText();
                String kind = row.path("kind").isMissingNode() || row.path("kind").isNull() ? null
                        : row.path("kind").asText();
                DraftPlaceResolver.Resolution r = resolver.resolve(name, kind, regions, cityNames(regions), null);
                Place p = r.place();
                counts.merge(r.status() + (r.match() == null ? "" : "/" + r.match()), 1, Integer::sum);
                w.write(String.join("\t", row.path("id").asText(), String.join(",", regions), clean(name),
                        Objects.toString(kind, ""), r.status(), Objects.toString(r.match(), ""),
                        p == null ? "" : String.valueOf(p.getId()), p == null ? "" : clean(p.getName()),
                        p == null ? "" : clean(p.getNameKo()), p == null ? "" : Objects.toString(p.getPlaceRole(), ""))
                        + "\n");
            }
        }
        log.info("이름 연결 결과 {}", counts);
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private List<String> cityNames(List<String> regions) {
        List<String> out = new ArrayList<>();
        for (String region : regions) {
            Optional<Destination> d = destinations.find(region).or(() -> DomesticCities.find(region));
            d.map(Destination::nameKo).ifPresent(out::add);
        }
        return out;
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ');
    }
}
