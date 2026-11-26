package com.shg.trip.shgtrip.domain.itinerary.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 사용자가 말한 여행의 뼈대. 여행지 문장 하나로는 담기지 않아 따로 받는다.
 *
 * @param visitAreas 들르겠다고 한 도시·지역(삿포로, 오타루). 모두 일정에 들어가야 한다
 * @param stays      묵는 순서와 박 수(삿포로 2박 → 후라노 1박). 첫 밤부터 차례로 채운다
 * @param transports 지역마다 다니는 방법(후라노는 렌터카). 없는 지역은 transportPref를 쓴다
 */
public record TripLayout(
        @Size(max = 10, message = "방문 지역은 최대 10개까지 가능합니다.")
        List<@NotBlank(message = "방문 지역이 비어 있습니다.")
             @Size(max = 30, message = "방문 지역은 30자 이내여야 합니다.") String> visitAreas,

        @Size(max = 10, message = "숙박 계획은 최대 10개까지 가능합니다.")
        List<@NotNull(message = "숙박 계획 값이 비어 있습니다.") @Valid Stay> stays,

        @Size(max = 10, message = "지역별 이동 방법은 최대 10개까지 가능합니다.")
        List<@NotNull(message = "지역별 이동 방법 값이 비어 있습니다.") @Valid AreaTransport> transports
) {

    public TripLayout {
        visitAreas = visitAreas == null ? List.of() : List.copyOf(visitAreas);
        stays = stays == null ? List.of() : List.copyOf(stays);
        transports = transports == null ? List.of() : List.copyOf(transports);
    }

    public boolean isEmpty() {
        return visitAreas.isEmpty() && stays.isEmpty() && transports.isEmpty();
    }

    public record Stay(
            @NotBlank(message = "숙박 지역이 비어 있습니다.")
            @Size(max = 30, message = "숙박 지역은 30자 이내여야 합니다.")
            String area,
            @Min(value = 1, message = "숙박은 1박 이상이어야 합니다.")
            @Max(value = 30, message = "숙박은 30박 이하로 입력해주세요.")
            int nights
    ) {}

    /** @param mode walk·car·transit·taxi·any. any는 가까우면 택시, 멀면 버스다 */
    public record AreaTransport(
            @NotBlank(message = "이동 방법의 지역이 비어 있습니다.")
            @Size(max = 30, message = "이동 방법의 지역은 30자 이내여야 합니다.")
            String area,
            @NotBlank(message = "이동 방법이 비어 있습니다.")
            @Pattern(regexp = WizardOptionIds.AREA_TRANSPORT_PATTERN, message = "지원하지 않는 이동 수단입니다.")
            String mode
    ) {}
}
