package com.shg.trip.shgtrip.domain.itinerary.dto;

import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.place.entity.Place;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public record ItineraryResponse(
        Long id,
        String title,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalBudget,
        BigDecimal estimatedCost,
        String coverImage,
        /** coverImage의 작성자·라이선스·원본 페이지. 표시 의무가 없는 사진이면 null이다. */
        String coverImageCredit,
        String coverImageLicense,
        String coverImageSourceUrl,
        List<String> tags,
        String status,
        /** 해소하지 못한 품질 문제 안내. 편집하면 현재 일정 기준으로 다시 만든다. 없거나 공유 응답이면 빈 목록이다. */
        List<String> qualityNotices,
        List<ItineraryStepResponse> steps,
        /** 곧 한국어 이름·소개가 채워질 스텝 장소. 화면은 이 장소의 소개 자리를 준비 중으로 두고 다시 조회한다. */
        List<Long> enrichingPlaceIds,
        /** 이야기 작성 상태(PENDING·DONE·FAILED). 화면은 PENDING일 때만 다시 조회한다. 판별하지 못한 옛 일정은 null이다. */
        String storyStatus,
        /** 장소·이동 비용 합계. 요금을 모르는 이동을 따로 세어 아는 금액 합과 구분한다. */
        CostSummary costSummary
) {
    /**
     * @param cover coverPlaceId로 읽은 장소. 없으면 null. 저장된 coverImage 컬럼은 만료된 주소라 쓰지 않는다.
     */
    public static ItineraryResponse from(Itinerary itinerary, Place cover, List<Long> enrichingPlaceIds) {
        boolean hasImage = cover != null && cover.getImageUrl() != null;
        List<ItineraryStepResponse> steps = itinerary.getSteps().stream()
                // reorder는 stepOrder 필드만 바꾸므로 배열 순서를 실제 순서에 맞춰 다시 정렬한다.
                .sorted(Comparator.comparingInt(ItineraryStep::getDayNumber)
                        .thenComparingInt(ItineraryStep::getStepOrder))
                .map(ItineraryStepResponse::from)
                .toList();
        return new ItineraryResponse(
                itinerary.getId(),
                itinerary.getTitle(),
                itinerary.getDestination(),
                itinerary.getStartDate(),
                itinerary.getEndDate(),
                itinerary.getTotalBudget(),
                itinerary.getEstimatedCost(),
                hasImage ? cover.getImageUrl() : null,
                hasImage ? cover.getImageCredit() : null,
                hasImage ? cover.getImageLicense() : null,
                hasImage ? cover.getImageSourceUrl() : null,
                itinerary.getTags(),
                itinerary.getStatus().name(),
                itinerary.getQualityNotices() != null ? itinerary.getQualityNotices() : List.of(),
                steps,
                enrichingPlaceIds,
                itinerary.getStoryStatus() != null ? itinerary.getStoryStatus().name() : null,
                CostSummary.of(steps, itinerary.getTotalBudget())
        );
    }

    /**
     * 비용 합계(원). 요금을 모르는 이동은 0원으로 더하지 않고 unknownTransportLegs로 센다. 금액에 넣지 못한 항목
     * (통행료·주차비 등)이 있어도 합계가 모자란다. 이 둘 중 하나라도 있으면 complete는 false이고 total은 확정 비용이 아니다.
     *
     * @param travellers  요금에 반영한 여행 인원. 모르면 null이고 perPerson도 null이다
     * @param overBudget  예산을 넘는지. 아는 금액만으로 넘으면 true, 모든 금액을 알고 안 넘으면 false, 그 밖은 null
     * @param fareBasis   요금 근거별 이동 수. 근거 없이 추정만 한 옛 구간은 NONE이다
     * @param excludes    어느 구간 요금에도 넣지 못한 항목(TOLL·PARKING·NIGHT_SURCHARGE 등)
     */
    public record CostSummary(BigDecimal placeCost, BigDecimal transportCost, int unknownTransportLegs,
                              BigDecimal total, boolean complete, Integer travellers, BigDecimal perPerson,
                              List<Day> days, java.util.Map<String, Integer> fareBasis, Boolean overBudget,
                              List<String> excludes) {

        public record Day(int dayNumber, BigDecimal placeCost, BigDecimal transportCost, int unknownTransportLegs) {}

        static CostSummary of(List<ItineraryStepResponse> steps, BigDecimal budget) {
            java.util.Map<Integer, BigDecimal[]> byDay = new java.util.TreeMap<>();
            java.util.Map<Integer, Integer> unknownByDay = new java.util.TreeMap<>();
            java.util.Map<String, Integer> basis = new java.util.TreeMap<>();
            java.util.Set<String> excludes = new java.util.TreeSet<>();
            Integer travellers = null;
            for (ItineraryStepResponse s : steps) {
                int day = s.dayNumber() == null ? 0 : s.dayNumber();
                BigDecimal[] sums = byDay.computeIfAbsent(day, d -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                unknownByDay.putIfAbsent(day, 0);
                if (s.estimatedCost() != null) sums[0] = sums[0].add(s.estimatedCost());
                if (s.transportationMode() == null) continue;
                var fare = s.transportationRoute() == null ? null : s.transportationRoute().fare();
                basis.merge(fare == null ? "NONE" : fare.basis(), 1, Integer::sum);
                if (fare != null) {
                    excludes.addAll(fare.excludes());
                    if (travellers == null) travellers = fare.travellers();
                }
                if (s.transportationCost() != null) sums[1] = sums[1].add(s.transportationCost());
                else unknownByDay.merge(day, 1, Integer::sum);
            }
            BigDecimal places = BigDecimal.ZERO;
            BigDecimal transport = BigDecimal.ZERO;
            List<Day> days = new java.util.ArrayList<>();
            for (var e : byDay.entrySet()) {
                places = places.add(e.getValue()[0]);
                transport = transport.add(e.getValue()[1]);
                days.add(new Day(e.getKey(), e.getValue()[0], e.getValue()[1], unknownByDay.get(e.getKey())));
            }
            int unknown = unknownByDay.values().stream().mapToInt(Integer::intValue).sum();
            BigDecimal total = places.add(transport);
            boolean complete = unknown == 0 && excludes.isEmpty();
            BigDecimal perPerson = travellers == null || travellers < 1 ? null
                    : total.divide(BigDecimal.valueOf(travellers), 0, java.math.RoundingMode.HALF_UP);
            Boolean over = budget == null ? null : total.compareTo(budget) > 0 ? Boolean.TRUE
                    : complete ? Boolean.FALSE : null;
            return new CostSummary(places, transport, unknown, total, complete, travellers, perPerson, days, basis,
                    over, List.copyOf(excludes));
        }
    }

    /** 공유 링크는 누구나 열 수 있어 소유자용 품질 안내(사용자가 적은 장소 이름 포함)를 뺀다. */
    public ItineraryResponse forSharing() {
        return new ItineraryResponse(id, title, destination, startDate, endDate, totalBudget, estimatedCost, coverImage,
                coverImageCredit, coverImageLicense, coverImageSourceUrl, tags, status, List.of(), steps, List.of(),
                storyStatus, costSummary);
    }
}
