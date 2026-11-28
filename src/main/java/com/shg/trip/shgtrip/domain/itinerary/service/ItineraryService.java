package com.shg.trip.shgtrip.domain.itinerary.service;

import com.shg.trip.shgtrip.domain.itinerary.dto.*;
import com.shg.trip.shgtrip.domain.itinerary.entity.AlternativeOption;
import com.shg.trip.shgtrip.domain.itinerary.entity.Itinerary;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryEditEvent;
import com.shg.trip.shgtrip.domain.itinerary.entity.ItineraryStep;
import com.shg.trip.shgtrip.domain.itinerary.repository.GenerationTraceRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryEditEventRepository;
import com.shg.trip.shgtrip.domain.itinerary.repository.ItineraryRepository;
import com.shg.trip.shgtrip.domain.place.enrich.PromotedPlaceEnricher;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.planning.service.OpeningCheck;
import com.shg.trip.shgtrip.domain.planning.service.PlaceCategoryConstants;
import com.shg.trip.shgtrip.domain.planning.service.PartyCosts;
import com.shg.trip.shgtrip.domain.planning.service.QualityGate;
import com.shg.trip.shgtrip.domain.planning.service.SavedItineraryFacts;
import com.shg.trip.shgtrip.domain.planning.service.ScheduleTuning;
import com.shg.trip.shgtrip.domain.planning.service.TransportLeg;
import com.shg.trip.shgtrip.domain.planning.service.TripRoutes;
import com.shg.trip.shgtrip.domain.planning.dto.TransportRoute;
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
import org.springframework.transaction.support.TransactionTemplate;
import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
    private final PromotedPlaceEnricher placeEnricher;
    private final GenerationTraceRepository generationTraceRepository;
    private final ItineraryEditEventRepository editEventRepository;
    private final QualityGate qualityGate;
    private final TripRoutes tripRoutes;
    private final TransactionTemplate transactionTemplate;

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

    private ItineraryResponse toResponse(Itinerary itinerary) {
        List<Long> placeIds = itinerary.getSteps().stream().map(ItineraryStep::getPlace).filter(Objects::nonNull)
                .map(Place::getId).distinct().toList();
        return toResponse(itinerary, placeEnricher.pendingAmong(placeIds));
    }

    /** 표지 장소가 스텝 밖(여행지 대표 사진)이면 따로 읽는다. */
    private ItineraryResponse toResponse(Itinerary itinerary, List<Long> enrichingPlaceIds) {
        Long coverId = itinerary.getCoverPlaceId();
        Place cover = coverId == null ? null : itinerary.getSteps().stream()
                .map(ItineraryStep::getPlace)
                .filter(p -> p != null && coverId.equals(p.getId()))
                .findFirst()
                .orElseGet(() -> placeRepository.findById(coverId).orElse(null));
        return ItineraryResponse.from(itinerary, cover, enrichingPlaceIds);
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
        return toResponse(itinerary, List.of()).forSharing();
    }

    /**
     * 선택 후 해당 step과 다음 step의 교통 거리를 좌표 기반으로 재계산한다.
     */
    public ItineraryResponse selectAlternative(Long itineraryId, Long stepId, Long alternativeId, Long userId) {
        return edit(itineraryId, userId, routes -> {
            Itinerary itinerary = routes.lock();
            return selectAlternative(itinerary, stepId, alternativeId, userId, routes);
        });
    }

    private ItineraryResponse selectAlternative(Itinerary itinerary, Long stepId, Long alternativeId, Long userId,
                                                EditRoutes routes) {

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

        recalculateTransportation(routes, step);
        itinerary.recalculateEstimatedCost();
        qualityGate.reflectEdit(itinerary);

        return toResponse(itinerary);
    }

    /**
     * 대안으로 바꿨을 때의 변화. 일정은 바꾸지 않는다. 이동은 {@link #recalculateTransportation}처럼 같은 날 앞뒤 구간만
     * 다시 재고, 잴 수 없으면 저장된 값을 둔다. 안내는 편집 재판정과 같은 판정으로 만든다.
     */
    public AlternativePreviewResponse previewAlternative(Long itineraryId, Long stepId, Long alternativeId,
                                                         Long userId) {
        EditRoutes routes = new EditRoutes(null);
        AlternativePreviewResponse estimated = transactionTemplate.execute(s ->
                previewAlternative(itineraryId, stepId, alternativeId, userId, routes, Map.of()));
        if (routes.pending.isEmpty() || !tripRoutes.editRoutingAllowed(userId)) return estimated;
        Map<Long, TransportLeg> routed = routes.routeOutsideLock();
        return transactionTemplate.execute(s ->
                previewAlternative(itineraryId, stepId, alternativeId, userId, new EditRoutes(null), routed));
    }

    /**
     * @param routed 잠금 밖에서 물어 본 구간(도착 스텝 id별). 비어 있으면 거리 추정이고, 이웃이 바뀐 구간도 추정으로 둔다
     */
    private AlternativePreviewResponse previewAlternative(Long itineraryId, Long stepId, Long alternativeId,
                                                          Long userId, EditRoutes routes,
                                                          Map<Long, TransportLeg> routed) {
        Itinerary itinerary = findAndVerifyOwner(itineraryId, userId);
        routes.itinerary = itinerary;
        verifyEditable(itinerary);
        ItineraryStep step = itinerary.getSteps().stream()
                .filter(s -> s.getId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "일정 단계를 찾을 수 없습니다."));
        AlternativeOption selected = step.getAlternatives().stream()
                .filter(a -> a.getId().equals(alternativeId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "대안 장소를 찾을 수 없습니다."));
        Place to = selected.getPlace();

        List<ItineraryStep> sorted = byStepOrder(itinerary.getSteps());
        ItineraryStep prev = sameDayNeighbor(sorted, step, -1);
        ItineraryStep next = sameDayNeighbor(sorted, step, 1);
        GeoUtils.TransportLeg incoming = prev == null ? null
                : routes.preview(prev, prev.getPlace(), to, step, routed.get(step.getId()));
        GeoUtils.TransportLeg outgoing = next == null ? null
                : routes.preview(step, to, next.getPlace(), next, routed.get(next.getId()));

        QualityGate.SwapPreview quality = qualityGate.previewSwap(itinerary, step, to, incoming, next, outgoing);
        List<String> now = itinerary.getQualityNotices() == null ? List.of() : itinerary.getQualityNotices();
        List<String> after = quality == null ? now : quality.notices();
        LocalDate date = itinerary.getStartDate() == null ? null
                : itinerary.getStartDate().plusDays(step.getDayNumber() - 1L);
        Integer alreadyOnDay = sorted.stream()
                .filter(s -> s != step && s.getPlace() != null && s.getPlace().getId().equals(to.getId()))
                .map(ItineraryStep::getDayNumber)
                .findFirst().orElse(null);

        return new AlternativePreviewResponse(
                prev == null ? null : legChange(step, incoming),
                next == null ? null : legChange(next, outgoing),
                step.getEstimatedCost(), selected.getEstimatedCost(),
                OpeningCheck.openDuring(to, date, step.getStartTime(), step.getEndTime()),
                OpeningCheck.closedOn(to, date),
                alreadyOnDay,
                now.stream().filter(n -> !after.contains(n)).toList(),
                after.stream().filter(n -> !now.contains(n)).toList());
    }

    private static AlternativePreviewResponse.LegChange legChange(ItineraryStep arriving,
                                                                  GeoUtils.TransportLeg recalculated) {
        AlternativePreviewResponse.Leg before = AlternativePreviewResponse.Leg.of(arriving);
        return new AlternativePreviewResponse.LegChange(before,
                recalculated == null ? before : AlternativePreviewResponse.Leg.of(recalculated));
    }

    /**
     * 시간(startTime/endTime)은 슬롯에 고정되고 장소만 재배치되며, 교통 정보는 새 순서로 재계산한다.
     * orderedStepIds가 해당 day의 전체 스텝 집합과 정확히 일치하지 않으면 거부한다.
     */
    public ItineraryResponse reorderSteps(Long itineraryId, Long userId, Integer dayNumber, List<Long> orderedStepIds) {
        return edit(itineraryId, userId, routes -> reorderSteps(routes.lock(), userId, dayNumber, orderedStepIds, routes));
    }

    private ItineraryResponse reorderSteps(Itinerary itinerary, Long userId, Integer dayNumber,
                                           List<Long> orderedStepIds, EditRoutes routes) {

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

        recalculateDayTransportation(routes, dayNumber, transfer);
        itinerary.recalculateEstimatedCost();
        qualityGate.reflectEdit(itinerary);
        return toResponse(itinerary);
    }

    /**
     * day의 마지막 1스텝은 삭제할 수 없다. 남은 스텝 순서와 해당 day의 이동 정보는 재계산한다.
     */
    public ItineraryResponse deleteStep(Long itineraryId, Long userId, Long stepId) {
        return edit(itineraryId, userId, routes -> deleteStep(routes.lock(), userId, stepId, routes));
    }

    private ItineraryResponse deleteStep(Itinerary itinerary, Long userId, Long stepId, EditRoutes routes) {

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

        recalculateDayTransportation(routes, dayNumber, transfer);
        itinerary.recalculateEstimatedCost();
        qualityGate.reflectEdit(itinerary);
        return toResponse(itinerary);
    }

    /**
     * 편집 한 번. 잠금을 잡은 트랜잭션에서 편집하고 바뀐 구간을 거리 추정으로 적은 뒤 커밋하고, 경로 엔진이 켜져 있으면
     * 잠금 밖에서 그 구간만 묻는다. 다시 잠가 그 사이 바뀌지 않은 구간에만 결과를 적어, 늦게 온 답이 다른 편집을 덮지 않는다.
     */
    private ItineraryResponse edit(Long itineraryId, Long userId,
                                   java.util.function.Function<EditRoutes, ItineraryResponse> change) {
        EditRoutes routes = new EditRoutes(null);
        routes.itineraryId = itineraryId;
        routes.userId = userId;
        ItineraryResponse edited = transactionTemplate.execute(s -> change.apply(routes));
        if (routes.pending.isEmpty() || !tripRoutes.editRoutingAllowed(userId)) return edited;
        Map<Long, TransportLeg> routed = routes.routeOutsideLock();
        if (routed.isEmpty()) return edited;
        try {
            return transactionTemplate.execute(s -> {
                Itinerary itinerary = findEditableForUpdate(itineraryId, userId);
                int applied = routes.applyStillValid(itinerary, routed);
                if (applied == 0) return toResponse(itinerary);
                itinerary.recalculateEstimatedCost();
                qualityGate.reflectEdit(itinerary);
                return toResponse(itinerary);
            });
        } catch (RuntimeException e) {
            // 편집은 이미 커밋됐다. 그 사이 확정·삭제됐으면 경로만 못 적은 것이라 편집 결과를 돌려준다.
            log.warn("edit route apply skipped itineraryId={} cause={}", itineraryId, e.getClass().getSimpleName());
            return edited;
        }
    }

    /** 다른 도시에서 넘어온 날 첫 스텝의 도시 간 이동 구간. 그날 순서를 바꿔도 이동 자체는 그대로다. */
    private record TransferLeg(String mode, Integer duration, BigDecimal distance, BigDecimal cost, String route) {}

    /** 그날 첫 스텝이 도시 간 이동 구간이면 그 값, 아니면 null. */
    private static TransferLeg transferLegOf(List<ItineraryStep> daySteps) {
        return daySteps.stream()
                .min(Comparator.comparingInt(ItineraryStep::getStepOrder))
                .filter(s -> s.getTransportationMode() != null
                        && ScheduleTuning.INTERCITY_LEG_MODES.contains(s.getTransportationMode()))
                .map(s -> new TransferLeg(s.getTransportationMode(), s.getTransportationDuration(),
                        s.getTransportationDistance(), s.getTransportationCost(), s.getTransportationRoute()))
                .orElse(null);
    }

    /**
     * day 첫 스텝은 인바운드 leg가 없어 교통정보를 비운다. 생성 파이프라인과 같은 시맨틱이다.
     * 좌표가 없어 계산할 수 없는 구간도 낡은 leg가 남지 않게 비운다.
     *
     * @param transfer 편집 전 첫 스텝의 도시 간 이동 구간. 있으면 새 첫 스텝에 둔다
     */
    private void recalculateDayTransportation(EditRoutes routes, Integer dayNumber, TransferLeg transfer) {
        List<ItineraryStep> daySteps = routes.itinerary.getSteps().stream()
                .filter(s -> dayNumber.equals(s.getDayNumber()))
                .sorted(Comparator.comparingInt(ItineraryStep::getStepOrder))
                .toList();
        for (int i = 0; i < daySteps.size(); i++) {
            ItineraryStep step = daySteps.get(i);
            if (i == 0) {
                if (transfer != null) {
                    step.updateTransportation(transfer.mode(), transfer.duration(), transfer.distance(), transfer.cost(),
                            EditRoutes.arrivesAt(transfer.route(), step.getPlace()) ? transfer.route() : null);
                } else {
                    step.updateTransportation(null, null, null, null);
                }
                continue;
            }
            ItineraryStep prev = daySteps.get(i - 1);
            if (routes.unchanged(prev, step)) continue;
            if (!routes.update(prev, step)) step.updateTransportation(null, null, null, null);
        }
    }

    /**
     * 일차 경계를 넘는 구간은 재계산하지 않는다.
     */
    private void recalculateTransportation(EditRoutes routes, ItineraryStep changedStep) {
        List<ItineraryStep> sorted = byStepOrder(routes.itinerary.getSteps());
        ItineraryStep prev = sameDayNeighbor(sorted, changedStep, -1);
        ItineraryStep next = sameDayNeighbor(sorted, changedStep, 1);
        if (prev != null && !routes.update(prev, changedStep)) EditRoutes.dropRoute(changedStep);
        if (next != null && !routes.update(changedStep, next)) EditRoutes.dropRoute(next);
    }

    /**
     * 편집 한 번에서 바뀐 구간. 잠금 안에서는 거리 추정을 적고 그 구간을 기억한다. 잠금 밖에서 경로를 물은 뒤 앞 장소·도착
     * 장소·떠나는 시각·적어 둔 추정이 그대로인 구간에만 결과를 적는다. 교통 데이터가 바뀌어도 고치지 않은 구간은 건드리지 않는다.
     */
    private final class EditRoutes {
        private Itinerary itinerary;
        private Long itineraryId;
        private Long userId;
        private final List<Pending> pending = new ArrayList<>();

        EditRoutes(Itinerary itinerary) {
            this.itinerary = itinerary;
        }

        /**
         * 잠금 밖에서 물을 구간. 장소는 잠금 안에서 후보로 옮겨 두어 트랜잭션이 끝난 뒤에도 쓴다.
         *
         * @param stepId 이 구간으로 도착하는 스텝. 결과는 이 스텝에 적는다
         */
        private record Pending(Long stepId, Long prevStepId, Long fromPlaceId, Long toPlaceId, String departTime,
                               LocalDate date, String country, PlaceCandidate from, PlaceCandidate to,
                               GeoUtils.TransportLeg estimate, String modeHint) {}

        Itinerary lock() {
            itinerary = findEditableForUpdate(itineraryId, userId);
            return itinerary;
        }

        /** 앞 장소·도착 장소·떠나는 시각이 저장된 경로 근거와 같은 구간. 다시 묻지 않는다. */
        boolean unchanged(ItineraryStep from, ItineraryStep to) {
            TransportRoute r = TransportRoute.fromJson(to.getTransportationRoute());
            return sameEnds(r, from.getPlace(), to.getPlace())
                    && r.departAt() != null && r.departAt().length() >= 16
                    && r.departAt().substring(11, 16).equals(from.getEndTime());
        }

        private static boolean sameEnds(TransportRoute r, Place from, Place to) {
            return r != null && from != null && to != null && Objects.equals(r.fromPlaceId(), from.getId())
                    && Objects.equals(r.toPlaceId(), to.getId());
        }

        /** 도시 간 구간 근거가 이 장소에 닿는 경로인지. 첫 스텝이 바뀌면 근거는 다른 장소를 가리킨다. */
        static boolean arrivesAt(String routeJson, Place place) {
            TransportRoute r = TransportRoute.fromJson(routeJson);
            return r != null && place != null && Objects.equals(r.toPlaceId(), place.getId());
        }

        /** 잴 수 없는 구간은 예전 값을 두되, 다른 장소를 가리키는 근거는 지운다. */
        static void dropRoute(ItineraryStep s) {
            s.updateTransportation(s.getTransportationMode(), s.getTransportationDuration(),
                    s.getTransportationDistance(), s.getTransportationCost(), null);
        }

        /**
         * 이 구간을 물을 수단. 앞 장소·도착 장소가 그대로일 때만 저장된 수단을 쓴다. 바뀐 구간에 이웃 구간 수단(짧은 도보 등)을
         * 쓰면 먼 구간을 걸어서 묻는다. 그 밖에는 추정 수단이다.
         */
        private static String modeHint(ItineraryStep arriving, Place from, Place to) {
            TransportRoute r = TransportRoute.fromJson(arriving.getTransportationRoute());
            return sameEnds(r, from, to) ? arriving.getTransportationMode() : null;
        }

        /**
         * 시간·비용·수단을 함께 바꿔야 화면 예산 합산(장소비+이동비)이 맞는다. 저장된 여행 조건이 없어 추정은 혼합 단가다.
         *
         * @return 좌표가 없거나 하루 안에 오갈 거리가 아니어서 잴 수 없으면 false. 저장된 값은 그대로다
         */
        boolean update(ItineraryStep from, ItineraryStep to) {
            GeoUtils.TransportLeg estimate = remember(from, from.getPlace(), to.getPlace(), to);
            if (estimate == null) return false;
            to.updateTransportation(estimate.mode(), estimate.durationMin(), estimate.distanceKm(), estimate.cost());
            return true;
        }

        /** 거리 추정을 내고 잠금 밖에서 물을 구간으로 기억한다. 잴 수 없으면 null이다. */
        private GeoUtils.TransportLeg remember(ItineraryStep departing, Place from, Place to, ItineraryStep arriving) {
            GeoUtils.TransportLeg estimate = computeLeg(from, to);
            if (estimate == null) return null;
            LocalDate date = itinerary.getStartDate() == null ? null
                    : itinerary.getStartDate().plusDays(arriving.getDayNumber() - 1L);
            pending.add(new Pending(arriving.getId(), departing.getId(), from.getId(), to.getId(),
                    departing.getEndTime(), date, to.getCountry(), SavedItineraryFacts.candidate(from),
                    SavedItineraryFacts.candidate(to), estimate, modeHint(arriving, from, to)));
            return estimate;
        }

        /**
         * @param routed 트랜잭션 밖에서 물어 본 이 구간. 없거나 그 사이 이웃이 바뀌었으면 거리 추정이다
         */
        GeoUtils.TransportLeg preview(ItineraryStep departing, Place from, Place to, ItineraryStep arriving,
                                      TransportLeg routed) {
            GeoUtils.TransportLeg estimate = remember(departing, from, to, arriving);
            if (routed == null || estimate == null || !sameEnds(routed.route(), from, to)
                    || routed.unreachable()) return estimate;
            TransportLeg priced = priced(routed);
            return new GeoUtils.TransportLeg(priced.mode(), priced.durationMin(), priced.distanceKm(), priced.cost());
        }

        /** 요금을 생성 때와 같은 인원으로 곱한다. 요금을 모르면 이동 비용을 비운다. */
        private TransportLeg priced(TransportLeg leg) {
            TransportRoute r = leg.route();
            if (r == null || r.fare() == null) return leg;
            TransportRoute.Fare fare = r.fare().forParty(qualityGate.partySize(itinerary),
                    PartyCosts.seatsPerVehicle(leg.mode()));
            return new TransportLeg(leg.mode(), leg.durationMin(), leg.distanceKm(), fare.totalWon(), r.withFare(fare));
        }

        /** 기억한 구간을 잠금 밖에서 묻는다. 경로를 받지 못한 구간은 빠진다(이미 거리 추정이 적혀 있다). */
        Map<Long, TransportLeg> routeOutsideLock() {
            Map<Long, TransportLeg> out = new LinkedHashMap<>();
            TripRoutes.Session session = null;
            for (Pending p : pending) {
                if (session == null) session = tripRoutes.editSession(p.country());
                TransportLeg leg = session.between(p.date(), p.departTime(), p.from(), p.to(), p.estimate(),
                        p.modeHint());
                if (leg.route() != null) out.put(p.stepId(), leg);
            }
            return out;
        }

        /** 다시 잠근 일정에서 그 사이 바뀌지 않은 구간에만 경로를 적는다. @return 적은 구간 수 */
        int applyStillValid(Itinerary current, Map<Long, TransportLeg> routed) {
            itinerary = current;
            List<ItineraryStep> sorted = byStepOrder(current.getSteps());
            int applied = 0;
            for (Pending p : pending) {
                TransportLeg leg = routed.get(p.stepId());
                ItineraryStep step = sorted.stream().filter(x -> x.getId().equals(p.stepId())).findFirst().orElse(null);
                if (leg == null || step == null) continue;
                ItineraryStep prev = sameDayNeighbor(sorted, step, -1);
                boolean same = prev != null && prev.getId().equals(p.prevStepId())
                        && Objects.equals(placeIdOf(prev), p.fromPlaceId()) && Objects.equals(placeIdOf(step), p.toPlaceId())
                        && Objects.equals(prev.getEndTime(), p.departTime())
                        && Objects.equals(step.getTransportationMode(), p.estimate().mode())
                        && Objects.equals(step.getTransportationDuration(), p.estimate().durationMin())
                        && step.getTransportationRoute() == null;
                if (!same) continue;
                TransportLeg priced = priced(leg);
                step.updateTransportation(priced.mode(), priced.durationMin(), priced.distanceKm(), priced.cost(),
                        priced.route().toJson());
                applied++;
            }
            return applied;
        }
    }

    private static List<ItineraryStep> byStepOrder(List<ItineraryStep> steps) {
        return steps.stream().sorted(Comparator.comparingInt(ItineraryStep::getStepOrder)).toList();
    }

    /** 같은 날 바로 앞({@code offset} -1)·뒤(+1) 스텝. 날이 바뀌거나 없으면 null. */
    private static ItineraryStep sameDayNeighbor(List<ItineraryStep> sorted, ItineraryStep step, int offset) {
        int idx = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getId().equals(step.getId())) {
                idx = i;
                break;
            }
        }
        int at = idx + offset;
        if (idx < 0 || at < 0 || at >= sorted.size()) return null;
        ItineraryStep neighbor = sorted.get(at);
        return neighbor.getDayNumber().equals(step.getDayNumber()) ? neighbor : null;
    }

    /** 좌표가 없거나(0,0 포함) 비현실 구간이면 null. */
    private static GeoUtils.TransportLeg computeLeg(Place fromPlace, Place toPlace) {
        return SavedItineraryFacts.legBetween(fromPlace, toPlace);
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
