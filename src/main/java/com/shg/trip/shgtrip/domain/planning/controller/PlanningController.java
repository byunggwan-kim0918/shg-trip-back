package com.shg.trip.shgtrip.domain.planning.controller;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryGenerateRequest;
import com.shg.trip.shgtrip.domain.planning.dto.GenerateJobResponse;
import com.shg.trip.shgtrip.domain.planning.dto.GenerationQuotaResponse;
import com.shg.trip.shgtrip.domain.planning.dto.SentenceParseRequest;
import com.shg.trip.shgtrip.domain.planning.dto.SentenceParseResponse;
import com.shg.trip.shgtrip.domain.planning.service.GenerationPolicyService;
import com.shg.trip.shgtrip.domain.planning.service.GenerationStream;
import com.shg.trip.shgtrip.domain.planning.service.TravelPlannerService;
import com.shg.trip.shgtrip.domain.planning.service.ai.SentenceParseService;
import com.shg.trip.shgtrip.domain.user.service.ActiveUserGuard;
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
    private final GenerationPolicyService generationPolicyService;
    private final ActiveUserGuard activeUserGuard;

    /**
     * 콘텐츠 실패는 디바운스 루프의 4xx 스팸을 막으려고 200 + empty()로 반환한다.
     */
    @PostMapping("/parse")
    public ApiResponse<SentenceParseResponse> parseSentence(
            @Valid @RequestBody SentenceParseRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        generationPolicyService.checkParseAllowed(principal.id());
        return ApiResponse.success(sentenceParseService.parse(request.sentence()));
    }

    @PostMapping("/generate")
    public ApiResponse<GenerateJobResponse> generateItinerary(
            @Valid @RequestBody ItineraryGenerateRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        activeUserGuard.requireActive(principal.id());
        return ApiResponse.success(travelPlannerService.startGeneration(request, principal.id()));
    }

    @GetMapping(value = "/generate/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamGeneration(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletResponse response) {

        response.setBufferSize(0);
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Transfer-Encoding", "chunked");
        response.setContentType("text/event-stream;charset=UTF-8");

        if (principal == null) {
            ErrorCode code = ErrorCode.INVALID_TOKEN;
            return GenerationStream.rejected(code.getCode(), code.getMessage());
        }
        return travelPlannerService.openStream(jobId, principal.id());
    }

    @GetMapping("/generation-quota")
    public ApiResponse<GenerationQuotaResponse> getGenerationQuota(
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        return ApiResponse.success(generationPolicyService.getStatus(principal.id()));
    }

    @GetMapping("/generate/{jobId}/result")
    public ApiResponse<Long> getGenerationResult(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        return ApiResponse.success(travelPlannerService.getResult(jobId, principal.id()));
    }
}
