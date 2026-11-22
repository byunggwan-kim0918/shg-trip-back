package com.shg.trip.shgtrip.domain.tripchat.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.service.PlanningCountries;
import com.shg.trip.shgtrip.domain.planning.service.TripFeasibility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 여행지를 정하지 못한 여행자에게 추천해도 되는 곳. 생성을 열어 둔 나라의 도시 가운데 장소 데이터로 일정을 지을 수 있는
 * 곳만 담는다. 추천한 곳이 생성에서 데이터 부족으로 막히면 추천하지 않은 것보다 나쁘다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DestinationCandidates {

    /** 짧은 여행 기준. 이보다 긴 여행은 생성 단계가 다시 데이터를 잰다. */
    private static final long CHECK_DAYS = 3;
    private static final Duration REFRESH = Duration.ofHours(24);

    private final PlaceRepository placeRepository;
    private final Destinations destinations;
    private final PlanningCountries planningCountries;

    /** @param places 장소 수. 모델이 고르지 못했을 때 데이터가 많은 곳부터 내놓는다 */
    public record Candidate(String nameKo, long places) {}

    private record Snapshot(List<Candidate> candidates, Instant at) {}

    private volatile Snapshot snapshot;

    /** @return 장소가 많은 순 */
    public List<Candidate> list() {
        Snapshot now = snapshot;
        if (now != null && now.at().plus(REFRESH).isAfter(Instant.now())) return now.candidates();
        synchronized (this) {
            now = snapshot;
            if (now == null || now.at().plus(REFRESH).isBefore(Instant.now())) {
                List<Candidate> loaded = load();
                // 데이터를 채우는 중에 기동하면 비어 있을 수 있어 빈 목록은 남겨 두지 않는다.
                now = new Snapshot(loaded, Instant.now());
                if (!loaded.isEmpty()) snapshot = now;
            }
            return now.candidates();
        }
    }

    private List<Candidate> load() {
        List<Destination> all = new ArrayList<>(DomesticCities.all());
        destinations.all().stream()
                .filter(d -> d.isCity() && d.parent() == null && planningCountries.isEnabled(d.country()))
                .forEach(all::add);
        List<Candidate> out = new ArrayList<>();
        for (Destination d : all) {
            Map<String, Long> roles = new LinkedHashMap<>();
            for (Object[] row : placeRepository.countRolesByRegions(List.of(d.name()))) {
                roles.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
            }
            if (!TripFeasibility.enoughPlaceData(roles, CHECK_DAYS)) continue;
            out.add(new Candidate(d.nameKo(), roles.values().stream().mapToLong(Long::longValue).sum()));
        }
        out.sort(Comparator.comparingLong(Candidate::places).reversed());
        log.info("추천 여행지 후보 {}곳", out.size());
        return List.copyOf(out);
    }
}
