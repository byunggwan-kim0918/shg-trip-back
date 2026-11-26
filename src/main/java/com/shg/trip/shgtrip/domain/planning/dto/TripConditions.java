package com.shg.trip.shgtrip.domain.planning.dto;

import com.shg.trip.shgtrip.domain.itinerary.dto.PlaceRestriction;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 일정이 지켜야 하는 여행 조건. 최종 검사가 이것으로 일정을 받아들일지 정한다.
 *
 * @param wants      사용자가 넣어 달라고 한 장소·음식·경험
 * @param cityDays   도시별 머무는 날 수. 정하지 않았으면 비어 있다
 * @param avoid      피할 음식·활동
 * @param lowMobility 오래 걷기 힘든 동행(부모님, 걷기 힘들다고 한 경우)
 * @param transport  car·transit·walk
 * @param arrival    첫날 도착 시각. 없으면 null
 * @param departure  마지막 날 출발 시각. 없으면 null
 * @param pace       relaxed·normal·tight. 모르면 null
 * @param chosenPlaceIds 사용자가 직접 고른 장소. 피할 조건에 걸려도 사용자가 고른 것이라 짓는 쪽이 남긴다
 * @param layout     사용자가 말한 들를 지역과 밤마다 묵을 도시
 */
public record TripConditions(List<Want> wants, Map<String, Integer> cityDays, List<PlaceRestriction> avoid,
                             boolean lowMobility, String transport, LocalTime arrival, LocalTime departure,
                             String pace, Set<Long> chosenPlaceIds, Layout layout) {

    public TripConditions {
        chosenPlaceIds = chosenPlaceIds == null ? Set.of() : Set.copyOf(chosenPlaceIds);
        layout = layout == null ? Layout.NONE : layout;
    }

    public TripConditions(List<Want> wants, Map<String, Integer> cityDays, List<PlaceRestriction> avoid,
                          boolean lowMobility, String transport, LocalTime arrival, LocalTime departure,
                          String pace, Set<Long> chosenPlaceIds) {
        this(wants, cityDays, avoid, lowMobility, transport, arrival, departure, pace, chosenPlaceIds, Layout.NONE);
    }

    /**
     * @param radiusKm 이 거리 안의 활동을 그 지역에 간 것으로 본다
     * @param rangeKm  이 거리 안의 숙소와 구간 출발지를 그 도시 것으로 본다. 도시 둘레 숙소를 넣으려고 radiusKm보다 넓다
     */
    public record Area(String name, double lat, double lng, double radiusKm, double rangeKm) {

        /** 좌표를 모르면 아주 먼 곳으로 본다. */
        public double kmTo(double[] at) {
            return at == null ? Double.MAX_VALUE : GeoUtils.haversine(at, new double[]{lat, lng});
        }

        /** 그 도시의 숙소·구간 출발지로 볼 만큼 가까운지. */
        public boolean covers(double[] at) {
            return kmTo(at) <= rangeKm;
        }

        public boolean coversPlace(PlaceCandidate place) {
            return place != null && place.latitude() != null && place.longitude() != null
                    && covers(new double[]{place.latitude().doubleValue(), place.longitude().doubleValue()});
        }
    }

    /** @param mode 그 지역에서 다니는 방법(walk·car·transit·taxi·any) */
    public record AreaMode(Area area, String mode) {}

    /**
     * @param visitAreas 활동이 하나 이상 있어야 하는 지역
     * @param nights     밤(1부터)마다 묵어야 하는 도시
     * @param modes      지역별 이동 방법. 말한 순서다
     */
    public record Layout(List<Area> visitAreas, Map<Integer, Area> nights, List<AreaMode> modes) {
        public static final Layout NONE = new Layout(List.of(), Map.of(), List.of());

        public Layout {
            visitAreas = visitAreas == null ? List.of() : List.copyOf(visitAreas);
            nights = nights == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(nights));
            modes = modes == null ? List.of() : List.copyOf(modes);
        }

        public String modeAt(double[] at, String fallback) {
            return TripConditions.modeAt(modes, at, fallback);
        }
    }

    /**
     * 좌표가 든 지역 가운데 가장 가까운 지역의 이동 방법. 말한 지역 밖이면 fallback이다. 짓기와 검사가 이 판정 하나를
     * 같이 써야 같은 구간을 두고 수단과 거리 상한이 갈리지 않는다.
     */
    public static String modeAt(List<AreaMode> modes, double[] at, String fallback) {
        if (at == null) return fallback;
        AreaMode best = null;
        double bestKm = Double.MAX_VALUE;
        for (AreaMode m : modes) {
            double km = m.area().kmTo(at);
            if (km <= m.area().rangeKm() && km < bestKm) {
                best = m;
                bestKm = km;
            }
        }
        return best == null ? fallback : best.mode();
    }

    public TripConditions(List<Want> wants, Map<String, Integer> cityDays, List<PlaceRestriction> avoid,
                          boolean lowMobility, String transport, LocalTime arrival, LocalTime departure,
                          String pace) {
        this(wants, cityDays, avoid, lowMobility, transport, arrival, departure, pace, Set.of());
    }

    /** 요구를 일정에 넣을 수 있는지. 사용자가 그 요구를 원하는지와 따로 본다. */
    public enum Reach {
        /** 채울 장소를 데이터에서 찾았고 넣을 날도 있다. */
        PLACEABLE,
        /** 채울 장소를 데이터에서 찾지 못했다. 그런 곳이 없다는 뜻이 아니다. */
        NOT_FOUND,
        /** 날짜·도착·출발·해 뜨고 지는 시각으로 넣을 날이 없다. */
        NO_SLOT
    }

    /**
     * @param text     요청에 쓴 말("성산일출봉 일출", "고등어회")
     * @param timing   sunrise·evening. 시각이 없는 요청이면 null
     * @param satisfy  이 요청을 채우는 장소 이름. 식당은 메뉴 정보가 없을 때만 이름으로 본다
     * @param food     먹어야 하는 음식. 식당 이름·메뉴에 이 말이 있어야 채운 것으로 본다. 음식 요청이 아니면 null
     * @param reach    넣을 수 있는지. null이면 {@link Reach#PLACEABLE}
     * @param required 사용자가 꼭 갈 곳·꼭 할 경험으로 직접 정한 요구인지. 초안이 요청 문장에서 읽은 요구는 false다
     * @param accepted 넣지 못한다는 것을 대화에서 듣고도 그대로 진행하기로 했는지
     * @param localNames 채우는 장소의 현지어 표기. 장소 원래 이름과 정확히 같을 때만 채운 것으로 본다
     * @param foodAliases food와 같은 음식의 다른 표기. 식당 이름·메뉴에 이 중 하나가 있어도 채운 것으로 본다
     */
    public record Want(String text, String timing, List<String> satisfy, String food, Reach reach,
                       boolean required, boolean accepted, List<String> localNames, List<String> foodAliases) {

        public Want {
            reach = reach == null ? Reach.PLACEABLE : reach;
            localNames = localNames == null ? List.of() : List.copyOf(localNames);
            foodAliases = foodAliases == null ? List.of() : List.copyOf(foodAliases);
        }

        public Want(String text, String timing, List<String> satisfy, String food, Reach reach,
                    boolean required, boolean accepted) {
            this(text, timing, satisfy, food, reach, required, accepted, List.of(), List.of());
        }

        public Want(String text, String timing, List<String> satisfy, String food) {
            this(text, timing, satisfy, food, Reach.PLACEABLE, false, false);
        }
    }
}
