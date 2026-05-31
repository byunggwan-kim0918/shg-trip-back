package com.shg.trip.shgtrip.domain.itinerary.controller;

import com.shg.trip.shgtrip.domain.itinerary.dto.ItineraryResponse;
import com.shg.trip.shgtrip.domain.itinerary.service.ItineraryService;
import com.shg.trip.shgtrip.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공유 일정 조회 (비인증).
 */
@RestController
@RequestMapping("/api/shared")
@RequiredArgsConstructor
public class SharedItineraryController {

    private final ItineraryService itineraryService;

    @GetMapping("/{token}")
    public ApiResponse<ItineraryResponse> getSharedItinerary(@PathVariable String token) {
        return ApiResponse.success(itineraryService.getSharedItinerary(token));
    }
}
