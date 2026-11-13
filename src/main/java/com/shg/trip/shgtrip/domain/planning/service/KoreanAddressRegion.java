package com.shg.trip.shgtrip.domain.planning.service;

import java.util.Map;
import java.util.Set;

/**
 * 국내 주소의 광역 단위가 장소의 지역(region)과 어긋나는지 본다.
 * Foursquare 행 중에는 좌표·지역은 부산인데 주소는 다른 도시인 것이 있어, 그 주소로 지역명을 쓰면 설명에 엉뚱한 도시가 나온다.
 */
final class KoreanAddressRegion {

    private KoreanAddressRegion() {}

    /** 주소 첫 토큰 → 광역 단위. 정확히 일치하는 토큰만 본다("부산진구"·"부산대학로"는 광역 표기가 아니다). */
    private static final Map<String, String> PROVINCE_TOKENS = Map.ofEntries(
            Map.entry("서울", "서울"), Map.entry("서울시", "서울"), Map.entry("서울특별시", "서울"),
            Map.entry("부산", "부산"), Map.entry("부산시", "부산"), Map.entry("부산광역시", "부산"),
            Map.entry("대구", "대구"), Map.entry("대구시", "대구"), Map.entry("대구광역시", "대구"),
            Map.entry("인천", "인천"), Map.entry("인천시", "인천"), Map.entry("인천광역시", "인천"),
            // "광주시"는 경기도 광주시와 겹쳐 넣지 않는다
            Map.entry("광주", "광주"), Map.entry("광주광역시", "광주"),
            Map.entry("대전", "대전"), Map.entry("대전시", "대전"), Map.entry("대전광역시", "대전"),
            Map.entry("울산", "울산"), Map.entry("울산시", "울산"), Map.entry("울산광역시", "울산"),
            Map.entry("세종", "세종"), Map.entry("세종시", "세종"), Map.entry("세종특별자치시", "세종"),
            Map.entry("경기", "경기"), Map.entry("경기도", "경기"),
            Map.entry("강원", "강원"), Map.entry("강원도", "강원"), Map.entry("강원특별자치도", "강원"),
            Map.entry("충북", "충북"), Map.entry("충청북도", "충북"),
            Map.entry("충남", "충남"), Map.entry("충청남도", "충남"),
            Map.entry("전북", "전북"), Map.entry("전라북도", "전북"), Map.entry("전북특별자치도", "전북"),
            Map.entry("전남", "전남"), Map.entry("전라남도", "전남"),
            Map.entry("전남광주", "전남광주"), Map.entry("전남광주통합특별시", "전남광주"),
            Map.entry("경북", "경북"), Map.entry("경상북도", "경북"),
            Map.entry("경남", "경남"), Map.entry("경상남도", "경남"),
            Map.entry("제주", "제주"), Map.entry("제주도", "제주"), Map.entry("제주특별자치도", "제주"),
            Map.entry("제주시", "제주"), Map.entry("서귀포시", "제주"));

    /**
     * 지역마다 주소에 나와도 되는 광역 단위. 적재는 도시 좌표 범위로 지역을 정해 경계 밖 인접 광역 주소가 섞인다.
     * 목록에 없는 지역(해외 포함)은 판정하지 않는다.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry("Seoul", Set.of("서울", "경기", "인천")),
            Map.entry("Busan", Set.of("부산", "경남", "울산")),
            Map.entry("Jeju", Set.of("제주")),
            Map.entry("Incheon", Set.of("인천", "경기", "서울")),
            Map.entry("Daegu", Set.of("대구", "경북")),
            Map.entry("Daejeon", Set.of("대전", "충남", "충북", "세종")),
            Map.entry("Suwon", Set.of("경기", "서울")),
            Map.entry("Gangneung", Set.of("강원")),
            Map.entry("Gwangju", Set.of("광주", "전남", "전남광주")),
            Map.entry("Gyeongju", Set.of("경북", "울산")),
            Map.entry("Jeonju", Set.of("전북")),
            Map.entry("Yeosu", Set.of("전남", "전남광주", "경남")));

    static boolean contradicts(String address, String region) {
        if (address == null || region == null) return false;
        Set<String> allowed = ALLOWED.get(region);
        if (allowed == null) return false;
        String province = provinceOf(address);
        return province != null && !allowed.contains(province);
    }

    /** 주소가 광역 단위로 시작하지 않으면 null. */
    static String provinceOf(String address) {
        if (address == null || address.isBlank()) return null;
        return PROVINCE_TOKENS.get(address.strip().split("\\s+", 2)[0]);
    }
}
