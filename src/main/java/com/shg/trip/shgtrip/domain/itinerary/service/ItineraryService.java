package com.shg.trip.shgtrip.domain.itinerary.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.*;
import com.shg.trip.shgtrip.domain.itinerary.entity.AlternativeOption;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryEditEvent;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryEditEventRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
import com.shg.trip.shgtrip.domain.planning.service.QualityGate;
import com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.util.GeoUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryService {

    private static final int SHARE_EXPIRE_DAYS = 7;

    private final ItineraryRepository itineraryRepository;
    private final PlaceRepository placeRepository;
    private final GenerationTraceRepository generationTraceRepository;
    private final ItineraryEditEventRepository editEventRepository;
    private final QualityGate qualityGate;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public ItineraryResponse getItinerary(Long itineraryId, Long userId) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        return toResponse(itinerary);
    }

    @Transactional(readOnly = true)
    public Page<ItinerarySummaryResponse> getMyItineraries(Long userId, Pageable pageable) {
        Page<Itinerary> page = itineraryRepository
                .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable);

        // 사진은 적재 배치(TourAPI·위키미디어 공용)가 채운 주소뿐이라 조회 시점에 채울 경로가 없다.
        // Google 사진은 Maps 약관 3.2.3(a)가 캐싱을 금지해 쓰지 않는다.
        List<Long> coverPlaceIds = page.getContent().stream()
                .map(Itinerary::getCoverPlaceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<Place> coverPlaces = coverPlaceIds.isEmpty()
                ? List.of()
                : placeRepository.findAllById(coverPlaceIds);

        Map<Long, Place> coverById = coverPlaces.stream()
                .collect(Collectors.toMap(Place::getId, p -> p));

        return page.map(i -> ItinerarySummaryResponse.from(
                i, i.getCoverPlaceId() != null ? coverById.get(i.getCoverPlaceId()) : null));
    }

    /** 표지 장소가 스텝 밖(여행지 대표 사진)이면 따로 읽는다. */
    private ItineraryResponse toResponse(Itinerary itinerary) {
        Long coverId = itinerary.getCoverPlaceId();
        Place cover = coverId == null ? null : itinerary.getSteps().stream()
                .map(ItineraryStep::getPlace)
                .filter(p -> p != null && coverId.equals(p.getId()))
                .findFirst()
                .orElseGet(() -> placeRepository.findById(coverId).orElse(null));
        return ItineraryResponse.from(itinerary, cover);
    }

    @Transactional
    public ItineraryResponse updateItinerary(Long itineraryId, Long userId, ItineraryUpdateRequest request) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        itinerary.updateInfo(request.title(), request.tags());
        return toResponse(itinerary);
    }

    @Transactional
    public ItineraryResponse finalizeItinerary(Long itineraryId, Long userId) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        if (itinerary.getStatus() == Itinerary.ItineraryStatus.FINALIZED) {
            return toResponse(itinerary);
        }
        if (itinerary.getSteps().isEmpty()) {
            throw new BusinessException(ErrorCode.ITINERARY_EMPTY);
        }
        itinerary.complete();
        recordEdit(itinerary, userId, ItineraryEditEvent.Type.FINALIZE, null, null,
                payload("stepCount", itinerary.getSteps().size()));
        return toResponse(itinerary);
    }

    @Transactional
    public void deleteItinerary(Long itineraryId, Long userId) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        recordEdit(itinerary, userId, ItineraryEditEvent.Type.DELETE_ITINERARY, null, null,
                payload("stepCount", itinerary.getSteps().size(),
                        "finalized", itinerary.getStatus() == Itinerary.ItineraryStatus.FINALIZED));
        itinerary.softDelete();
        // 요청 문장이 들어 있어 개인정보처리방침상 같이 지운다
        generationTraceRepository.deleteByItineraryId(itineraryId);
    }

    @Transactional
    public ShareLinkResponse generateShareLink(Long itineraryId, Long userId) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        // 다시 누를 때마다 새로 발급하면 이미 보낸 링크가 죽으므로 유효한 토큰은 그대로 돌려준다.
        if (itinerary.getShareToken() != null
                && (itinerary.getShareExpiresAt() == null || itinerary.getShareExpiresAt().isAfter(OffsetDateTime.now()))) {
            return new ShareLinkResponse(itinerary.getShareToken(), itinerary.getShareExpiresAt());
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(SHARE_EXPIRE_DAYS);
        itinerary.generateShareToken(token, expiresAt);
        return new ShareLinkResponse(token, expiresAt);
    }

    @Transactional(readOnly = true)
    public ItineraryResponse getSharedItinerary(String shareToken) {
        Itinerary itinerary = itineraryRepository.findByShareToken(shareToken)
                .orElseThrow(() -> new BusinessException(ErrorCode.ITINERARY_NOT_FOUND, "공유 링크를 찾을 수 없습니다."));

        if (itinerary.getShareExpiresAt() != null
                && itinerary.getShareExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new BusinessException(ErrorCode.ITINERARY_NOT_FOUND, "만료된 공유 링크입니다.");
        }
        return toResponse(itinerary).forSharing();
    }

    /**
     * 선택 후 해당 step과 다음 step의 교통 거리를 좌표 기반으로 재계산한다.
     */
    @Transactional
    public ItineraryResponse selectAlternative(Long itineraryId, Long stepId, Long alternativeId, Long userId) {
        Itinerary itinerary = findEditableForUpdate(itineraryId, userId);

        ItineraryStep step = itinerary.getSteps().stream()
                .filter(s -> s.getId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "일정 단계를 찾을 수 없습니다."));

        AlternativeOption selected = step.getAlternatives().stream()
                .filter(a -> a.getId().equals(alternativeId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "대안 장소를 찾을 수 없습니다. 이미 선택된 대안이거나 유효하지 않은 요청입니다."));

        Long fromPlaceId = placeIdOf(step);
        step.selectAlternative(selected);
        recordEdit(itinerary, userId, ItineraryEditEvent.Type.SELECT_ALTERNATIVE, step.getDayNumber(), stepId,
                payload("fromPlaceId", fromPlaceId, "toPlaceId", placeIdOf(step), "stepOrder", step.getStepOrder()));

        // DELETE가 INSERT보다 먼저 나가야 Hibernate orphanRemoval flush가 충돌하지 않는다.
        entityManager.flush();

        recalculateTransportation(itinerary.getSteps(), step);
        itinerary.recalculateEstimatedCost();
        qualityGate.reflectEdit(itinerary);

        return toResponse(itinerary);
    }

    /**
     * 시간(startTime/endTime)은 슬롯에 고정되고 장소만 재배치되며, 교통 정보는 새 순서로 재계산한다.
     * orderedStepIds가 해당 day의 전체 스텝 집합과 정확히 일치하지 않으면 거부한다.
     */
    @Transactional
    public ItineraryResponse reorderSteps(Long itineraryId, Long userId, Integer dayNumber, List<Long> orderedStepIds) {
        Itinerary itinerary = findEditableForUpdate(itineraryId, userId);

        List<ItineraryStep> daySteps = itinerary.getSteps().stream()
                .filter(s -> dayNumber.equals(s.getDayNumber()))
                .toList();
        if (daySteps.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "해당 날짜의 일정 단계를 찾을 수 없습니다.");
        }

        Set<Long> dayStepIds = daySteps.stream().map(ItineraryStep::getId).collect(Collectors.toSet());
        Set<Long> requestedIds = new LinkedHashSet<>(orderedStepIds);
        if (requestedIds.size() != orderedStepIds.size() || !requestedIds.equals(dayStepIds)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "재정렬 요청이 해당 날짜의 단계 목록과 일치하지 않습니다.");
        }

        verifyAnchorsKept(daySteps, orderedStepIds);
        TransferLeg transfer = transferLegOf(daySteps);

        // 슬롯 시간을 스텝 변경 전에 먼저 캡처해야 뮤테이션 중 값이 섞이지 않는다.
        List<Integer> slots = daySteps.stream()
                .map(ItineraryStep::getStepOrder)
                .sorted()
                .toList();
        Map<Integer, String[]> slotTimes = daySteps.stream()
                .collect(Collectors.toMap(
                        ItineraryStep::getStepOrder,
                        s -> new String[]{s.getStartTime(), s.getEndTime()}));
        Map<Long, ItineraryStep> byId = daySteps.stream()
                .collect(Collectors.toMap(ItineraryStep::getId, s -> s));
        List<Long> placesBefore = daySteps.stream()
                .sorted(Comparator.comparingInt(ItineraryStep::getStepOrder))
                .map(ItineraryService::placeIdOf)
                .toList();
        List<Long> placesAfter = orderedStepIds.stream().map(id -> placeIdOf(byId.get(id))).toList();
        if (!placesBefore.equals(placesAfter)) {
            recordEdit(itinerary, userId, ItineraryEditEvent.Type.REORDER, dayNumber, null,
                    payload("before", placesBefore, "after", placesAfter));
        }
        for (int i = 0; i < orderedStepIds.size(); i++) {
            int slot = slots.get(i);
            String[] times = slotTimes.get(slot);
            byId.get(orderedStepIds.get(i)).assignSlot(slot, times[0], times[1]);
        }
        entityManager.flush();

        recalculateDayTransportation(itinerary.getSteps(), dayNumber, transfer);
        itinerary.recalculateEstimatedCost();
        qualityGate.reflectEdit(itinerary);
        return toResponse(itinerary);
    }

    /**
     * day의 마지막 1스텝은 삭제할 수 없다. 남은 스텝 순서와 해당 day의 이동 정보는 재계산한다.
     */
    @Transactional
    public ItineraryResponse deleteStep(Long itineraryId, Long userId, Long stepId) {
        Itinerary itinerary = findEditableForUpdate(itineraryId, userId);

        ItineraryStep target = itinerary.getSteps().stream()
                .filter(s -> s.getId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "일정 단계를 찾을 수 없습니다."));

        Integer dayNumber = target.getDayNumber();
        long dayStepCount = itinerary.getSteps().stream()
                .filter(s -> dayNumber.equals(s.getDayNumber()))
                .count();
        if (dayStepCount <= 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "해당 날짜의 마지막 일정은 삭제할 수 없습니다.");
        }
        recordEdit(itinerary, userId, ItineraryEditEvent.Type.DELETE_STEP, dayNumber, stepId,
                payload("placeId", placeIdOf(target), "stepOrder", target.getStepOrder()));
        TransferLeg transfer = transferLegOf(itinerary.getSteps().stream()
                .filter(s -> dayNumber.equals(s.getDayNumber())).toList());

        itinerary.removeStep(target);
        entityManager.flush(); // orphanRemoval DELETE가 재정렬 UPDATE보다 먼저 나가야 한다

        List<ItineraryStep> remaining = itinerary.getSteps().stream()
                .sorted(Comparator.comparingInt(ItineraryStep::getDayNumber)
                        .thenComparingInt(ItineraryStep::getStepOrder))
                .toList();
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).assignOrder(i);
        }
        entityManager.flush();

        recalculateDayTransportation(itinerary.getSteps(), dayNumber, transfer);
        itinerary.recalculateEstimatedCost();
        qualityGate.reflectEdit(itinerary);
        return toResponse(itinerary);
    }

    /** 다른 도시에서 넘어온 날 첫 스텝의 도시 간 이동 구간. 그날 순서를 바꿔도 이동 자체는 그대로다. */
    private record TransferLeg(String mode, Integer duration, BigDecimal distance, BigDecimal cost) {}

    /** 그날 첫 스텝이 도시 간 이동 구간이면 그 값, 아니면 null. */
    private static TransferLeg transferLegOf(List<ItineraryStep> daySteps) {
        return daySteps.stream()
                .min(Comparator.comparingInt(ItineraryStep::getStepOrder))
                .filter(s -> s.getTransportationMode() != null
                        && ScheduleTuning.INTERCITY_LEG_MODES.contains(s.getTransportationMode()))
                .map(s -> new TransferLeg(s.getTransportationMode(), s.getTransportationDuration(),
                        s.getTransportationDistance(), s.getTransportationCost()))
                .orElse(null);
    }

    /**
     * day 첫 스텝은 인바운드 leg가 없어 교통정보를 비운다. 생성 파이프라인과 같은 시맨틱이다.
     * 좌표가 없어 계산할 수 없는 구간도 낡은 leg가 남지 않게 비운다.
     *
     * @param transfer 편집 전 첫 스텝의 도시 간 이동 구간. 있으면 새 첫 스텝에 둔다
     */
    private void recalculateDayTransportation(List<ItineraryStep> allSteps, Integer dayNumber, TransferLeg transfer) {
        List<ItineraryStep> daySteps = allSteps.stream()
                .filter(s -> dayNumber.equals(s.getDayNumber()))
                .sorted(Comparator.comparingInt(ItineraryStep::getStepOrder))
                .toList();
        for (int i = 0; i < daySteps.size(); i++) {
            ItineraryStep step = daySteps.get(i);
            if (i == 0) {
                if (transfer != null) {
                    step.updateTransportation(transfer.mode(), transfer.duration(), transfer.distance(), transfer.cost());
                } else {
                    step.updateTransportation(null, null, null, null);
                }
                continue;
            }
            GeoUtils.TransportLeg leg = computeLeg(daySteps.get(i - 1), step);
            if (leg != null) {
                step.updateTransportation(leg.mode(), leg.durationMin(), leg.distanceKm(), leg.cost());
            } else {
                step.updateTransportation(null, null, null, null);
            }
        }
    }

    /**
     * 일차 경계를 넘는 구간은 재계산하지 않는다.
     */
    private void recalculateTransportation(List<ItineraryStep> allSteps, ItineraryStep changedStep) {
        List<ItineraryStep> sorted = allSteps.stream()
                .sorted(Comparator.comparingInt(ItineraryStep::getStepOrder))
                .toList();

        int idx = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getId().equals(changedStep.getId())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;

        if (idx > 0) {
            ItineraryStep prev = sorted.get(idx - 1);
            if (prev.getDayNumber().equals(changedStep.getDayNumber())) {
                updateDistance(prev, changedStep);
            }
        }

        if (idx < sorted.size() - 1) {
            ItineraryStep next = sorted.get(idx + 1);
            if (changedStep.getDayNumber().equals(next.getDayNumber())) {
                updateDistance(changedStep, next);
            }
        }
    }

    /**
     * 시간·비용·모드도 거리와 함께 {@link GeoUtils#estimateLeg}로 갱신해야 프론트 예산 합산(장소비+이동비)이 맞는다.
     * transportPref는 저장돼 있지 않아 "any"(혼합 단가)로 계산한다.
     */
    private void updateDistance(ItineraryStep from, ItineraryStep to) {
        GeoUtils.TransportLeg leg = computeLeg(from, to);
        if (leg == null) return; // 좌표 없음·비현실 구간이면 기존 교통정보를 유지한다
        to.updateTransportation(leg.mode(), leg.durationMin(), leg.distanceKm(), leg.cost());
        log.debug("Recalculated transport: {} → {} = {}km, {}원",
                from.getPlace().getName(), to.getPlace().getName(), leg.distanceKm(), leg.cost());
    }

    /**
     * 좌표가 없거나(0,0 포함) 비현실 구간이면 null.
     */
    private GeoUtils.TransportLeg computeLeg(ItineraryStep from, ItineraryStep to) {
        Place fromPlace = from.getPlace();
        Place toPlace = to.getPlace();
        if (fromPlace == null || toPlace == null) return null;
        if (fromPlace.getLatitude() == null || toPlace.getLatitude() == null) return null;
        if (GeoUtils.isZeroCoord(fromPlace.getLatitude(), fromPlace.getLongitude())
                || GeoUtils.isZeroCoord(toPlace.getLatitude(), toPlace.getLongitude())) return null;

        boolean hubTransfer = PlaceCategoryConstants.isOverseasHub(fromPlace.getCountry(), fromPlace.getCategory())
                || PlaceCategoryConstants.isOverseasHub(toPlace.getCountry(), toPlace.getCategory());
        return GeoUtils.estimateLeg(
                new double[]{fromPlace.getLatitude().doubleValue(), fromPlace.getLongitude().doubleValue()},
                new double[]{toPlace.getLatitude().doubleValue(), toPlace.getLongitude().doubleValue()},
                "any", ScheduleTuning.maxLegKm(hubTransfer), hubTransfer);
    }

    /**
     * 숙소·공항·역은 체크인·출발 시각에 묶여 있어 슬롯 시간만 받는 재정렬로 옮기면 09:30 체크인 같은 일정이 된다.
     */
    private static void verifyAnchorsKept(List<ItineraryStep> daySteps, List<Long> orderedStepIds) {
        List<ItineraryStep> current = daySteps.stream()
                .sorted(Comparator.comparingInt(ItineraryStep::getStepOrder))
                .toList();
        for (int i = 0; i < current.size(); i++) {
            ItineraryStep step = current.get(i);
            if (isAnchor(step) && !step.getId().equals(orderedStepIds.get(i))) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "숙소와 공항·역은 자리를 옮길 수 없어요.");
            }
        }
    }

    private static boolean isAnchor(ItineraryStep step) {
        Place place = step.getPlace();
        if (place == null) return false;
        if (place.getPlaceRole() != null) {
            return "LODGING".equals(place.getPlaceRole()) || "TRANSIT".equals(place.getPlaceRole());
        }
        String major = PlaceCategoryConstants.majorCategory(place.getCategory());
        return "LODGING".equals(major) || "TRANSIT_HUB".equals(major);
    }

    /** 행 잠금을 먼저 잡아야 동시에 들어온 두 번째 편집이 앞 편집이 지운 대안·스텝을 다시 지우려다 500이 나지 않는다. */
    private Itinerary findEditableForUpdate(Long itineraryId, Long userId) {
        itineraryRepository.findByIdForUpdate(itineraryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ITINERARY_NOT_FOUND, "일정을 찾을 수 없습니다."));
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        verifyEditable(itinerary);
        return itinerary;
    }

    /** 확정은 장소 구성을 잠근다. 제목·태그는 표시 정보라 확정 뒤에도 고칠 수 있다. */
    private void verifyEditable(Itinerary itinerary) {
        if (itinerary.getStatus() == Itinerary.ItineraryStatus.FINALIZED) {
            throw new BusinessException(ErrorCode.ITINERARY_FINALIZED);
        }
    }

    private Itinerary findAndVerifyOwner(Long itineraryId, Long userId) {
        Itinerary itinerary = itineraryRepository.findByIdWithDetails(itineraryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ITINERARY_NOT_FOUND, "일정을 찾을 수 없습니다."));

        if (!itinerary.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ITINERARY_ACCESS_DENIED, "일정에 접근할 권한이 없습니다.");
        }
        return itinerary;
    }

    private void recordEdit(Itinerary itinerary, Long userId, ItineraryEditEvent.Type type, Integer dayNumber,
                            Long stepId, Map<String, Object> payload) {
        editEventRepository.save(ItineraryEditEvent.of(itinerary.getId(), userId, type, dayNumber, stepId, payload));
    }

    /** 키·값 쌍 → payload. 값에 null이 올 수 있어 Map.of를 쓰지 않는다. */
    private static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) map.put((String) keyValues[i], keyValues[i + 1]);
        return map;
    }

    private static Long placeIdOf(ItineraryStep step) {
        return step.getPlace() == null ? null : step.getPlace().getId();
    }
}
