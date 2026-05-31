package com.shg.trip.shgtrip.domain.planning.controller;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.GenerateJobResponse;
import com.shg.trip.shgtrip.domain.planning.service.TravelPlannerService;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.response.ApiResponse;
import com.shg.trip.shgtrip.global.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 일정 생성 API 엔드포인트.
 */
@RestController
@RequestMapping("/api/itineraries")
@RequiredArgsConstructor
public class PlanningController {

    private final TravelPlannerService travelPlannerService;

    /**
     * POST /api/itineraries/generate → jobId 반환.
     */
    @PostMapping("/generate")
    public ApiResponse<GenerateJobResponse> generateItinerary(
            @Valid @RequestBody ItineraryGenerateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        GenerateJobResponse response = travelPlannerService.startGeneration(request, principal.id());
        return ApiResponse.success(response);
    }

    /**
     * GET /api/itineraries/generate/{jobId}/stream → SSE 스트림.
     * 이벤트: progress, complete, error
     */
    @GetMapping(value = "/generate/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGeneration(@PathVariable String jobId) {
        return travelPlannerService.getEmitter(jobId);
    }

    /**
     * GET /api/itineraries/generate/{jobId}/result → 완료된 itineraryId 반환 (인증 필수, 1회성).
     * SSE complete 이벤트에 itineraryId를 노출하지 않고 인증된 API로만 제공.
     */
    @GetMapping("/generate/{jobId}/result")
    public ApiResponse<Long> getGenerationResult(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        return ApiResponse.success(travelPlannerService.getResult(jobId));
    }
}
