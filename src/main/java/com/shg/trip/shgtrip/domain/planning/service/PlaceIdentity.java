package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.DomesticCities;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;
import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 출처(관광공사·Foursquare·Wikidata)가 다른 두 장소 행이 같은 곳인지 본다.
 * 출처별 행은 이름 표기·좌표·언어판 표지가 조금씩 달라 id나 중복 키 하나로는 같은 곳을 놓친다.
 */
public final class PlaceIdentity {

    private PlaceIdentity() {}

    enum Basis { PLACE_ID, SAME_CELL, SAME_NAME, BARE_NAME, WIKIDATA_ITEM }

    private static final double WIKIDATA_TWIN_KM = 1.0;
    /** 출처별 같은 가게 행의 좌표 차이 범위. 이름이 같아도 이보다 멀면 체인의 다른 지점이나 다른 가게다. */
    private static final double SAME_NAME_KM = 1.0;
    /** 산·성곽처럼 넓은 야외 명소는 출처마다 좌표가 수 km 다르다. */
    private static final double OUTDOOR_SAME_NAME_KM = 5.0;
    /**
     * 리조트·서원처럼 출처마다 숙소와 관광지로 갈린 같은 곳의 좌표 차이 범위(수 m~수십 m).
     * 관광지·가게와 이름이 같은 숙소는 이 거리 안일 때만 같은 곳이다.
     */
    private static final double SAME_NAME_STAY_KM = 0.1;
    private static final int MIN_BARE_NAME = 2;
    /** 딸린 행으로 볼 명소 이름의 최소 길이. 두 글자 이름("경포")으로 시작하는 곳은 다른 명소가 많다. */
    private static final int MIN_PART_PREFIX = 3;
    /** 명소 이름 뒤에 붙으면 그 명소의 부속 시설인 낱말. "롯데월드타워"처럼 다른 명소가 되는 꼬리는 넣지 않는다. */
    private static final List<String> PART_SUFFIXES = List.of("중앙광장", "광장", "입구", "주차장", "물놀이장", "산책로",
            "인증센터", "안내소", "매표소", "전망대", "중부", "동부", "서부", "남부", "북부");
    private static final Pattern NAME_SEPARATORS = Pattern.compile("[\\s\\p{Punct}\\p{IsPunctuation}]+");
    private static final Pattern TRAILING_NOTE = Pattern.compile("\\s*[(（\\[][^()（）\\[\\]]*[)）\\]]\\s*$");
    /** 영어 이름 꼬리. 괄호로 감싼 병기("東京ドームシティ (Tokyo Dome City)")도 받는다. */
    private static final Pattern LATIN_TAIL = Pattern.compile("[\\s(（]+[A-Za-z0-9][A-Za-z0-9 .,'’&()（）/-]*");
    /** 구역 명소 이름으로 시작하는 FSQ 행은 그 구역 안 가게("アメリカ村 Club DROP")다. */
    private static final Set<String> AREA_LEAVES = Set.of("pedestrian plaza", "plaza");
    /** 장소 항목과 그곳의 박물관 기관 항목(故宫·故宫博物院)은 Wikidata 항목이 달라도 같은 곳이다. */
    private static final Pattern MUSEUM_INSTITUTION_SUFFIX = Pattern.compile("博物院|博物館|博物馆");

    static Optional<Basis> sameAs(PlaceCandidate a, PlaceCandidate b) {
        if (a.placeId() != null && a.placeId().equals(b.placeId())) return Optional.of(Basis.PLACE_ID);
        String majorA = PlaceCategoryConstants.majorCategory(a.category());
        String majorB = PlaceCategoryConstants.majorCategory(b.category());
        // 건물 안 식당과 같은 좌표 칸의 호텔·역은 다른 곳이다.
        if (majorA.equals(majorB) && sameCell(a, b)) return Optional.of(Basis.SAME_CELL);
        if (Objects.equals(a.region(), b.region()) && namesCrossCategory(a, majorA, b, majorB)
                && withinKm(a, b, isOutdoor(a) && isOutdoor(b) ? OUTDOOR_SAME_NAME_KM : SAME_NAME_KM)) {
            if (overlaps(names(a), names(b))) return Optional.of(Basis.SAME_NAME);
            if (overlaps(bareNames(a), bareNames(b))) return Optional.of(Basis.BARE_NAME);
            // 명소 이름 뒤에 영문을 붙인 근처 가게("광화문 market")는 다른 곳이다.
            if (majorA.equals(majorB) && (isBilingualTwin(a.name(), b) || isBilingualTwin(b.name(), a))) {
                return Optional.of(Basis.BARE_NAME);
            }
        }
        if (isWikidataTwin(a, b) || isWikidataTwin(b, a) || isNestedWikidataItem(a, b) || isNestedWikidataItem(b, a)) {
            return Optional.of(Basis.WIKIDATA_ITEM);
        }
        return Optional.empty();
    }

    /**
     * 같은 지역·대분류에서 대소문자·띄어쓰기·문장부호만 지운 원래 이름, 너무 짧으면 null. 괄호 설명·영문 병기를 떼면 하위 시설
     * ("에버랜드 (주토피아)")과 다른 가게("Café …")까지 묶이고, 보강 한국어 이름은 다른 장소에도 같게 붙어 쓰지 않는다.
     */
    static String nameKey(String name, String region, String category) {
        String compact = name == null ? "" : compact(name);
        if (compact.length() < MIN_BARE_NAME) return null;
        return region + "|" + PlaceCategoryConstants.majorCategory(category) + "|" + compact;
    }

    /** 일본 지명의 한국어 표기 차이. 모델과 여행자는 "츠키지·큐슈"로, 장소 데이터는 "쓰키지·규슈"로 적기도 한다. */
    private static final List<String[]> JAPANESE_SPELLINGS = List.of(
            new String[]{"츠", "쓰"}, new String[]{"큐", "규"}, new String[]{"덴", "텐"}, new String[]{"텐", "덴"});

    /**
     * 같은 곳을 부르는 이름들. 첫 이름은 받은 그대로다. 해변과 해수욕장은 같은 곳이라 "경포해변"의 장소 이름이
     * "경포해수욕장"일 수 있고, 일본 지명은 표기가 갈린다.
     */
    public static List<String> nameVariants(String name) {
        String n = name == null ? "" : name.strip();
        List<String> beach = n.endsWith("해수욕장") ? List.of(n, n.substring(0, n.length() - 4) + "해변")
                : n.endsWith("해변") ? List.of(n, n.substring(0, n.length() - 2) + "해수욕장") : List.of(n);
        Set<String> out = new java.util.LinkedHashSet<>(beach);
        for (String b : beach) {
            for (String[] pair : JAPANESE_SPELLINGS) {
                if (b.contains(pair[0])) out.add(b.replace(pair[0], pair[1]));
            }
        }
        return List.copyOf(out);
    }

    /** 피할 명소를 빼도 딸린 행("경포해변 중앙광장")이 남으면 같은 곳에 간다. */
    static boolean isPartOf(PlaceCandidate c, PlaceCandidate sight) {
        if (!Objects.equals(c.region(), sight.region()) || !withinKm(c, sight, OUTDOOR_SAME_NAME_KM)) return false;
        if (!"ATTRACTION".equals(PlaceCategoryConstants.majorCategory(c.category()))
                || !"ATTRACTION".equals(PlaceCategoryConstants.majorCategory(sight.category()))) return false;
        Set<String> names = new HashSet<>(names(c));
        names.addAll(bareNames(c));
        for (String sightName : new String[]{sight.name(), sight.nameKo()}) {
            if (sightName == null || sightName.isBlank()) continue;
            for (String variant : nameVariants(bareName(sightName, sight.region()))) {
                String key = compact(variant);
                if (key.length() >= MIN_PART_PREFIX && names.stream().anyMatch(n -> n.startsWith(key)
                        && PART_SUFFIXES.contains(n.substring(key.length())))) return true;
            }
        }
        return false;
    }

    /** 이름 끝 괄호 설명("용지봉 (634m)")과 앞에 붙은 그 지역 도시 이름("강릉 경포대")을 뗀 이름. null이면 빈 문자열이다. */
    public static String bareName(String name, String region) {
        if (name == null) return "";
        String bare = TRAILING_NOTE.matcher(name.strip()).replaceFirst("");
        String city = DomesticCities.find(region).map(Destination::nameKo).orElse(null);
        if (city != null && bare.length() > city.length() && bare.startsWith(city)
                && Character.isWhitespace(bare.charAt(city.length()))) {
            bare = bare.substring(city.length()).strip();
        }
        return bare;
    }

    // 역·공항은 도착·출발 거점이라 이름이 같아도 다른 곳으로 본다.
    private static boolean namesCrossCategory(PlaceCandidate a, String majorA, PlaceCandidate b, String majorB) {
        if (majorA.equals(majorB)) return true;
        if (isHub(majorA) || isHub(majorB)) return false;
        if (!"LODGING".equals(majorA) && !"LODGING".equals(majorB)) return true;
        return withinKm(a, b, SAME_NAME_STAY_KM);
    }

    private static boolean isHub(String major) {
        return "TRANSIT_HUB".equals(major);
    }

    private static Set<String> names(PlaceCandidate c) {
        Set<String> forms = new HashSet<>(2);
        for (String n : new String[]{c.name(), c.nameKo()}) {
            if (n != null && !n.isBlank()) forms.add(compact(n));
        }
        return forms;
    }

    private static Set<String> bareNames(PlaceCandidate c) {
        Set<String> forms = new HashSet<>(4);
        for (String n : new String[]{c.name(), c.nameKo()}) {
            if (n != null && !n.isBlank()) addForms(forms, n, c.region());
        }
        return forms;
    }

    private static void addForms(Set<String> forms, String name, String region) {
        forms.add(compact(name));
        String bare = compact(bareName(name, region));
        if (bare.length() >= MIN_BARE_NAME) forms.add(bare);
    }

    private static String compact(String name) {
        return NAME_SEPARATORS.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static boolean isBilingualTwin(String name, PlaceCandidate base) {
        return name != null && base.name() != null && !isAreaLeaf(base.category())
                && isBilingualName(name.strip(), base.name().strip());
    }

    // 숫자로 시작하는 꼬리("명동교자 2")는 같은 가게의 다른 지점이다.
    private static boolean isBilingualName(String name, String base) {
        return isBilingualOf(name, base)
                && name.substring(base.length()).replaceAll("^[\\s(（]+", "").matches("^[A-Za-z].*");
    }

    private static boolean isOutdoor(PlaceCandidate c) {
        return c.category() != null && c.category().toLowerCase(Locale.ROOT).startsWith("landmarks and outdoors");
    }

    /** 한글·한자 이름 뒤에 영문·숫자 꼬리만 붙었는가("해동용궁사 Haedong Yonggungsa Temple"). */
    private static boolean isBilingualOf(String name, String base) {
        return base.chars().anyMatch(ch -> ch >= 0x80) && name.startsWith(base)
                && LATIN_TAIL.matcher(name.substring(base.length())).matches();
    }

    private static boolean overlaps(Set<String> a, Set<String> b) {
        return a.stream().anyMatch(b::contains);
    }

    private static boolean sameCell(PlaceCandidate a, PlaceCandidate b) {
        return hasCoords(a) && hasCoords(b)
                && Math.round(a.latitude().doubleValue() * 10000) == Math.round(b.latitude().doubleValue() * 10000)
                && Math.round(a.longitude().doubleValue() * 10000) == Math.round(b.longitude().doubleValue() * 10000);
    }

    private static boolean withinKm(PlaceCandidate a, PlaceCandidate b, double km) {
        return hasCoords(a) && hasCoords(b)
                && GeoUtils.haversine(a.latitude().doubleValue(), a.longitude().doubleValue(),
                        b.latitude().doubleValue(), b.longitude().doubleValue()) <= km;
    }

    static boolean isWikidataTwin(PlaceCandidate twin, PlaceCandidate item) {
        if (twin == item || twin.userSelected() || !isWikidataItem(item) || isWikidataItem(twin)) return false;
        if (twin.name() == null || item.name() == null || !Objects.equals(twin.region(), item.region())) return false;
        String base = item.name().strip();
        String name = twin.name().strip();
        boolean sameName = name.equalsIgnoreCase(base);
        boolean bilingual = !sameName && isBilingualOf(name, base);
        if (!sameName && !bilingual) return false;
        if ((bilingual && isAreaLeaf(item.category())) || !PlaceCategoryConstants.majorCategory(item.category())
                .equals(PlaceCategoryConstants.majorCategory(twin.category()))) {
            return false;
        }
        return withinKm(twin, item, WIKIDATA_TWIN_KM);
    }

    /** dropped가 kept와 같은 곳을 가리키는 다른 항목이고 둘 중 덜 알려진 쪽인가. 같으면 짧은 이름(장소)을 남긴다. */
    static boolean isNestedWikidataItem(PlaceCandidate dropped, PlaceCandidate kept) {
        if (dropped == kept || dropped.userSelected() || !isWikidataItem(dropped) || !isWikidataItem(kept)) return false;
        if (dropped.name() == null || kept.name() == null || !Objects.equals(dropped.region(), kept.region())) return false;
        String a = dropped.name().strip();
        String b = kept.name().strip();
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (shorter.equals(longer) || !longer.startsWith(shorter)
                || !MUSEUM_INSTITUTION_SUFFIX.matcher(longer.substring(shorter.length())).matches()) {
            return false;
        }
        if (!PlaceCategoryConstants.majorCategory(dropped.category())
                .equals(PlaceCategoryConstants.majorCategory(kept.category()))
                || !withinKm(dropped, kept, WIKIDATA_TWIN_KM)) {
            return false;
        }
        int droppedLinks = dropped.wikidataSitelinks();
        int keptLinks = kept.wikidataSitelinks();
        return droppedLinks < keptLinks || (droppedLinks == keptLinks && a.length() > b.length());
    }

    private static boolean isWikidataItem(PlaceCandidate c) {
        return c.wikidataSitelinks() != null && c.wikidataSitelinks() > 0;
    }

    private static boolean isAreaLeaf(String category) {
        if (category == null) return false;
        return AREA_LEAVES.contains(category.substring(category.lastIndexOf('>') + 1).strip().toLowerCase(Locale.ROOT));
    }

    private static boolean hasCoords(PlaceCandidate c) {
        return c.latitude() != null && c.longitude() != null
                && !GeoUtils.isZeroCoord(c.latitude(), c.longitude());
    }
}
