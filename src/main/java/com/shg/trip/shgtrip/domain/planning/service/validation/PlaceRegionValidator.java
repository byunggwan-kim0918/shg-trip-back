package com.shg.trip.shgtrip.domain.planning.service.validation;

import com.shg.trip.shgtrip.domain.place.destination.Destination;
import com.shg.trip.shgtrip.domain.place.destination.Destinations;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 검증 기준은 enrich가 산출한 {@link VectorEnrichedInput#regions()}다. 오탐을 막으려고 regions가 비었거나 장소 region이
 * null이면 건너뛰고, regionAllocation 지역은 모두 허용한다. 해외는 같은 상위 여행지(destinations.json parent)에 묶인
 * 여행지를 모두 허용한다(타이베이 여행의 지우펀, 교토 여행의 나라 장소).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceRegionValidator {

    private final Destinations destinations;

    /**
     * 선택 장소의 지역이 여행지 지역과 다르면 {@link BusinessException}을 던진다.
     *
     * @param selectedPlaces 사용자가 선택한 실제 DB 장소(Manual 모드). null/빈 리스트면 통과.
     * @param enrichedInput  enrich 결과(regions, regionAllocation, country 등)
     */
    public void validate(List<Place> selectedPlaces, VectorEnrichedInput enrichedInput) {
        if (selectedPlaces == null || selectedPlaces.isEmpty()) {
            return;
        }

        Set<String> allowedRegions = buildAllowedRegions(enrichedInput);
        if (allowedRegions.isEmpty()) {
            // enrich가 지역을 확정하지 못하면 false-reject를 막으려고 건너뛴다.
            log.debug("지역 검증 스킵(regions 비어있음): destination={}", enrichedInput.destination());
            return;
        }

        List<Place> mismatched = selectedPlaces.stream()
                .filter(p -> p.getRegion() != null && !allowedRegions.contains(p.getRegion()))
                .collect(Collectors.toList());

        if (mismatched.isEmpty()) {
            return;
        }

        String destinationLabel = enrichedInput.normalizedDestination() != null
                ? enrichedInput.normalizedDestination()
                : enrichedInput.destination();

        String detail = mismatched.stream()
                .map(p -> "'" + p.getName() + "'(" + p.getRegion() + ")")
                .collect(Collectors.joining(", "));

        String message = String.format(
                "선택하신 장소 %s은(는) 여행지 '%s'와 다른 지역입니다. "
                        + "여행지를 변경하시거나 해당 장소를 선택 해제해주세요.",
                detail, destinationLabel);

        log.info("지역 불일치 거부: destination={}, allowed={}, mismatched={}",
                destinationLabel, allowedRegions,
                mismatched.stream().map(Place::getName).collect(Collectors.toList()));

        throw new BusinessException(ErrorCode.PLACE_REGION_MISMATCH, message);
    }

    /** enrich regions + regionAllocation의 모든 지역을 허용 집합으로 합친다. */
    private Set<String> buildAllowedRegions(VectorEnrichedInput enrichedInput) {
        Set<String> allowed = new HashSet<>();
        if (enrichedInput.regions() != null) {
            enrichedInput.regions().stream()
                    .filter(r -> r != null && !r.isBlank())
                    .forEach(allowed::add);
        }
        if (enrichedInput.regionAllocation() != null) {
            for (List<String> regions : enrichedInput.regionAllocation().values()) {
                if (regions == null) continue;
                regions.stream()
                        .filter(r -> r != null && !r.isBlank())
                        .forEach(allowed::add);
            }
        }
        Set<String> roots = allowed.stream().map(destinations::rootOf).collect(Collectors.toSet());
        destinations.all().stream()
                .map(Destination::name)
                .filter(name -> roots.contains(destinations.rootOf(name)))
                .forEach(allowed::add);
        return allowed;
    }
}
