package com.shg.trip.shgtrip.domain.place.destination;

import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 여행지 사전(destinations) 조회. 일정 생성은 여전히 {@link Destinations}·{@link DomesticCities}로 도시를 정하고,
 * 이 사전은 그 목록 밖 이름이 준비 중인 곳인지, 이름만 아는 곳인지를 알리고 요청 수를 남기는 데 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DestinationCatalog {

    public enum Status { ACTIVE, PREPARING, CANDIDATE }

    public record Entry(String key, String country, String kind, String name, String nameKo, Status status) {
        public String shownName() {
            return nameKo != null && !nameKo.isBlank() ? nameKo : name;
        }
    }

    private final JdbcTemplate jdbcTemplate;

    /**
     * 이름(한국어 이름·별칭 포함)이 정확 키로 같은 여행지. 같은 이름이 여럿이면 준비된 곳, 사람이 사는 곳, 인구가 많은 곳 순이다.
     * 조회가 실패하면 비어 있다(안내 문구만 기본값이 된다).
     *
     * @param country 비어 있지 않으면 그 나라 안에서만 찾는다
     */
    public Optional<Entry> lookup(String text, String country) {
        String key = PlaceNames.exactKey(text);
        if (key.length() < 2) return Optional.empty();
        boolean anyCountry = country == null || country.isBlank();
        try {
            return queryFirst(key, anyCountry, country);
        } catch (RuntimeException e) {
            log.warn("여행지 사전 조회 실패({}): {}", text, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Entry> queryFirst(String key, boolean anyCountry, String country) {
        List<Entry> found = jdbcTemplate.query("""
                SELECT destination_key, country, kind, name, name_ko, status FROM destinations
                WHERE alias_keys @> ARRAY[CAST(? AS text)] AND (? OR country = ?)
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'PREPARING' THEN 1 ELSE 2 END,
                         kind = 'town' DESC, population DESC, destination_key
                LIMIT 1
                """, (rs, i) -> new Entry(rs.getString("destination_key"), rs.getString("country"),
                rs.getString("kind"), rs.getString("name"), rs.getString("name_ko"),
                Status.valueOf(rs.getString("status"))), key, anyCountry, anyCountry ? "" : country.trim());
        return found.stream().findFirst();
    }

    /** 생성 요청이 들어온 준비 전 여행지. 어느 후보부터 데이터를 모을지 정하는 근거다. */
    public void recordRequest(String destinationKey) {
        try {
            jdbcTemplate.update("""
                    UPDATE destinations SET requested_count = requested_count + 1, last_requested_at = now()
                    WHERE destination_key = ? AND status <> 'ACTIVE'""", destinationKey);
        } catch (RuntimeException e) {
            log.warn("여행지 요청 기록 실패({}): {}", destinationKey, e.getMessage());
        }
    }

    /** 사전에 넣을 이름 키. 이름·한국어 이름·별칭을 같은 규칙으로 만든다. */
    public static List<String> aliasKeys(String name, String nameKo, List<String> aliases) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (String n : new String[]{name, nameKo}) keys.add(PlaceNames.exactKey(n));
        if (aliases != null) aliases.forEach(a -> keys.add(PlaceNames.exactKey(a)));
        keys.removeIf(k -> k.length() < 2);
        return List.copyOf(keys);
    }
}
