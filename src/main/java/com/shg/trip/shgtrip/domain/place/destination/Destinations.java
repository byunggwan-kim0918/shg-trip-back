package com.shg.trip.shgtrip.domain.place.destination;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 해외 여행지 목록. FSQ 추출기(fsq-extractor/extract.py)가 같은 파일로 장소를 여행지에 배정하므로
 * 배정 규칙({@link #nearest})을 바꾸려면 추출기도 같이 바꿔야 한다. 한국 지역은 이 목록에 없다.
 */
@Component
public class Destinations {

    static final String RESOURCE = "destinations.json";

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final List<Destination> all;
    private final Map<String, Destination> byName;

    @Autowired
    public Destinations(ObjectMapper objectMapper) {
        this(load(objectMapper));
    }

    public Destinations(List<Destination> destinations) {
        Map<String, Destination> map = new LinkedHashMap<>();
        for (Destination d : destinations) {
            if (map.put(d.name(), d) != null) {
                throw new IllegalStateException("여행지 이름 중복: " + d.name());
            }
        }
        this.all = List.copyOf(destinations);
        this.byName = map;
    }

    public static Destinations fromClasspath() {
        return new Destinations(new ObjectMapper());
    }

    private static List<Destination> load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<Destination>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(RESOURCE + " 읽기 실패", e);
        }
    }

    public List<Destination> all() {
        return all;
    }

    public Optional<Destination> find(String region) {
        return Optional.ofNullable(region == null ? null : byName.get(region));
    }

    public boolean isOverseas(String region) {
        return region != null && byName.containsKey(region);
    }

    /** 반경 안 같은 국가 여행지 중 거리/반경이 가장 작은 곳. 같으면 이름순(추출기와 같은 규칙). */
    public Optional<Destination> nearest(String country, double lat, double lng) {
        return placement(country, lat, lng).map(Placement::destination);
    }

    /** {@link #nearest}와 그 거리/반경. 좌표가 여러 개인 장소는 이 값이 가장 작은 좌표로 배정한다. */
    public Optional<Placement> placement(String country, double lat, double lng) {
        return all.stream()
                .filter(d -> d.country().equals(country))
                .map(d -> new Placement(d, distanceKm(lat, lng, d.lat(), d.lng()) / d.radiusKm()))
                .filter(p -> p.ratio() <= 1.0)
                .min(Comparator.comparingDouble(Placement::ratio)
                        .thenComparing(p -> p.destination().name()));
    }

    public record Placement(Destination destination, double ratio) {}

    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }
}
