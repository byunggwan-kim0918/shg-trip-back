package com.shg.trip.shgtrip.domain.place.integrity;

import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장된 일정(스텝·대안·표지)이 가리키는 장소가 적재·교체 뒤에도 같은 곳인지 본다. 일정은 장소 행을 그대로 참조해
 * 행이 다른 곳으로 바뀌면 사용자의 일정 화면도 말없이 바뀐다.
 */
@Component
@RequiredArgsConstructor
public class PlaceReferenceGuard {

    /** 같은 장소의 좌표 보정으로 보는 거리. 넘으면 다른 곳으로 옮겨진 것으로 센다. */
    static final double MOVED_KM = 1.0;

    private static final String REFERENCED_IDS = """
            SELECT place_id AS id FROM itinerary_steps WHERE place_id IS NOT NULL
            UNION SELECT place_id FROM alternative_options WHERE place_id IS NOT NULL
            UNION SELECT cover_place_id FROM itineraries WHERE cover_place_id IS NOT NULL
            """;

    private final JdbcTemplate jdbcTemplate;

    public record Ref(String nameKey, double latitude, double longitude, boolean active) {}

    /**
     * @param missing     장소 행이 없어진 참조 id. 일정이 장소를 잃는다
     * @param renamed     이름 키가 바뀐 id
     * @param moved       {@link #MOVED_KM}보다 멀리 옮겨진 id
     * @param deactivated 꺼진 id(행은 남아 일정은 보인다)
     */
    public record Report(int referenced, List<Long> missing, List<Long> renamed, List<Long> moved,
                         List<Long> deactivated) {
        public boolean lostPlaces() {
            return !missing.isEmpty();
        }

        public String summary() {
            return "참조 " + referenced + "곳, 사라짐 " + missing.size() + ", 이름 바뀜 " + renamed.size()
                    + ", 옮겨짐 " + moved.size() + ", 꺼짐 " + deactivated.size()
                    + (renamed.isEmpty() ? "" : ", 이름 바뀐 예 " + renamed.subList(0, Math.min(10, renamed.size())));
        }
    }

    /** 참조된 장소 id마다 지금 모습. 장소 행이 없는 참조는 null 값으로 넣는다. */
    public Map<Long, Ref> snapshot() {
        Map<Long, Ref> out = new HashMap<>();
        jdbcTemplate.query("SELECT r.id, p.name, p.latitude, p.longitude, p.active FROM ("
                + REFERENCED_IDS + ") r LEFT JOIN places p ON p.id = r.id", rs -> {
            long id = rs.getLong("id");
            String name = rs.getString("name");
            out.put(id, name == null && rs.getObject("latitude") == null ? null
                    : new Ref(PlaceNames.exactKey(name), rs.getDouble("latitude"), rs.getDouble("longitude"),
                    rs.getBoolean("active")));
        });
        return out;
    }

    public Report compare(Map<Long, Ref> before) {
        return compare(before, snapshot());
    }

    static Report compare(Map<Long, Ref> before, Map<Long, Ref> after) {
        List<Long> missing = new ArrayList<>(), renamed = new ArrayList<>(), moved = new ArrayList<>(),
                deactivated = new ArrayList<>();
        for (Map.Entry<Long, Ref> e : before.entrySet()) {
            Ref was = e.getValue();
            Ref now = after.get(e.getKey());
            if (now == null) {
                if (was != null) missing.add(e.getKey());
                continue;
            }
            if (was == null) continue;
            if (!was.nameKey().equals(now.nameKey())) renamed.add(e.getKey());
            if (GeoUtils.haversine(was.latitude(), was.longitude(), now.latitude(), now.longitude()) > MOVED_KM) {
                moved.add(e.getKey());
            }
            if (was.active() && !now.active()) deactivated.add(e.getKey());
        }
        return new Report(before.size(), missing, renamed, moved, deactivated);
    }
}
