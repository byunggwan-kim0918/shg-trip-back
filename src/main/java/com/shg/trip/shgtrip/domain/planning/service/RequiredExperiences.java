package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest.RequiredExperience;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceData;
import com.shg.trip.shgtrip.domain.planning.dto.StepData;
import lombok.extern.slf4j.Slf4j;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 이름으로 맞추지 못한 꼭 가고 싶은 곳("광안리 야경")을, 지역 낱말이 이름에 든 관광 후보 한 곳에 의도로 싣는다.
 * 실린 후보는 트림·교체에서 빠지고 선택 결과에 없으면 주입된다. userSelected가 아니라 영업시간·휴무·귀가 가드는 그대로 받는다.
 *
 * <p>스케줄 전 후보 목록에서 의도가 실린 곳은 여기서 실은 곳뿐이다. 선택 결과의 timing·highlight는 스케줄 안에서
 * 따로 실린다({@link IntentHints#apply}). 요청이 없는 기록 재채점은 이 규약으로 필수 경험 장소를 다시 찾는다({@link #placeIds}).
 */
@Slf4j
public final class RequiredExperiences {

    private RequiredExperiences() {}

    private static final int MIN_KEYWORD_LENGTH = 2;
    private static final Set<String> INTENT_TIMES = Set.of("morning", "evening");

    record Matched(RequiredExperience experience, PlaceCandidate place) {}

    /** @param candidates 맞춘 장소에 의도를 실은 후보 목록. 맞춘 것이 없으면 입력 그대로다 */
    record Match(List<PlaceCandidate> candidates, List<Matched> matched, List<RequiredExperience> unmatched) {

        Set<Long> placeIds() {
            return matched.stream().map(m -> m.place().placeId()).filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    static Match attach(List<RequiredExperience> experiences, List<PlaceCandidate> candidates) {
        if (experiences == null || experiences.isEmpty()) return new Match(candidates, List.of(), List.of());
        Map<Integer, PlaceCandidate> marked = new LinkedHashMap<>();
        Map<RequiredExperience, Integer> placeOf = new LinkedHashMap<>();
        List<RequiredExperience> unmatched = new ArrayList<>();
        for (RequiredExperience e : experiences) {
            if (e == null) continue;
            PlaceCandidate best = bestMatch(e.areaKeyword(), candidates);
            if (best == null) {
                unmatched.add(e);
                continue;
            }
            // 같은 곳을 다르게 말한 경험("광안리 야경", "광안리 밤바다")은 한 장소에 모으고 먼저 말한 시간대를 따른다.
            PlaceCandidate current = marked.getOrDefault(best.index(), best);
            String timing = current.intentTime() != null ? current.intentTime() : timingOf(e);
            marked.put(best.index(), best.withIntent(true, timing));
            placeOf.putIfAbsent(e, best.index());
        }
        List<Matched> matched = placeOf.entrySet().stream()
                .map(en -> new Matched(en.getKey(), marked.get(en.getValue())))
                .toList();
        if (!matched.isEmpty()) {
            log.info("필수 경험 반영: {}", matched.stream()
                    .map(m -> m.experience().text() + " → " + m.place().name()
                            + (m.place().intentTime() != null ? "(" + m.place().intentTime() + ")" : ""))
                    .toList());
        }
        if (!unmatched.isEmpty()) {
            log.info("필수 경험 후보 없음: {}", unmatched.stream().map(RequiredExperience::text).toList());
        }
        List<PlaceCandidate> out = marked.isEmpty() ? candidates : candidates.stream()
                .map(c -> c != null && marked.containsKey(c.index()) ? marked.get(c.index()) : c)
                .toList();
        return new Match(out, matched, unmatched);
    }

    /** 스케줄 전 후보 목록의 필수 경험 장소 id. 요청이 남지 않는 기록 재채점용이다. */
    public static Set<Long> placeIds(List<PlaceCandidate> candidates) {
        return candidates.stream()
                .filter(c -> c != null && c.intentProtected() && c.placeId() != null)
                .map(PlaceCandidate::placeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 맞춘 장소가 최종 일정에 없는 경험. 휴무·영업시간·출발 시각 가드로 빠질 수 있다. */
    static List<String> dropped(Match match, List<StepData> steps) {
        Set<Long> placedIds = steps.stream().map(StepData::place).filter(Objects::nonNull)
                .map(PlaceData::placeId).filter(Objects::nonNull).collect(Collectors.toSet());
        List<String> out = match.matched().stream()
                .filter(m -> m.place().placeId() == null || !placedIds.contains(m.place().placeId()))
                .map(m -> m.experience().text())
                .toList();
        if (!out.isEmpty()) log.warn("필수 경험 장소가 최종 일정에서 빠짐: {}", out);
        return out;
    }

    /** 맞추지 못했고 최종 일정에도 이름이 든 장소가 없는 경험. 선택 단계가 식당 같은 다른 역할로 넣었으면 안내하지 않는다. */
    static List<String> unplaced(Match match, List<StepData> steps) {
        List<String> placedNames = steps.stream().map(StepData::place).filter(Objects::nonNull)
                .map(p -> key(p.name()) + "|" + key(p.nameKo())).toList();
        return match.unmatched().stream()
                .filter(e -> keysOf(e.areaKeyword()).stream()
                        .noneMatch(k -> placedNames.stream().anyMatch(n -> n.contains(k))))
                .map(RequiredExperience::text)
                .toList();
    }

    // 검증을 거치지 않는 요청(큐레이션)도 있어 모르는 시간대는 버린다.
    private static String timingOf(RequiredExperience e) {
        String t = e.timing() == null ? null : e.timing().strip().toLowerCase(Locale.ROOT);
        return t != null && INTENT_TIMES.contains(t) ? t : null;
    }

    private static PlaceCandidate bestMatch(String areaKeyword, List<PlaceCandidate> candidates) {
        for (String k : keysOf(areaKeyword)) {
            // 이름이 낱말로 시작하는 곳, 그다음 언어판이 많은 곳
            PlaceCandidate best = candidates.stream()
                    .filter(c -> c != null && isEligible(c))
                    .filter(c -> key(c.name()).contains(k) || key(c.nameKo()).contains(k))
                    .min(Comparator.comparing((PlaceCandidate c) -> !key(c.name()).startsWith(k)
                                    && !key(c.nameKo()).startsWith(k))
                            .thenComparing(c -> c.wikidataSitelinks() != null ? -c.wikidataSitelinks() : 0)
                            .thenComparingInt(PlaceCandidate::index))
                    .orElse(null);
            if (best != null) return best;
        }
        return null;
    }

    // 붙여 쓴 전체("해운대블루라인파크") 다음 첫 낱말(지역)까지만 본다. 뒤 낱말("산책")로 찾으면 엉뚱한 곳이 걸린다.
    private static List<String> keysOf(String areaKeyword) {
        if (areaKeyword == null || areaKeyword.isBlank()) return List.of();
        String first = areaKeyword.strip().split("\\s+")[0];
        return java.util.stream.Stream.of(key(areaKeyword), key(first))
                .filter(k -> k.length() >= MIN_KEYWORD_LENGTH).distinct().toList();
    }

    // 숙소·교통 대분류는 역할이 관광이어도(호텔 수영장) 받지 않는다. 주입이 숙소로 다뤄 전 일정 숙소를 바꾼다.
    private static boolean isEligible(PlaceCandidate c) {
        if (!CandidatePolicy.roleOf(c).isVisit()) return false;
        String major = PlaceCategoryConstants.majorCategory(c.category());
        if ("LODGING".equals(major) || "TRANSIT_HUB".equals(major)) return false;
        return PlaceCategoryConstants.isVisitableEntity(c.name(), c.address(), c.country())
                && !PlaceCategoryConstants.isAggregatePoi(c.name(), c.category())
                && !PlaceCategoryConstants.isPassengerFacility(c.name());
    }

    private static String key(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·]", "");
    }
}
