package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.WizardOptionIds;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.BALANCED_NOTABLE_PER_DAY;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.FILL_OPPOSITE_RESERVE;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.FILL_VARIETY;
import static com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning.WEAK_FILL_NOTABLE_SITELINKS;

/**
 * 여행자가 원하는 유명 장소와 덜 알려진 장소의 섞임.
 * 검색 후보 풀은 그대로 두고 초안 요청과 빈 시간을 채울 근처 후보의 구성만 바꾼다. 검색 풀에서 유명한 곳을 줄이면
 * 덜 알려진 곳이 모자란 날 대신 넣을 곳이 없어 하루가 빈다.
 */
public enum FameMix {
    BALANCED,
    /** 사람 적은 곳, 한적한 곳 위주. */
    QUIET;

    public static FameMix of(Collection<String> themes) {
        return themes != null && themes.contains(WizardOptionIds.QUIET_THEME) ? QUIET : BALANCED;
    }

    /** 초안 요청에 적는 성향. BALANCED는 적지 않는다(초안 규칙이 이미 대표 명소를 뼈대로 섞는다). */
    String draftLine() {
        return this == QUIET ? "한적한 곳 위주" : null;
    }

    /**
     * 빈 시간 근처 후보를 고를 때 유명도에 곱하는 방향. 1이면 유명한 곳 쪽, -1이면 덜 알려진 곳 쪽이다.
     *
     * @param dayPlaces 그날 이미 넣은 장소(초안 뼈대)
     */
    double fillDirection(List<PlaceCandidate> dayPlaces) {
        if (this == QUIET) return -1;
        return notableCount(dayPlaces) >= BALANCED_NOTABLE_PER_DAY ? -1 : 1;
    }

    /** 대표 명소는 빈 시간 보충의 "명소" 경계와 같은 언어판 수로 센다. 경기장은 언어판이 많아도 여행자가 찾는 명소가 아니다. */
    static boolean isNotable(PlaceCandidate c) {
        Integer sitelinks = PlaceProminence.effectiveSitelinks(c.wikidataSitelinks(), c.travelerFame());
        return sitelinks != null && sitelinks >= WEAK_FILL_NOTABLE_SITELINKS
                && !PlaceCategoryConstants.isEventVenue(c.name(), c.category());
    }

    static long notableCount(List<PlaceCandidate> places) {
        if (places == null) return 0;
        return places.stream().filter(Objects::nonNull).filter(FameMix::isNotable).count();
    }

    /**
     * 짓기({@code DayBuilder})는 이 후보 중 시간에 맞는 가장 가까운 곳을 넣어, 덜 알려진 곳만 남기면 그곳들이 맞지 않는 날 빈다.
     * 그래서 덜 알려진 쪽으로 고를 때도 유명한 곳을 섞되, 가까우면 그 유명한 곳이 먼저 들어간다.
     *
     * @param seed {@link #seedOf}로 만든 요청 씨앗
     */
    static List<PlaceCandidate> pickNearby(List<PlaceCandidate> eligible, double direction, long seed, int limit) {
        List<PlaceCandidate> leaning = eligible.stream()
                .sorted(Comparator.comparingDouble(c -> -nearbyScore(c, direction, seed))).toList();
        int reserve = direction < 0 ? FILL_OPPOSITE_RESERVE : 0;
        int lead = Math.max(0, Math.min(limit - reserve, leaning.size()));
        List<PlaceCandidate> picked = new ArrayList<>(leaning.subList(0, lead));
        leaning.subList(lead, leaning.size()).stream()
                .sorted(Comparator.comparingDouble(c -> -nearbyScore(c, -direction, seed)))
                .limit(Math.max(0, limit - lead))
                .forEach(picked::add);
        return picked;
    }

    /** 고르는 점수(클수록 먼저). 흔들림은 씨앗과 장소 id로 정해 같은 요청은 같은 결과다. */
    static double nearbyScore(PlaceCandidate c, double direction, long seed) {
        double variety = c.placeId() == null ? 0 : new SplittableRandom(seed ^ c.placeId()).nextDouble() * FILL_VARIETY;
        return direction * PlaceProminence.of(c) + variety;
    }

    /**
     * 여행지·날짜·동행·취향이 같으면 같은 값이다. 문자열만 이어 해시해 JVM이 달라도 같다(enum의 hashCode는 실행마다 다르다).
     * 평가 재생이 같은 결과를 내고, 다른 요청끼리는 고르는 곳이 달라진다.
     */
    static long seedOf(String... parts) {
        return String.join("\u001f", parts).hashCode();
    }
}
