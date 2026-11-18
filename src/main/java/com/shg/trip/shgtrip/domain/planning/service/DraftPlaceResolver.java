package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 초안의 장소 이름을 DB 장소에 잇는다. 같은 이름 > 앞부분 일치 > 이름이 질의를 담음 > 질의가 이름을 담음 순이다.
 * 첫 낱말만으로 다시 찾지 않는다. "동명동 카페거리"가 "동명동소주집"으로 이어지는 식의 오연결이 난다.
 */
@Component
@Profile("eval")
@RequiredArgsConstructor
class DraftPlaceResolver {

    /** 질의가 이름을 담는 방향으로 이을 때 이름의 최소 길이. "남산"이 "남산서울타워야경"의 일부로 잡히지 않게 한다. */
    private static final int MIN_CONTAINED_NAME = 3;
    /** 같은 순위 후보가 이만큼 넘게 떨어져 있으면 기준 좌표 없이는 고르지 않는다. */
    private static final double AMBIGUOUS_KM = 1.5;
    private static final Pattern NON_KEY = Pattern.compile("[\\s\\p{P}\\p{S}]+");
    private static final Pattern BRANCH_SUFFIX = Pattern.compile("\\s*본점$");
    /** 명소 이름을 담고 있지만 방문할 곳이 아닌 시설(경포해변 인증센터, 구청 여권과). */
    private static final Pattern NOT_A_PLACE = Pattern.compile(
            "인증센터|인증부스|매표소|주차장|안내소|안내센터|관광안내|여권|민원|구청|시청|군청|정부청사|사무소|관리소|입구|정류장|출구");
    /** 정류장·역 행. 명소 이름을 단 버스 노선 행("해운대해수욕장(09-116)")이 관광지 자리에 잡힌다. */
    private static final Pattern TRANSIT_CATEGORY = Pattern.compile(
            "bus (line|stop|station)|metro station|train station|rail station|subway", Pattern.CASE_INSENSITIVE);

    /** 관광지·식당 자리에 들어가면 안 되는 행. 질의 자체가 그 시설을 찾을 때(출발 역)는 호출부가 거르지 않는다. */
    static boolean notAVisit(String name, String category) {
        return name != null && NOT_A_PLACE.matcher(name).find()
                || category != null && TRANSIT_CATEGORY.matcher(category).find();
    }

    static final Map<String, List<String>> ROLES = Map.of(
            "sight", List.of("SIGHT", "ACTIVITY", "MARKET", "SHOPPING"),
            "experience", List.of("SIGHT", "ACTIVITY", "MARKET", "SHOPPING"),
            "night", List.of("SIGHT", "ACTIVITY", "MARKET", "SHOPPING", "BAR"),
            "market", List.of("MARKET", "SIGHT", "SHOPPING"),
            "shop", List.of("SHOPPING", "MARKET", "SIGHT"),
            "cafe", List.of("CAFE"),
            "meal", List.of("MEAL", "CAFE", "BAR"),
            "lodging", List.of("LODGING"),
            "transit", List.of("TRANSIT"));

    private static final List<String> ANY_VISIT = List.of("SIGHT", "ACTIVITY", "MARKET", "SHOPPING", "CAFE", "MEAL", "BAR");

    private final PlaceRepository placeRepository;

    record Resolution(Place place, String status) {
        static Resolution miss(String status) {
            return new Resolution(null, status);
        }
    }

    /** 이름·공식 메뉴·태그에 음식 이름이 든 식당. 기준 좌표에서 가까운 순이다. */
    List<Place> findMealsByDish(String word, Collection<String> regions, double[] near) {
        String k = key(word);
        if (k.length() < 2 || near == null) return List.of();
        return placeRepository.findMealsByDishNear(k, regions, near[0], near[1]).stream()
                .filter(p -> !NOT_A_PLACE.matcher(p.getName()).find())
                .toList();
    }

    /**
     * @param cityNames 질의 앞에 붙은 도시 이름을 떼는 데 쓴다("수원 통닭거리" → "통닭거리")
     * @param anchor    같은 순위 후보가 여럿일 때 가장 가까운 것을 고르는 기준 좌표. null이면 언어판 수로 고른다
     */
    Resolution resolve(String name, String kind, Collection<String> regions, Collection<String> cityNames,
                       double[] anchor) {
        Resolution last = Resolution.miss("too_short");
        List<String> variants = queryVariants(name, cityNames);
        for (String query : variants) {
            last = resolveKey(query, kind == null ? ROLES.get("sight") : ROLES.getOrDefault(kind, ROLES.get("sight")),
                    regions, anchor, 3);
            if (last.place() != null) return last;
        }
        // 모델이 명소로 적은 카페·빵집(테라로사 커피공장, 성심당)은 이름이 거의 같을 때만 잇는다.
        for (String query : variants) {
            last = resolveKey(query, ANY_VISIT, regions, anchor, 2);
            if (last.place() != null) return last;
        }
        return last;
    }

    /**
     * 시도할 질의 순서. 도시 이름을 붙인 채("전주남부시장"), 뗀 채, 꾸밈말("야경 산책")을 뗀 채, 마지막 낱말("수원화성 화서문" →
     * "화서문")이다. 마지막 낱말은 3자 이상일 때만 쓴다. 첫 낱말은 쓰지 않는다.
     */
    static List<String> queryVariants(String name, Collection<String> cityNames) {
        String trimmed = BRANCH_SUFFIX.matcher(name == null ? "" : name.trim()).replaceAll("");
        List<String> tokens = new java.util.ArrayList<>(List.of(trimmed.split("\\s+")));
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        addWithCity(out, String.join(" ", tokens), cityNames);
        while (tokens.size() > 1 && DESCRIPTORS.contains(tokens.get(tokens.size() - 1))) {
            tokens.remove(tokens.size() - 1);
            addWithCity(out, String.join(" ", tokens), cityNames);
        }
        String lastToken = tokens.get(tokens.size() - 1);
        if (lastToken.endsWith("야시장") && lastToken.length() > 3) {
            addWithCity(out, String.join(" ", tokens.subList(0, tokens.size() - 1)) + " "
                    + lastToken.substring(0, lastToken.length() - 3) + "시장", cityNames);
        }
        if (tokens.size() >= 2 && key(lastToken).length() >= MIN_CONTAINED_NAME && !DESCRIPTORS.contains(lastToken)) {
            out.add(key(lastToken));
        }
        out.removeIf(q -> q.length() < 2);
        return List.copyOf(out);
    }

    private static void addWithCity(java.util.Set<String> out, String text, Collection<String> cityNames) {
        out.add(key(text));
        out.add(queryKey(text, cityNames));
    }

    /** 이름 끝에 붙어 장소 이름이 아닌 꾸밈말. */
    private static final java.util.Set<String> DESCRIPTORS = java.util.Set.of(
            "야경", "산책", "야경산책", "투어", "체험", "일대", "관람", "구경", "야간", "나들이", "방문", "드라이브", "야시장");

    private Resolution resolveKey(String query, List<String> roles, Collection<String> regions, double[] anchor,
                                  int maxTier) {
        List<Place> rows = placeRepository.findActiveByCompactName(query, MIN_CONTAINED_NAME, regions, roles);
        if (rows.isEmpty()) return Resolution.miss("none");

        // 같은 순위면 이름난 곳이 먼저다. "창덕궁"이 글자 수가 가까운 부속 건물("창덕궁 약방")로 잡히지 않게 한다.
        Comparator<Place> order = Comparator.comparingInt((Place p) -> rankTier(bareKey(p.getName()), query))
                .thenComparing((Place p) -> -sitelinks(p))
                .thenComparingInt(p -> Math.abs(bareKey(p.getName()).length() - query.length()));
        if (anchor != null) {
            order = order.thenComparingDouble(p -> distanceKm(anchor, p));
        } else {
            order = order.thenComparing((Place p) -> -sitelinks(p));
        }
        order = order.thenComparingInt(p -> key(p.getName()).length());
        List<Place> ranked = rows.stream()
                .filter(p -> tier(bareKey(p.getName()), query) <= maxTier)
                .filter(DraftPlaceResolver::readableName)
                .filter(p -> !NOT_A_PLACE.matcher(p.getName()).find() || NOT_A_PLACE.matcher(query).find())
                .filter(p -> roles.contains("TRANSIT") || p.getCategory() == null
                        || !TRANSIT_CATEGORY.matcher(p.getCategory()).find())
                .sorted(order).toList();
        if (ranked.isEmpty()) return Resolution.miss("none");
        Place best = ranked.get(0);
        int bestTier = tier(bareKey(best.getName()), query);
        if (anchor == null && bestTier > 0) {
            boolean ambiguous = ranked.stream().skip(1)
                    .filter(p -> tier(bareKey(p.getName()), query) == bestTier && sitelinks(p) >= sitelinks(best))
                    .anyMatch(p -> distanceKm(coords(best), p) > AMBIGUOUS_KM);
            if (ambiguous) return Resolution.miss("ambiguous");
        }
        return new Resolution(best, "ok");
    }

    /** 앞부분 일치와 담음은 같은 순위로 보고 덧붙은 글자가 적은 쪽을 고른다("자갈치시장"은 "부산 자갈치시장"이 "자갈치 시장 경매장"보다 낫다). */
    private static int rankTier(String name, String query) {
        int t = tier(name, query);
        return t == 1 ? 2 : t;
    }

    /** 0 같은 이름, 1 앞부분, 2 이름이 질의를 담음, 3 질의가 이름을 담음, 4 해당 없음. */
    static int tier(String name, String query) {
        if (name.equals(query)) return 0;
        if (name.startsWith(query)) return 1;
        if (name.contains(query)) return 2;
        if (name.length() >= MIN_CONTAINED_NAME && query.contains(name)) return 3;
        return 4;
    }

    static String queryKey(String name, Collection<String> cityNames) {
        String trimmed = BRANCH_SUFFIX.matcher(name == null ? "" : name.trim()).replaceAll("");
        String q = key(trimmed);
        for (String city : cityNames) {
            String c = key(city);
            if (!c.isEmpty() && q.startsWith(c) && q.length() > c.length() + 1) {
                return q.substring(c.length());
            }
        }
        return q;
    }

    /** 괄호 안 한자·영문·설명을 뺀 이름. "불국사 (佛國寺)"가 "불국사장"보다 "불국사"에 가깝게 잡히게 한다. */
    static String bareKey(String name) {
        String bare = name == null ? "" : PARENTHESES.matcher(name).replaceAll("");
        String k = key(bare);
        return k.isEmpty() ? key(name) : k;
    }

    private static final Pattern PARENTHESES = Pattern.compile("[(\\[（【].*?[)\\]）】]");

    /** 한글 이름이 없는 국내 행(위키데이터의 일본어 표제 "石窟庵と仏国寺")은 일정에 그대로 보여 줄 수 없다. */
    private static boolean readableName(Place p) {
        if (p.getNameKo() != null && !p.getNameKo().isBlank()) return true;
        return !"KR".equals(p.getCountry()) || HANGUL.matcher(p.getName()).find();
    }

    private static final Pattern HANGUL = Pattern.compile("[가-힣]");

    static String key(String s) {
        return s == null ? "" : NON_KEY.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static int sitelinks(Place p) {
        return p.getWikidataSitelinks() == null ? 0 : p.getWikidataSitelinks();
    }

    private static double distanceKm(double[] anchor, Place p) {
        return GeoUtils.haversine(anchor, coords(p));
    }

    static double[] coords(Place p) {
        return new double[]{p.getLatitude().doubleValue(), p.getLongitude().doubleValue()};
    }
}
