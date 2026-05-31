package com.shg.trip.shgtrip.domain.itinerary.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.*;
import com.shg.trip.shgtrip.domain.itinerary.entity.AlternativeOption;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.util.GeoUtils;
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
import java.util.List;
import java.util.UUID;

/**
 * 일정 관리 서비스.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryService {

    private static final int SHARE_EXPIRE_DAYS = 7;

    private final ItineraryRepository itineraryRepository;

    @Transactional(readOnly = true)
    public ItineraryResponse getItinerary(Long itineraryId, Long userId) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        return ItineraryResponse.from(itinerary);
    }

    @Transactional(readOnly = true)
    public Page<ItinerarySummaryResponse> getMyItineraries(Long userId, Pageable pageable) {
        return itineraryRepository
                .findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable)
                .map(ItinerarySummaryResponse::from);
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

        // 교통 거리 재계산
        recalculateTransportation(itinerary.getSteps(), step);

        return ItineraryResponse.from(itinerary);
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

    private void updateDistance(ItineraryStep from, ItineraryStep to) {
        Place fromPlace = from.getPlace();
        Place toPlace = to.getPlace();
        if (fromPlace == null || toPlace == null) return;
        if (fromPlace.getLatitude() == null || toPlace.getLatitude() == null) return;
        if (GeoUtils.isZeroCoord(fromPlace.getLatitude(), fromPlace.getLongitude())
                || GeoUtils.isZeroCoord(toPlace.getLatitude(), toPlace.getLongitude())) return;

        double dist = GeoUtils.haversine(
                fromPlace.getLatitude().doubleValue(), fromPlace.getLongitude().doubleValue(),
                toPlace.getLatitude().doubleValue(), toPlace.getLongitude().doubleValue()
        );
        to.updateTransportationDistance(BigDecimal.valueOf(dist).setScale(2, RoundingMode.HALF_UP));
        log.debug("Recalculated distance: {} → {} = {}km", fromPlace.getName(), toPlace.getName(),
                String.format("%.2f", dist));
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
