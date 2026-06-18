package com.shg.trip.shgtrip.domain.place.controller;

import com.shg.trip.shgtrip.domain.place.dto.PlaceResponse;
import com.shg.trip.shgtrip.domain.place.service.PlaceService;
import com.shg.trip.shgtrip.global.response.ApiResponse;
import com.shg.trip.shgtrip.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceService placeService;

    @GetMapping("/{id}")
    public ApiResponse<PlaceResponse> getPlace(@PathVariable Long id) {
        return ApiResponse.success(placeService.getPlace(id));
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<PlaceResponse>> searchPlaces(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "5.0") double radius,
            @PageableDefault(size = 20, sort = "rating", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        if (lat != null && lng != null) {
            return ApiResponse.success(PageResponse.from(placeService.searchNearby(lat, lng, radius, pageable)));
        }
        if (keyword != null && !keyword.isBlank()) {
            if (category != null && !category.isBlank()) {
                return ApiResponse.success(PageResponse.from(placeService.searchPlaces(keyword, category, pageable)));
            }
            return ApiResponse.success(PageResponse.from(placeService.searchPlaces(keyword, pageable)));
        }
        return ApiResponse.success(PageResponse.from(Page.empty(pageable)));
    }
}
