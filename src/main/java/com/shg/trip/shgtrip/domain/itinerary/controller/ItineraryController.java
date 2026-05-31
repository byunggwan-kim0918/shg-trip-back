package com.shg.trip.shgtrip.domain.itinerary.controller;

import com.shg.trip.shgtrip.domain.itinerary.dto.*;
import com.shg.trip.shgtrip.domain.itinerary.service.ItineraryService;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.response.ApiResponse;
import com.shg.trip.shgtrip.global.response.PageResponse;
import com.shg.trip.shgtrip.global.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 일정 관리 API 엔드포인트.
 */
@RestController
@RequestMapping("/api/itineraries")
@RequiredArgsConstructor
public class ItineraryController {

    private final ItineraryService itineraryService;

    @GetMapping("/{id}")
    public ApiResponse<ItineraryResponse> getItinerary(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        verifyPrincipal(principal);
        return ApiResponse.success(itineraryService.getItinerary(id, principal.id()));
    }

    @GetMapping
    public ApiResponse<PageResponse<ItinerarySummaryResponse>> getMyItineraries(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        verifyPrincipal(principal);
        return ApiResponse.success(PageResponse.from(itineraryService.getMyItineraries(principal.id(), pageable)));
    }

    @PutMapping("/{id}")
    public ApiResponse<ItineraryResponse> updateItinerary(
            @PathVariable Long id,
            @Valid @RequestBody ItineraryUpdateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        verifyPrincipal(principal);
        return ApiResponse.success(itineraryService.updateItinerary(id, principal.id(), request));
    }

    @PostMapping("/{id}/finalize")
    public ApiResponse<ItineraryResponse> finalizeItinerary(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        verifyPrincipal(principal);
        return ApiResponse.success(itineraryService.finalizeItinerary(id, principal.id()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItinerary(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        verifyPrincipal(principal);
        itineraryService.deleteItinerary(id, principal.id());
    }

    @PatchMapping("/{id}/steps/{stepId}/select-alternative")
    public ApiResponse<ItineraryResponse> selectAlternative(
            @PathVariable Long id,
            @PathVariable Long stepId,
            @RequestBody AlternativeSelectionRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        verifyPrincipal(principal);
        return ApiResponse.success(itineraryService.selectAlternative(id, stepId, request.alternativeId(), principal.id()));
    }

    @PostMapping("/{id}/share")
    public ApiResponse<ShareLinkResponse> generateShareLink(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        verifyPrincipal(principal);
        return ApiResponse.success(itineraryService.generateShareLink(id, principal.id()));
    }

    private void verifyPrincipal(UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
    }
}
