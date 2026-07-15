package com.shg.trip.shgtrip.domain.itinerary.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.*;
import com.shg.trip.shgtrip.domain.itinerary.entity.AlternativeOption;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.s3.PlaceImageAsyncRecovery;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 일정 관리 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryService {

    private static final int SHARE_EXPIRE_DAYS = 7;

    private final ItineraryRepository itineraryRepository;
    private final PlaceRepository placeRepository;
    private final PlaceImageAsyncRecovery asyncRecovery;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public ItineraryResponse getItinerary(Long itineraryId, Long userId) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        triggerAsyncImageRecovery(itinerary);
        return ItineraryResponse.from(itinerary);
    }

    private void triggerAsyncImageRecovery(Itinerary itinerary) {
        itinerary.getSteps().stream()
                .map(ItineraryStep::getPlace)
                .filter(place -> place != null && place.getImageUrl() == null && place.getPhotoReference() != null)
                .forEach(place -> asyncRecovery.tryUploadAsync(place.getId(), place.getPhotoReference()));
    }

    @Transactional(readOnly = true)
    public Page<ItinerarySummaryResponse> getMyItineraries(Long userId, Pageable pageable) {
        Page<Itinerary> page = itineraryRepository
                .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable);

        // 커버 place들의 현재 imageUrl을 한 번에 해소한다(만료되는 presigned URL을 저장하지 않고 read-time 조회).
        List<Long> coverPlaceIds = page.getContent().stream()
                .map(Itinerary::getCoverPlaceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<Place> coverPlaces = coverPlaceIds.isEmpty()
                ? List.of()
                : placeRepository.findAllById(coverPlaceIds);

        Map<Long, String> coverUrlById = coverPlaces.stream()
                .filter(p -> p.getImageUrl() != null)
                .collect(Collectors.toMap(Place::getId, Place::getImageUrl));

        // 아직 이미지가 없는 커버 place는 비동기 업로드를 트리거 — 다음 조회 때 채워진다.
        coverPlaces.stream()
                .filter(p -> p.getImageUrl() == null && p.getPhotoReference() != null)
                .forEach(p -> asyncRecovery.tryUploadAsync(p.getId(), p.getPhotoReference()));

        return page.map(i -> ItinerarySummaryResponse.from(
                i, i.getCoverPlaceId() != null ? coverUrlById.get(i.getCoverPlaceId()) : null));
    }

    @Transactional
    public ItineraryResponse updateItinerary(Long itineraryId, Long userId, ItineraryUpdateRequest request) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        itinerary.updateInfo(request.title(), request.tags());
        return ItineraryResponse.from(itinerary);
    }

    @Transactional
    public ItineraryResponse finalizeItinerary(Long itineraryId, Long userId) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        itinerary.complete();
        return ItineraryResponse.from(itinerary);
    }

    @Transactional
    public void deleteItinerary(Long itineraryId, Long userId) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        itinerary.softDelete();
    }

    @Transactional
    public ShareLinkResponse generateShareLink(Long itineraryId, Long userId) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
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
        return ItineraryResponse.from(itinerary);
    }

    /**
     * 일정 단계의 대안 장소 선택.
     * 선택 후 해당 step과 다음 step의 교통 거리를 좌표 기반으로 재계산한다.
     */
    @Transactional
    public ItineraryResponse selectAlternative(Long itineraryId, Long stepId, Long alternativeId, Long userId) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);

        ItineraryStep step = itinerary.getSteps().stream()
                .filter(s -> s.getId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "일정 단계를 찾을 수 없습니다."));

        AlternativeOption selected = step.getAlternatives().stream()
                .filter(a -> a.getId().equals(alternativeId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "대안 장소를 찾을 수 없습니다. 이미 선택된 대안이거나 유효하지 않은 요청입니다."));

        step.selectAlternative(selected);

        // Ensure DELETE executes before INSERT to avoid Hibernate orphanRemoval flush conflicts
        entityManager.flush();

        // 교통 거리 재계산
        recalculateTransportation(itinerary.getSteps(), step);

        return ItineraryResponse.from(itinerary);
    }

    /**
     * 같은 day 내 스텝 드래그 재정렬 (F3).
     * 시간(startTime/endTime)은 자리 고정 — 순서만 바뀌고 해당 day의 이동(교통) 정보만 재계산한다.
     * orderedStepIds는 해당 day의 전체 스텝 집합과 정확히 일치해야 한다(누락·중복·타 day/타 일정 주입 거부).
     */
    @Transactional
    public ItineraryResponse reorderSteps(Long itineraryId, Long userId, Integer dayNumber, List<Long> orderedStepIds) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);

        List<ItineraryStep> daySteps = itinerary.getSteps().stream()
                .filter(s -> dayNumber.equals(s.getDayNumber()))
                .toList();
        if (daySteps.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "해당 날짜의 일정 단계를 찾을 수 없습니다.");
        }

        // 요청 순서가 해당 day 스텝 집합과 정확히 일치하는지 검증
        Set<Long> dayStepIds = daySteps.stream().map(ItineraryStep::getId).collect(Collectors.toSet());
        Set<Long> requestedIds = new LinkedHashSet<>(orderedStepIds);
        if (requestedIds.size() != orderedStepIds.size() || !requestedIds.equals(dayStepIds)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "재정렬 요청이 해당 날짜의 단계 목록과 일치하지 않습니다.");
        }

        // 이 day가 점유한 stepOrder 슬롯을 그대로 새 순서에 재배정 → 다른 day의 위치는 불변
        List<Integer> slots = daySteps.stream()
                .map(ItineraryStep::getStepOrder)
                .sorted()
                .toList();
        Map<Long, ItineraryStep> byId = daySteps.stream()
                .collect(Collectors.toMap(ItineraryStep::getId, s -> s));
        for (int i = 0; i < orderedStepIds.size(); i++) {
            byId.get(orderedStepIds.get(i)).assignOrder(slots.get(i));
        }
        entityManager.flush();

        recalculateDayTransportation(itinerary.getSteps(), dayNumber);
        return ItineraryResponse.from(itinerary);
    }

    /**
     * 스텝(스톱) 삭제 (F3). 남은 스텝의 순서를 재정렬하고 해당 day의 이동 정보를 재계산한다.
     * day 최소 1스텝을 보장(마지막 1개는 삭제 불가).
     */
    @Transactional
    public ItineraryResponse deleteStep(Long itineraryId, Long userId, Long stepId) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);

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

        itinerary.removeStep(target);
        entityManager.flush(); // orphanRemoval DELETE를 재정렬 UPDATE보다 먼저 확정

        // 남은 스텝 stepOrder를 (day, order) 기준 0..N-1로 재정렬해 빈 슬롯 제거
        List<ItineraryStep> remaining = itinerary.getSteps().stream()
                .sorted(Comparator.comparingInt(ItineraryStep::getDayNumber)
                        .thenComparingInt(ItineraryStep::getStepOrder))
                .toList();
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).assignOrder(i);
        }
        entityManager.flush();

        recalculateDayTransportation(itinerary.getSteps(), dayNumber);
        return ItineraryResponse.from(itinerary);
    }

    /**
     * 한 day 전체의 이동(교통) 정보를 순서대로 재계산한다(F3 reorder/delete 공용).
     * day 첫 스텝은 인바운드 leg가 없으므로 교통정보를 비우고(생성 파이프라인과 동일 시맨틱),
     * 이후 스텝은 직전 스텝과의 좌표 거리로 재계산한다. 좌표가 없어 계산 불가하면 비운다(스테일 leg 방지).
     */
    private void recalculateDayTransportation(List<ItineraryStep> allSteps, Integer dayNumber) {
        List<ItineraryStep> daySteps = allSteps.stream()
                .filter(s -> dayNumber.equals(s.getDayNumber()))
                .sorted(Comparator.comparingInt(ItineraryStep::getStepOrder))
                .toList();
        for (int i = 0; i < daySteps.size(); i++) {
            ItineraryStep step = daySteps.get(i);
            if (i == 0) {
                step.updateTransportation(null, null, null, null);
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
     * 대안 선택 후 인접 step의 교통 거리를 재계산. 일차 경계를 넘는 경우 skip.
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

    // 한 destination 내 하루 이동으로는 비현실적인 구간거리 임계값(RouteOptimizer와 동일).
    private static final double MAX_REASONABLE_LEG_KM = 200.0;

    /**
     * 대안 선택으로 장소가 바뀐 스텝의 교통 정보를 재계산한다. 거리뿐 아니라 시간·비용·모드를
     * RouteOptimizer 생성 경로와 동일한 공식({@link GeoUtils#estimateLeg})으로 함께 갱신해야
     * 프론트 예산 합산(장소비+이동비)이 어긋나지 않는다(대안 선택 후 거리는 새 장소·비용은 옛
     * 장소로 어긋나던 버그). transportPref는 저장돼 있지 않아 기본 "any"(혼합 단가)로 재계산한다.
     */
    private void updateDistance(ItineraryStep from, ItineraryStep to) {
        GeoUtils.TransportLeg leg = computeLeg(from, to);
        if (leg == null) return; // 좌표 없음·비현실 구간 — 기존 교통정보 유지
        to.updateTransportation(leg.mode(), leg.durationMin(), leg.distanceKm(), leg.cost());
        log.debug("Recalculated transport: {} → {} = {}km, {}원",
                from.getPlace().getName(), to.getPlace().getName(), leg.distanceKm(), leg.cost());
    }

    /**
     * 두 스텝 간 이동 leg를 좌표 기반으로 계산한다. 좌표가 없거나(0,0 포함) 비현실 구간이면 null.
     * transportPref는 저장돼 있지 않아 기본 "any"(혼합 단가)로 계산(RouteOptimizer 생성 경로와 동일 공식).
     */
    private GeoUtils.TransportLeg computeLeg(ItineraryStep from, ItineraryStep to) {
        Place fromPlace = from.getPlace();
        Place toPlace = to.getPlace();
        if (fromPlace == null || toPlace == null) return null;
        if (fromPlace.getLatitude() == null || toPlace.getLatitude() == null) return null;
        if (GeoUtils.isZeroCoord(fromPlace.getLatitude(), fromPlace.getLongitude())
                || GeoUtils.isZeroCoord(toPlace.getLatitude(), toPlace.getLongitude())) return null;

        return GeoUtils.estimateLeg(
                new double[]{fromPlace.getLatitude().doubleValue(), fromPlace.getLongitude().doubleValue()},
                new double[]{toPlace.getLatitude().doubleValue(), toPlace.getLongitude().doubleValue()},
                "any", MAX_REASONABLE_LEG_KM);
    }

    private Itinerary findAndVerifyOwner(Long itineraryId, Long userId) {
        Itinerary itinerary = itineraryRepository.findByIdWithDetails(itineraryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ITINERARY_NOT_FOUND, "일정을 찾을 수 없습니다."));

        if (!itinerary.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ITINERARY_ACCESS_DENIED, "일정에 접근할 권한이 없습니다.");
        }
        return itinerary;
    }
}
