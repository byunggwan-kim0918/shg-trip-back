package com.shg.trip.shgtrip.domain.planning.service.validation;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.planning.dto.VectorEnrichedInput;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manual 모드에서 사용자가 선택한 장소가 여행지 지역과 일치하는지 검증한다.
 *
 * 검증 기준은 enrich(Haiku)가 산출한 {@link VectorEnrichedInput#regions()}이며,
 * 여기서 별도의 여행지→지역 매핑을 두지 않는다(단일 소스 오브 트루스).
 *
 * 방어적으로 다음 경우는 검증을 건너뛴다(오탐 방지):
 * <ul>
 *   <li>선택 장소가 없음(AUTO 모드)</li>
 *   <li>enrich regions가 비어있음(정규화 실패/불확실)</li>
 *   <li>country가 KR이 아님(해외 place.region 포맷 미정의)</li>
 *   <li>개별 장소의 region이 null</li>
 * </ul>
 * 다지역 여행(regionAllocation)은 그 안의 모든 지역을 허용 집합에 포함해 오탐을 막는다.
 */
@Slf4j
@Component
public class PlaceRegionValidator {

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

        // 해외는 place.region 포맷이 미정의 → 검증 스킵
        String country = enrichedInput.country();
        if (country != null && !"KR".equalsIgnoreCase(country)) {
            log.debug("지역 검증 스킵(해외): country={}", country);
            return;
        }

        Set<String> allowedRegions = buildAllowedRegions(enrichedInput);
        if (allowedRegions.isEmpty()) {
            // enrich가 지역을 확정하지 못함 → false-reject 방지 위해 스킵
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
        return allowed;
    }
}
