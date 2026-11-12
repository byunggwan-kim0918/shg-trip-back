package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 일정을 만들어 주는 국가. 한국은 늘 포함한다. 장소 적재·검증을 마친 나라만 넣는다. 덜 적재된 나라로 만들면
 * 후보가 성겨 같은 장소만 도는 일정이 나온다.
 */
@Component
public class PlanningCountries {

    private final List<String> enabled;
    private final Destinations destinations;

    public PlanningCountries(@Value("${planning.enabled-countries:KR,JP}") List<String> enabled,
                             Destinations destinations) {
        this.enabled = enabled.stream().map(String::trim).filter(c -> !c.isEmpty()).toList();
        this.destinations = destinations;
    }

    /** 국가를 모르면(enrich 실패로 legacy 경로) 막지 않는다. */
    public boolean isEnabled(String country) {
        if (country == null || country.isBlank() || "KR".equalsIgnoreCase(country.trim())) return true;
        return enabled.stream().anyMatch(c -> c.equalsIgnoreCase(country.trim()));
    }

    public String notEnabledMessage(String country) {
        String names = enabled.stream().map(Destinations::countryNameKo).distinct().collect(Collectors.joining("·"));
        String name = Destinations.countryNameKo(country);
        String subject = name.equals(country) ? "이 여행지는" : name + " 여행지는";
        return subject + " 아직 준비 중이에요. 지금은 " + names + " 여행지로 일정을 만들 수 있어요.";
    }

    /**
     * 사용자가 말한 여행지가 열지 않은 나라의 목록 여행지면 안내 문구. 목록에 없는 이름은 나라를 알 수 없어
     * 생성 단계의 국가 확인이 막는다.
     */
    public Optional<String> closedDestinationMessage(String destination) {
        return destinations.resolve(destination)
                .map(Destination::country)
                .filter(country -> !isEnabled(country))
                .map(this::notEnabledMessage);
    }
}
