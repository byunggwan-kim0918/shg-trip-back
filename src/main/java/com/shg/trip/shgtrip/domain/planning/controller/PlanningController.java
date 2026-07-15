package com.shg.trip.shgtrip.domain.planning.controller;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.GenerateJobResponse;
import com.shg.trip.shgtrip.domain.planning.dto.SentenceParseRequest;
import com.shg.trip.shgtrip.domain.planning.dto.SentenceParseResponse;
import com.shg.trip.shgtrip.domain.planning.service.TravelPlannerService;
import com.shg.trip.shgtrip.domain.planning.service.ai.SentenceParseService;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.response.ApiResponse;
import com.shg.trip.shgtrip.global.security.UserPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/itineraries")
@RequiredArgsConstructor
public class PlanningController {

    private final TravelPlannerService travelPlannerService;
    private final SentenceParseService sentenceParseService;

    /**
     * 자연어 문장 → 구조화 필드 파싱 (NewTripShell 실시간 패널·마법사 프리필).
     * 콘텐츠 실패는 200 + empty()로 반환(디바운스 루프 4xx 스팸 방지).
     */
    @PostMapping("/parse")
    public ApiResponse<SentenceParseResponse> parseSentence(
            @Valid @RequestBody SentenceParseRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        return ApiResponse.success(sentenceParseService.parse(request.sentence()));
    }

    @PostMapping("/generate")
    public ApiResponse<GenerateJobResponse> generateItinerary(
            @Valid @RequestBody ItineraryGenerateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        return ApiResponse.success(travelPlannerService.startGeneration(request, principal.id()));
    }

    @GetMapping(value = "/generate/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGeneration(
            @PathVariable String jobId,
            HttpServletResponse response) {

        response.setBufferSize(0);
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Transfer-Encoding", "chunked");
        response.setContentType("text/event-stream;charset=UTF-8");

        return travelPlannerService.getEmitter(jobId);
    }

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
