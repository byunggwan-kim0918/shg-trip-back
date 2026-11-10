package com.shg.trip.shgtrip.domain.tripchat.controller;

import com.shg.trip.shgtrip.domain.tripchat.dto.CreateTripChatRequest;
import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatAnswerRequest;
import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatEditRequest;
import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatResponse;
import com.shg.trip.shgtrip.domain.tripchat.dto.TripChatVersionRequest;
import com.shg.trip.shgtrip.domain.tripchat.service.TripChatService;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.response.ApiResponse;
import com.shg.trip.shgtrip.global.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/trip-chats")
@RequiredArgsConstructor
public class TripChatController {

    private final TripChatService tripChatService;

    @GetMapping("/current")
    public ApiResponse<TripChatResponse> current(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(tripChatService.current(userId(principal)));
    }

    @PostMapping
    public ApiResponse<TripChatResponse> create(@Valid @RequestBody CreateTripChatRequest request,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(tripChatService.create(userId(principal), request.clientRequestId()));
    }

    @DeleteMapping("/current")
    public ApiResponse<Void> archive(@AuthenticationPrincipal UserPrincipal principal) {
        tripChatService.archiveCurrent(userId(principal));
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> archive(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        tripChatService.archive(userId(principal), id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/answers")
    public ApiResponse<TripChatResponse> answer(@PathVariable UUID id,
                                                @Valid @RequestBody TripChatAnswerRequest request,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(tripChatService.answer(userId(principal), id, request));
    }

    @PostMapping("/{id}/edits")
    public ApiResponse<TripChatResponse> edit(@PathVariable UUID id,
                                              @Valid @RequestBody TripChatEditRequest request,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(tripChatService.edit(userId(principal), id, request.expectedVersion(),
                request.slot()));
    }

    @PostMapping("/{id}/edits/cancel")
    public ApiResponse<TripChatResponse> cancelEdit(@PathVariable UUID id,
                                                    @Valid @RequestBody TripChatVersionRequest request,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(tripChatService.cancelEdit(userId(principal), id, request.expectedVersion()));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<TripChatResponse> confirm(@PathVariable UUID id,
                                                 @Valid @RequestBody TripChatVersionRequest request,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(tripChatService.confirm(userId(principal), id, request.expectedVersion()));
    }

    private static Long userId(UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        return principal.id();
    }
}
