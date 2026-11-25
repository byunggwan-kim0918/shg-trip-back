package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.catalog.CatalogPlace;
import com.shg.trip.shgtrip.domain.place.catalog.PlaceCatalog;
import com.shg.trip.shgtrip.domain.place.catalog.PlaceCatalogPromoter;
import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.name.LocalNameCheck;
import com.shg.trip.shgtrip.domain.place.name.PlaceNames;
import com.shg.trip.shgtrip.domain.place.name.PlaceNames.Match;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 초안의 장소 이름을 DB 장소에 잇는다. 같은 이름 > 앞부분 일치 > 이름이 질의를 담음 > 질의가 이름을 담음 순이다.
 * 첫 낱말만으로 다시 찾지 않는다. "동명동 카페거리"가 "동명동소주집"으로 이어지는 식의 오연결이 난다.
 */
@Component
@RequiredArgsConstructor
public class DraftPlaceResolver {

    /** 질의가 이름을 담는 방향으로 이을 때 이름의 최소 길이. "남산"이 "남산서울타워야경"의 일부로 잡히지 않게 한다. */
    private static final int MIN_CONTAINED_NAME = 3;
    /** 앞뒤 두 글자씩은 남아야 같은 곳의 다른 이름으로 본다. */
    private static final int MIN_INFIXED_NAME = 4;
    /** 같은 순위 후보가 이만큼 넘게 떨어져 있으면 기준 좌표 없이는 고르지 않는다. */
    private static final double AMBIGUOUS_KM = 1.5;
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

    /** 역할이 아직 없는(보강 전) 행의 카테고리 대분류로 짐작한 역할. */
    private static final Map<String, List<String>> ROLES_BY_MAJOR = Map.of(
            "ATTRACTION", List.of("SIGHT", "ACTIVITY", "MARKET", "SHOPPING"),
            "DINING", List.of("MEAL", "BAR"),
            "CAFE", List.of("CAFE"),
            "LODGING", List.of("LODGING"),
            "TRANSIT_HUB", List.of("TRANSIT"));

    private final PlaceRepository placeRepository;
    private final Destinations destinations;
    private final PlaceCatalog catalog;
    private final PlaceCatalogPromoter promoter;

    /**
     * @param status ok·none·ambiguous·too_short
     * @param match  이름이 맞은 근거. 찾지 못했으면 null
     */
    public record Resolution(Place place, String status, Match match) {
        static Resolution miss(String status) {
            return new Resolution(null, status, null);
        }

        Resolution as(Match weaker) {
            return place == null || match == null || weaker.ordinal() <= match.ordinal() ? this
                    : new Resolution(place, status, weaker);
        }
    }

    /** 이름·공식 메뉴·태그에 음식 이름이 든 식당({@link PlaceNames#mentions}). 기준 좌표에서 가까운 순이다. */
    List<Place> findMealsByDish(String word, Collection<String> regions, double[] near) {
        String k = PlaceNames.exactKey(word);
        if (k.length() < 2 || near == null) return List.of();
        return placeRepository.findMealsByDishNear(k, regions, near[0], near[1]).stream()
                .filter(p -> !NOT_A_PLACE.matcher(p.getName()).find())
                .filter(p -> servesDish(p, word))
                .toList();
    }

    private static boolean servesDish(Place p, String word) {
        List<String> texts = new ArrayList<>();
        texts.add(p.getName());
        texts.add(p.getNameKo());
        if (p.getTags() != null) texts.addAll(p.getTags());
        if (p.getAttributes() != null && p.getAttributes().official() != null) {
            texts.add(p.getAttributes().official().get("firstmenu"));
            texts.add(p.getAttributes().official().get("treatmenu"));
        }
        return texts.stream().filter(Objects::nonNull).anyMatch(t -> PlaceNames.mentions(t, word));
    }

    /**
     * @param cityNames 질의 앞에 붙은 도시 이름을 떼는 데 쓴다("수원 통닭거리" → "통닭거리")
     * @param anchor    같은 순위 후보가 여럿일 때 가장 가까운 것을 고르는 기준 좌표. null이면 언어판 수로 고른다
     */
    public Resolution resolve(String name, String kind, Collection<String> regions, Collection<String> cityNames,
                              double[] anchor) {
        Resolution found = resolveIn(dbLookup(), name, kind, regions, cityNames, anchor);
        if (strong(found) || "ambiguous".equals(found.status()) || !catalog.enabled() || "transit".equals(kind)) {
            return found;
        }
        CatalogLookup lookup = new CatalogLookup(catalog);
        Resolution pulled = found.place() == null ? resolveIn(lookup, name, kind, regions, cityNames, anchor)
                : resolveExact(lookup, name, kind, regions, cityNames, anchor);
        return pulled.place() != null && (found.place() == null || strong(pulled)) ? promoted(lookup, pulled, found) : found;
    }

    /**
     * 초안이 이름으로 고른 장소와 요구의 장소. 한국어 이름 → 현지어 이름(localNames) → 카탈로그 → 이웃 여행지 순으로 찾는다.
     * 현지어 이름과 이웃 여행지는 정확·별칭 일치만 받는다. 모델이 추측한 표기나 경계 밖 지역을 부분 일치로 받으면 이름이
     * 비슷한 다른 장소로 잇는다.
     *
     * @param localNames 현지어 표기. 검색어일 뿐 장소 신원의 근거가 아니다. null이면 없는 것이다
     */
    public Resolution resolveNamed(String name, List<String> localNames, String kind, Collection<String> regions,
                                   Collection<String> cityNames, double[] anchor) {
        List<String> locals = localNames == null || !overseas(regions) ? List.of() : localNames;
        Resolution found = bestOf(dbLookup(), name, locals, kind, regions, cityNames, anchor);
        if (strong(found) || "ambiguous".equals(found.status())) return found;
        if (catalog.enabled()) {
            // DB에는 핵심 장소만 있어, DB에서 이름 일부만 맞은 곳(大涌谷自然研究路)보다 카탈로그에서 이름이 정확히 같은 본체가 낫다.
            CatalogLookup lookup = new CatalogLookup(catalog);
            Resolution pulled = found.place() == null ? bestOf(lookup, name, locals, kind, regions, cityNames, anchor)
                    : exactOf(lookup, name, name, locals, kind, regions, cityNames, anchor);
            if (pulled.place() != null && (found.place() == null || strong(pulled))) return promoted(lookup, pulled, found);
        }
        if (found.place() != null) return found;
        List<String> nearby = neighborRegions(regions);
        if (nearby.isEmpty()) return found;
        Resolution near = unlessCommon(exactOf(dbLookup(), name, name, locals, kind, nearby, cityNames, anchor));
        if (near.place() != null || !catalog.enabled()) return near.place() != null ? near : found;
        CatalogLookup lookup = new CatalogLookup(catalog);
        Resolution pulled = unlessCommon(exactOf(lookup, name, name, locals, kind, nearby, cityNames, anchor));
        return pulled.place() != null ? promoted(lookup, pulled, found) : found;
    }

    /** 같은 이름이 이만큼 많은 여행지에 있으면 흔한 이름("八幡神社")이다. */
    private static final int COMMON_NAME_REGIONS = 3;

    /** 이웃 여행지에서는 거리로 요청한 그곳인지 가릴 수 없어, 같은 나라에 흔한 이름이면 다른 곳의 같은 이름일 수 있다. */
    private Resolution unlessCommon(Resolution r) {
        if (r.place() == null) return r;
        String key = PlaceNames.bareKey(r.place().getName());
        String country = destinations.find(r.place().getRegion()).map(Destination::country).orElse(null);
        if (country == null || key.length() < 2) return r;
        List<String> sameCountry = destinations.all().stream().filter(d -> country.equals(d.country()))
                .map(Destination::name).toList();
        // DB는 여행지마다 일부 장소만 두어 흔한 이름도 한두 곳에만 있을 수 있다.
        List<Place> rows = new ArrayList<>(dbLookup().byCompactName(key, key.length(), sameCountry, ANY_VISIT));
        if (catalog.enabled()) rows.addAll(new CatalogLookup(catalog).byCompactName(key, key.length(), sameCountry, ANY_VISIT));
        long regionsWithName = rows.stream().filter(p -> PlaceNames.bareKey(p.getName()).equals(key))
                .map(Place::getRegion).distinct().count();
        return regionsWithName >= COMMON_NAME_REGIONS ? Resolution.miss("common_name") : r;
    }

    private Resolution bestOf(Lookup lookup, String name, List<String> locals, String kind, Collection<String> regions,
                              Collection<String> cityNames, double[] anchor) {
        Resolution best = resolveIn(lookup, name, kind, regions, cityNames, anchor);
        if (strong(best) || locals.isEmpty()) return best;
        Resolution local = exactOf(lookup, null, name, locals, kind, regions, cityNames, anchor);
        return local.place() != null && (best.place() == null || local.match().ordinal() < best.match().ordinal())
                ? local : best;
    }

    /** @param korean 현지어 이름으로 찾은 장소가 같은 곳인지 견줄 초안의 한국어 이름 */
    private Resolution exactOf(Lookup lookup, String name, String korean, List<String> locals, String kind,
                               Collection<String> regions, Collection<String> cityNames, double[] anchor) {
        if (name != null) {
            Resolution r = resolveExact(lookup, name, kind, regions, cityNames, anchor);
            if (r.place() != null) return r;
        }
        Resolution unconfirmed = Resolution.miss("none");
        for (String n : locals) {
            Resolution r = resolveExact(lookup, n, kind, regions, cityNames, anchor);
            if (r.place() == null) continue;
            if (sameAsKorean(r.place(), korean, cityNames)) return r;
            unconfirmed = Resolution.miss("local_unconfirmed");
        }
        return unconfirmed;
    }

    /**
     * 현지어 이름이 정확히 같은 장소가 초안의 한국어 이름과 같은 곳인지. 모델이 다른 곳의 현지 이름을 적으면 그 이름의 장소가
     * 있어도 잇지 않는다. 근거는 같은 카탈로그 행의 규칙 읽기다. DB의 한국어 이름은 보강이 지어 틀릴 수 있어 약한 근거다.
     */
    private boolean sameAsKorean(Place place, String korean, Collection<String> cityNames) {
        if (korean == null || korean.isBlank()) return true;
        CatalogPlace row = catalog.enabled() && place.getLatitude() != null && place.getLongitude() != null
                ? catalog.twinOf(place.getName(), place.getRegion(), place.getLatitude().doubleValue(),
                place.getLongitude().doubleValue()) : null;
        List<String> readings = new ArrayList<>();
        List<String> latin = List.of();
        if (row != null) {
            readings.addAll(row.readings());
            if (row.nameKo() != null) readings.add(row.nameKo());
            latin = row.latin();
        }
        List<String> weak = place.getNameKo() == null || readings.contains(place.getNameKo()) ? List.of()
                : List.of(place.getNameKo());
        Set<String> cities = new LinkedHashSet<>(cityNames == null ? List.of() : cityNames);
        String country = destinations.find(place.getRegion()).map(Destination::country).orElse(null);
        destinations.all().stream().filter(d -> country == null || country.equals(d.country()))
                .map(Destination::nameKo).forEach(cities::add);
        return LocalNameCheck.confirms(korean, cities, place.getName(), place.getCategory(), readings, weak, latin);
    }

    /**
     * 정확·별칭 일치만 받는 단계(현지어 이름·이웃 여행지)는 이름 그대로의 키만 묻는다. 표기 변형·부분 질의는 결과를 받지 않는데도
     * 이름마다 조회가 열 번 넘게 늘어난다.
     */
    private Resolution resolveExact(Lookup lookup, String name, String kind, Collection<String> regions,
                                    Collection<String> cityNames, double[] anchor) {
        // 이름이 정확히 같으면 명소로 적은 카페·빵집도 받는 것은 resolveIn의 ANY_VISIT 단계와 같다.
        List<String> roles = new ArrayList<>(kind == null ? ROLES.get("sight") : ROLES.getOrDefault(kind, ROLES.get("sight")));
        ANY_VISIT.stream().filter(r -> !roles.contains(r)).forEach(roles::add);
        Map<String, List<Hit>> fetched = new java.util.HashMap<>();
        for (String query : new java.util.LinkedHashSet<>(List.of(PlaceNames.exactKey(trimmed(name)),
                queryKey(name, cityNames)))) {
            if (query.length() < 2) continue;
            Resolution hit = resolveKey(lookup, query, roles, regions, anchor, 0, fetched, p -> true);
            if (strong(hit)) return hit;
        }
        return Resolution.miss("none");
    }

    private static boolean strong(Resolution r) {
        return r.place() != null && (r.match() == Match.EXACT || r.match() == Match.ALIAS);
    }

    /** @param fallback 카탈로그 장소가 꺼져 있어 올리지 못하면 대신 돌려줄 DB 결과 */
    private Resolution promoted(CatalogLookup lookup, Resolution pulled, Resolution fallback) {
        return promoter.promote(lookup.source(pulled.place()))
                .map(p -> new Resolution(p, pulled.status(), pulled.match()))
                .orElseGet(() -> fallback.place() != null ? fallback : Resolution.miss("policy_off"));
    }

    /**
     * 해외 여행지 지역인지. 현지어 이름은 해외 장소에만 쓴다. 모델이 국내 장소에 한자·영어 표기를 적어도 국내 연결은 바뀌지
     * 않아야 한다.
     */
    public boolean overseas(Collection<String> regions) {
        return regions != null && regions.stream().anyMatch(r -> destinations.find(r).isPresent());
    }

    private volatile Set<String> destinationKeys;

    private Set<String> destinationKeys() {
        Set<String> keys = destinationKeys;
        if (keys == null) {
            Set<String> out = new HashSet<>();
            for (Destination d : destinations.all()) {
                out.add(PlaceNames.bareKey(d.name()));
                if (d.nameKo() != null) out.add(PlaceNames.bareKey(d.nameKo()));
                if (d.aliases() != null) d.aliases().forEach(a -> out.add(PlaceNames.bareKey(a)));
            }
            out.remove("");
            destinationKeys = keys = Set.copyOf(out);
        }
        return keys;
    }

    /** 이웃 여행지로 보는 중심 거리 여유. 두 반경 밖으로 근교 이동 30분 안팎이다. */
    private static final double NEIGHBOR_MARGIN_KM = 10;

    /** regions 여행지 반경과 붙어 있는 같은 나라의 다른 여행지. */
    List<String> neighborRegions(Collection<String> regions) {
        if (regions == null || regions.isEmpty()) return List.of();
        List<Destination> own = regions.stream().map(destinations::find).flatMap(java.util.Optional::stream).toList();
        return destinations.all().stream()
                .filter(d -> !regions.contains(d.name()))
                .filter(d -> own.stream().anyMatch(o -> o.country().equals(d.country())
                        && GeoUtils.haversine(o.lat(), o.lng(), d.lat(), d.lng())
                        <= o.radiusKm() + d.radiusKm() + NEIGHBOR_MARGIN_KM))
                .map(Destination::name).toList();
    }

    /**
     * 이름을 찾는 곳. 운영 DB를 먼저 보고, 없으면 같은 규칙으로 카탈로그를 본다. 카탈로그 행은 해석 안에서만 쓰는
     * 음수 id를 달고, 고른 행만 DB에 올린다.
     */
    interface Lookup {
        List<Place> byCompactName(String compact, int minLength, Collection<String> regions, List<String> roles);

        /** 이름·괄호 뗀 이름·한국어 이름 키가 key와 같을 수 있는 행. 정확 일치 단계는 포함 조회가 필요 없다. */
        default List<Place> byExactKey(String key, Collection<String> regions, List<String> roles) {
            return byCompactName(key, key.length(), regions, roles);
        }

        List<Place> byNameEdges(String head, String tail, Collection<String> regions, List<String> roles);
    }

    private Lookup dbLookup() {
        return new Lookup() {
            @Override
            public List<Place> byCompactName(String compact, int minLength, Collection<String> regions,
                                             List<String> roles) {
                return placeRepository.findActiveByCompactName(compact, minLength, regions, roles);
            }

            @Override
            public List<Place> byNameEdges(String head, String tail, Collection<String> regions, List<String> roles) {
                return placeRepository.findActiveByNameEdges(head, tail, regions, roles);
            }
        };
    }

    static final class CatalogLookup implements Lookup {
        private final PlaceCatalog catalog;
        private final Map<Long, CatalogPlace> byId = new java.util.HashMap<>();
        private final Map<CatalogPlace, Place> transients = new java.util.HashMap<>();

        CatalogLookup(PlaceCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public List<Place> byCompactName(String compact, int minLength, Collection<String> regions, List<String> roles) {
            return places(catalog.findByCompactName(compact, minLength, regions, roles));
        }

        @Override
        public List<Place> byNameEdges(String head, String tail, Collection<String> regions, List<String> roles) {
            return places(catalog.findByNameEdges(head, tail, regions, roles));
        }

        @Override
        public List<Place> byExactKey(String key, Collection<String> regions, List<String> roles) {
            return places(catalog.findByExactKey(key, regions, roles));
        }

        private List<Place> places(List<CatalogPlace> rows) {
            return rows.stream().map(c -> transients.computeIfAbsent(c, k -> {
                long id = -(byId.size() + 1L);
                byId.put(id, k);
                return k.transientPlace(id);
            })).toList();
        }

        CatalogPlace source(Place p) {
            return byId.get(p.getId());
        }
    }

    private Resolution resolveIn(Lookup lookup, String name, String kind, Collection<String> regions,
                                 Collection<String> cityNames, double[] anchor) {
        Resolution last = Resolution.miss("too_short");
        List<String> variants = queryVariants(name, cityNames);
        List<String> aliases = PlaceNames.variants(name);
        Set<String> asWritten = new HashSet<>(List.of(PlaceNames.exactKey(trimmed(name)), queryKey(name, cityNames)));
        Set<String> lastWords = new HashSet<>();
        for (String alias : aliases) {
            String w = lastWord(alias);
            if (w != null) lastWords.add(w);
        }
        List<String> roles = kind == null ? ROLES.get("sight") : ROLES.getOrDefault(kind, ROLES.get("sight"));
        Map<String, List<Hit>> fetched = new java.util.HashMap<>();
        // 별칭이 있으면 같은 이름을 먼저 쓴다. 앞부분만 맞는 딸린 시설("경포해변 중앙광장")이 별칭 이름의 본래 장소를 가린다.
        // 별칭이 없으면 질의 순서가 이미 긴 이름부터라 이 조회를 더하지 않는다.
        for (String query : aliases.size() > 1 ? variants : List.<String>of()) {
            last = resolveKey(lookup, query, roles, regions, anchor, 0, fetched, p -> true);
            if (last.place() != null) return writtenAs(last, query, asWritten, lastWords);
        }
        java.util.function.Predicate<Hit> keepsRest = keepsRestOf(aliases, cityNames);
        for (String query : variants) {
            last = resolveKey(lookup, query, roles, regions, anchor, 3, fetched, lastWords.contains(query) ? keepsRest : p -> true);
            if (last.place() != null) return writtenAs(last, query, asWritten, lastWords);
        }
        // 모델이 명소로 적은 카페·빵집(테라로사 커피공장, 성심당)은 이름이 거의 같을 때만 잇는다.
        for (String query : variants) {
            last = resolveKey(lookup, query, ANY_VISIT, regions, anchor, 2, fetched,
                    lastWords.contains(query) ? keepsRest : p -> true);
            if (last.place() != null) return writtenAs(last, query, asWritten, lastWords);
        }
        for (String query : variants.subList(0, Math.min(2, variants.size()))) {
            Resolution infixed = resolveInfixed(lookup, query, roles, regions);
            if (infixed.place() != null) return infixed;
        }
        // 표기 변형은 근거가 약해 다른 단계가 모두 실패했을 때 같은 이름이거나 앞부분이 맞을 때만 받는다.
        for (String spelled : PlaceNames.spellingVariants(trimmed(name), isJapanese(regions))) {
            for (String query : new java.util.LinkedHashSet<>(List.of(PlaceNames.exactKey(spelled),
                    queryKey(spelled, cityNames)))) {
                if (query.length() < 2) continue;
                Resolution spelledHit = resolveKey(lookup, query, roles, regions, anchor, 1, fetched, p -> true);
                if (spelledHit.place() != null) return spelledHit.as(Match.VARIANT);
            }
        }
        return last;
    }

    /** 받은 이름 그대로가 아닌 질의로 찾았으면 근거를 낮춘다. 해변↔해수욕장·꾸밈말 떼기는 변형, 마지막 낱말은 부분 일치다. */
    private static Resolution writtenAs(Resolution hit, String query, Set<String> asWritten, Set<String> lastWords) {
        if (asWritten.contains(query)) return hit;
        return hit.as(lastWords.contains(query) ? Match.FUZZY : Match.VARIANT);
    }

    private boolean isJapanese(Collection<String> regions) {
        return regions.stream().map(destinations::find).flatMap(java.util.Optional::stream)
                .map(Destination::country).anyMatch("JP"::equals);
    }

    /** 알려진 이름 가운데 꾸밈말이 낀 질의("대인예술시장")를 그 꾸밈말을 뺀 DB 이름("대인시장")으로 잇는다. */
    private Resolution resolveInfixed(Lookup lookup, String query, List<String> roles, Collection<String> regions) {
        if (query.length() < MIN_INFIXED_NAME + 2) return Resolution.miss("none");
        List<Hit> hits = hits(lookup.byNameEdges(query.substring(0, 2),
                        query.substring(query.length() - 2), regions, roles), roles).stream()
                .filter(h -> readableName(h.place()))
                .filter(h -> h.forms().stream().anyMatch(f -> dropsInfix(f.key(), query)))
                .toList();
        if (hits.isEmpty()) return Resolution.miss("none");
        Place best = hits.stream().map(Hit::place).max(Comparator.comparingInt(DraftPlaceResolver::sitelinks)).orElseThrow();
        boolean ambiguous = hits.stream().anyMatch(h -> distanceKm(coords(best), h.place()) > AMBIGUOUS_KM);
        return ambiguous ? Resolution.miss("ambiguous") : new Resolution(best, "ok", Match.FUZZY);
    }

    /** 이름이 질의의 앞부분과 뒷부분만 남기고 가운데 두 글자 이상을 뺀 것인지. */
    static boolean dropsInfix(String name, String query) {
        if (name.length() < MIN_INFIXED_NAME || name.length() > query.length() - 2) return false;
        for (int i = 2; i <= name.length() - 2; i++) {
            if (query.startsWith(name.substring(0, i)) && query.endsWith(name.substring(i))) return true;
        }
        return false;
    }

    /**
     * 시도할 질의 순서. 도시 이름을 붙인 채("전주남부시장"), 뗀 채, 꾸밈말("야경 산책")을 뗀 채, 마지막 낱말("수원화성 화서문" →
     * "화서문")이다. 마지막 낱말은 3자 이상일 때만 쓴다. 첫 낱말은 쓰지 않는다.
     */
    static List<String> queryVariants(String name, Collection<String> cityNames) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        List<String> aliases = PlaceNames.variants(name);
        for (String alias : aliases) {
            List<String> tokens = new java.util.ArrayList<>(List.of(trimmed(alias).split("\\s+")));
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
        }
        for (String alias : aliases) {
            String w = lastWord(alias);
            if (w != null) out.add(w);
        }
        out.removeIf(q -> q.length() < 2);
        return List.copyOf(out);
    }

    /**
     * 마지막 낱말로 찾은 곳이 요청한 곳인지. 이름이 그 낱말과 같거나, 도시 이름을 뺀 앞 낱말 하나가 이름에 있어야 한다.
     * 앞 낱말이 동네 이름뿐이면 장소 이름에 없을 수 있다("고소동 벽화마을"과 "여수천사벽화마을"). 이 확인이 없으면
     * "하카타"로 찾은 "하카타 기온 야마카사"가 "캐널시티 하카타" 자리에 들어간다.
     */
    private static java.util.function.Predicate<Hit> keepsRestOf(List<String> aliases, Collection<String> cityNames) {
        Set<String> cities = new HashSet<>();
        cityNames.forEach(c -> cities.add(PlaceNames.exactKey(c)));
        List<String> rest = new java.util.ArrayList<>();
        Set<String> lasts = new HashSet<>();
        for (String alias : aliases) {
            List<String> tokens = new java.util.ArrayList<>(List.of(trimmed(alias).split("\\s+")));
            while (tokens.size() > 1 && DESCRIPTORS.contains(tokens.get(tokens.size() - 1))) {
                tokens.remove(tokens.size() - 1);
            }
            lasts.add(PlaceNames.exactKey(tokens.get(tokens.size() - 1)));
            for (String t : tokens.subList(0, tokens.size() - 1)) {
                String k = PlaceNames.exactKey(t);
                if (k.length() >= 2 && !cities.contains(k) && !DESCRIPTORS.contains(t)) rest.add(k);
            }
        }
        boolean onlyLocalities = !rest.isEmpty() && rest.stream().allMatch(k -> WantMatcher.LOCALITY.matcher(k).matches());
        return h -> {
            if (h.forms().stream().anyMatch(f -> lasts.contains(f.key()))) return true;
            String names = h.forms().stream().map(Form::full).collect(Collectors.joining("|"));
            return rest.isEmpty() || onlyLocalities || rest.stream().anyMatch(names::contains);
        };
    }

    /** 꾸밈말을 뗀 뒤 마지막 낱말. 낱말이 하나뿐이거나 3자보다 짧으면 null이다. */
    private static String lastWord(String name) {
        List<String> tokens = new java.util.ArrayList<>(List.of(trimmed(name).split("\\s+")));
        while (tokens.size() > 1 && DESCRIPTORS.contains(tokens.get(tokens.size() - 1))) tokens.remove(tokens.size() - 1);
        String last = tokens.get(tokens.size() - 1);
        return tokens.size() >= 2 && PlaceNames.exactKey(last).length() >= MIN_CONTAINED_NAME
                && !DESCRIPTORS.contains(last) ? PlaceNames.exactKey(last) : null;
    }

    private static String trimmed(String name) {
        return BRANCH_SUFFIX.matcher(name == null ? "" : name.trim()).replaceAll("");
    }

    private static void addWithCity(java.util.Set<String> out, String text, Collection<String> cityNames) {
        out.add(PlaceNames.exactKey(text));
        out.add(queryKey(text, cityNames));
    }

    /** 이름 끝에 붙어 장소 이름이 아닌 꾸밈말. */
    private static final java.util.Set<String> DESCRIPTORS = java.util.Set.of(
            "야경", "산책", "야경산책", "투어", "체험", "일대", "관람", "구경", "야간", "나들이", "방문", "드라이브", "야시장");

    /**
     * @param fetched 한 이름을 잇는 동안 같은 질의를 다시 조회하지 않게 결과를 담아 둔다
     * @param accepts 순위를 매기기 전에 걸러 낼 조건
     */
    private Resolution resolveKey(Lookup lookup, String query, List<String> roles, Collection<String> regions,
                                  double[] anchor, int maxTier, Map<String, List<Hit>> fetched,
                                  java.util.function.Predicate<Hit> accepts) {
        List<Hit> rows = maxTier == 0
                ? fetched.computeIfAbsent("exact|" + roles + "|" + query,
                        k -> hits(lookup.byExactKey(query, regions, roles), roles))
                : fetched.computeIfAbsent(roles + "|" + query,
                        k -> hits(lookup.byCompactName(query, MIN_CONTAINED_NAME, regions, roles), roles));
        if (rows.isEmpty()) return Resolution.miss("none");

        // 같은 순위 안에서는 원래 이름으로 맞은 곳, 이름난 곳 순이다. 별칭은 출처마다 붙이는 방식이 달라 덜 믿고,
        // 이름난 곳을 앞세워야 "창덕궁"이 글자 수가 가까운 부속 건물("창덕궁 약방")로 잡히지 않는다.
        Comparator<Hit> order = Comparator.comparingInt((Hit h) -> rankTier(h.best(query).key(), query))
                .thenComparing((Hit h) -> h.best(query).alias())
                .thenComparing((Hit h) -> -sitelinks(h.place()))
                .thenComparingInt(h -> Math.abs(h.best(query).key().length() - query.length()));
        if (anchor != null) {
            order = order.thenComparingDouble(h -> distanceKm(anchor, h.place()));
        } else {
            order = order.thenComparing((Hit h) -> -sitelinks(h.place()));
        }
        order = order.thenComparingInt(h -> PlaceNames.exactKey(h.place().getName()).length());
        List<Hit> ranked = rows.stream()
                .filter(h -> tier(h.best(query).key(), query) <= maxTier)
                .filter(h -> readableName(h.place()))
                .filter(accepts)
                .filter(h -> !NOT_A_PLACE.matcher(h.place().getName()).find() || NOT_A_PLACE.matcher(query).find())
                .filter(h -> roles.contains("TRANSIT") || h.place().getCategory() == null
                        || !TRANSIT_CATEGORY.matcher(h.place().getCategory()).find())
                // 이름이 여행지 이름뿐인 행(HAKODATE, Kyoto)은 그 지역 표지라, 더 긴 요청 이름의 일부로 맞으면 다른 곳이다.
                .filter(h -> tier(h.best(query).key(), query) == 0 || !overseas(regions)
                        || !destinationKeys().contains(PlaceNames.bareKey(h.place().getName())))
                .sorted(order).toList();
        if (ranked.isEmpty()) return Resolution.miss("none");
        Hit best = ranked.get(0);
        Form bestForm = best.best(query);
        int bestTier = tier(bestForm.key(), query);
        if (anchor == null && bestTier > 0) {
            boolean ambiguous = ranked.stream().skip(1)
                    .filter(h -> tier(h.best(query).key(), query) == bestTier
                            && sitelinks(h.place()) >= sitelinks(best.place()))
                    .anyMatch(h -> distanceKm(coords(best.place()), h.place()) > AMBIGUOUS_KM);
            if (ambiguous) return Resolution.miss("ambiguous");
        }
        Match match = bestTier > 0 ? Match.FUZZY : bestForm.alias() ? Match.ALIAS : Match.EXACT;
        return new Resolution(best.place(), "ok", match);
    }

    /**
     * 이름을 비교할 형태. key는 괄호 설명을 뗀 키, full은 원래 이름의 키다. alias는 출처가 준 다른 이름
     * (한국어 이름·괄호 안 한글 이름·같은 곳으로 합쳐진 행의 이름)이다.
     */
    private record Form(String key, String full, boolean alias) {}

    /** 조회한 장소와 그 장소를 부르는 이름들. */
    private record Hit(Place place, List<Form> forms) {
        /** 질의에 가장 가까운 형태. 같은 순위면 원래 이름을 먼저 쓴다. */
        Form best(String query) {
            return forms.stream().min(Comparator.comparingInt((Form f) -> tier(f.key(), query))
                    .thenComparing(Form::alias)).orElseThrow();
        }
    }

    /**
     * 같은 곳으로 합쳐진 행은 대표 행으로 바꾸고 그 행의 이름은 별칭으로 남긴다. 합쳐진 행에만 있는 한국어 이름
     * ("쇼텐지")으로도 대표 행을 찾는다. 딸린 일부(PART)는 다른 곳이라 빼고, 역할이 없는 행은 대분류로 짐작한 역할로 거른다.
     */
    private List<Hit> hits(List<Place> rows, List<String> roles) {
        Set<Long> repIds = rows.stream().map(Place::samePlaceRepresentativeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Place> reps = repIds.isEmpty() ? Map.of() : placeRepository.findAllById(repIds).stream()
                .filter(r -> Boolean.TRUE.equals(r.getActive()) && r.getMergedIntoId() == null && fitsRoles(r, roles))
                .collect(Collectors.toMap(Place::getId, Function.identity()));
        Map<Long, Hit> out = new java.util.LinkedHashMap<>();
        for (Place p : rows) {
            if (p.getMergedIntoId() == null) {
                if (fitsRoles(p, roles)) out.merge(p.getId(), new Hit(p, forms(p, false)), DraftPlaceResolver::union);
                continue;
            }
            Place rep = p.samePlaceRepresentativeId() == null ? null : reps.get(p.samePlaceRepresentativeId());
            if (rep == null) continue;
            List<Form> forms = new ArrayList<>(forms(rep, false));
            forms.addAll(forms(p, true));
            out.merge(rep.getId(), new Hit(rep, forms), DraftPlaceResolver::union);
        }
        return List.copyOf(out.values());
    }

    private static Hit union(Hit a, Hit b) {
        List<Form> forms = new ArrayList<>(a.forms());
        b.forms().stream().filter(f -> !forms.contains(f)).forEach(forms::add);
        return new Hit(a.place(), forms);
    }

    private static List<Form> forms(Place p, boolean aliasOnly) {
        List<Form> out = new ArrayList<>(4);
        if (p.getName() != null) {
            out.add(new Form(PlaceNames.bareKey(p.getName()), PlaceNames.exactKey(p.getName()), aliasOnly));
        }
        if (p.getNameKo() != null && !p.getNameKo().isBlank()) {
            out.add(new Form(PlaceNames.bareKey(p.getNameKo()), PlaceNames.exactKey(p.getNameKo()), true));
        }
        for (String name : new String[]{p.getName(), p.getNameKo()}) {
            for (String alias : PlaceNames.bracketAliasKeys(name)) out.add(new Form(alias, alias, true));
        }
        return out;
    }

    private static boolean fitsRoles(Place p, List<String> roles) {
        if (p.getPlaceRole() != null) return roles.contains(p.getPlaceRole());
        return ROLES_BY_MAJOR.getOrDefault(PlaceCategoryConstants.majorCategory(p.getCategory()), List.of())
                .stream().anyMatch(roles::contains);
    }

    /** 일정에 보이는 이름. 해외 장소는 한국어 이름이다. */
    static String shownName(Place p) {
        return p.getNameKo() != null && !p.getNameKo().isBlank() ? p.getNameKo() : p.getName();
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
        String q = PlaceNames.exactKey(trimmed(name));
        for (String city : cityNames) {
            String c = PlaceNames.exactKey(city);
            if (!c.isEmpty() && q.startsWith(c) && q.length() > c.length() + 1) {
                return q.substring(c.length());
            }
        }
        return q;
    }

    /** 한글 이름이 없는 국내 행(위키데이터의 일본어 표제 "石窟庵と仏国寺")은 일정에 그대로 보여 줄 수 없다. */
    private static boolean readableName(Place p) {
        if (p.getNameKo() != null && !p.getNameKo().isBlank()) return true;
        return !"KR".equals(p.getCountry()) || HANGUL.matcher(p.getName()).find();
    }

    private static final Pattern HANGUL = Pattern.compile("[가-힣]");

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
