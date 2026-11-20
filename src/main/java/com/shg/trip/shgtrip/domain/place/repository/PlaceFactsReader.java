package com.shg.trip.shgtrip.domain.place.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code place_facts}의 여행자용 사실. 채우지 않은 장소는 결과에 없다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceFactsReader {

    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper objectMapper;

    /**
     * @param value     방문 가치 0~3. 0은 여행자가 일부러 갈 곳이 아니다
     * @param partOf    대표 장소 이름. 다른 관광지 안의 시설이거나 같은 곳의 다른 이름일 때만 있다
     * @param reachable 일반 관광객이 들어갈 수 있는지
     * @param closedDays 정기 휴무 요일. 모르면 빈 집합이다
     */
    public record PlaceFact(Integer value, String partOf, Boolean reachable, Set<DayOfWeek> closedDays) {

        public PlaceFact {
            closedDays = closedDays == null || closedDays.isEmpty() ? Set.of() : Set.copyOf(closedDays);
        }

        public boolean notWorthVisiting() {
            return Boolean.FALSE.equals(reachable) || (value != null && value == 0);
        }
    }

    public Map<Long, PlaceFact> find(Collection<Long> placeIds) {
        Map<Long, PlaceFact> out = new HashMap<>();
        if (placeIds == null || placeIds.isEmpty()) return out;
        try {
            named.query("select place_id, facts::text from place_facts where place_id in (:ids)",
                    Map.of("ids", placeIds), rs -> {
                        out.put(rs.getLong(1), parse(rs.getString(2)));
                    });
        } catch (DataAccessException e) {
            // 사실이 없으면 생성은 사실 없이 진행한다(테이블을 아직 채우지 않은 환경).
            log.warn("장소 사실 조회 실패: {}", e.getMessage());
        }
        return out;
    }

    private PlaceFact parse(String json) {
        try {
            JsonNode n = objectMapper.readTree(json);
            Set<DayOfWeek> closed = EnumSet.noneOf(DayOfWeek.class);
            if (n.has("c")) n.get("c").forEach(d -> {
                int i = KO_DAYS.indexOf(d.asText());
                if (i >= 0) closed.add(DayOfWeek.of(i + 1));
            });
            return new PlaceFact(n.hasNonNull("v") ? n.get("v").asInt() : null,
                    n.hasNonNull("p") ? n.get("p").asText() : null,
                    n.hasNonNull("r") ? n.get("r").asBoolean() : null, closed);
        } catch (Exception e) {
            return new PlaceFact(null, null, null, null);
        }
    }

    private static final List<String> KO_DAYS = List.of("월", "화", "수", "목", "금", "토", "일");
}
