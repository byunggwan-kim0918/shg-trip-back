package com.shg.trip.shgtrip.domain.place.batch;

import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 한국어 표시 이름 보정표({@value #RESOURCE})를 적용한다. 보강·Wikidata 매칭 뒤에 돌아 그 값을 덮는다.
 * 표만 고치면 재보강 없이 다음 배치에 반영된다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class NameKoOverrideJob {

    static final String RESOURCE = "name-ko-overrides.tsv";

    private static final Pattern QID = Pattern.compile("Q[0-9]+");
    private static final Pattern NAME_KEY = Pattern.compile("([A-Z]{2}):(.+)");

    private final PlaceRepository placeRepository;

    /** @param qid Wikidata 항목 id. null이면 country·name으로 찾는다. */
    record Override(String qid, String country, String name, String nameKo) {}

    public void run() {
        List<Override> overrides = parse(read());
        int changed = 0;
        for (Override o : overrides) {
            changed += o.qid() != null
                    ? placeRepository.overrideNameKoByQid(o.qid(), o.nameKo())
                    : placeRepository.overrideNameKoByName(o.country(), o.name(), o.nameKo());
        }
        log.info("한국어 이름 보정표 적용 - 항목 {}개, 바뀐 장소 {}곳", overrides.size(), changed);
    }

    private static List<String> read() {
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(RESOURCE + " 읽기 실패", e);
        }
    }

    /** 잘못된 줄이 하나라도 있으면 전부 거부한다. 일부만 적용하면 고친 줄이 반영됐는지 알 수 없다. */
    static List<Override> parse(List<String> lines) {
        List<Override> result = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] cols = line.split("\t");
            if (cols.length != 2 || cols[1].isBlank()) {
                throw new IllegalStateException(RESOURCE + " " + (i + 1) + "행 형식 오류: " + line);
            }
            String key = cols[0].strip();
            String nameKo = cols[1].strip();
            if (!keys.add(key)) throw new IllegalStateException(RESOURCE + " " + (i + 1) + "행 키 중복: " + key);
            if (QID.matcher(key).matches()) {
                result.add(new Override(key, null, null, nameKo));
                continue;
            }
            var m = NAME_KEY.matcher(key);
            if (!m.matches()) throw new IllegalStateException(RESOURCE + " " + (i + 1) + "행 키 오류: " + key);
            result.add(new Override(null, m.group(1), m.group(2).strip(), nameKo));
        }
        return result;
    }
}
