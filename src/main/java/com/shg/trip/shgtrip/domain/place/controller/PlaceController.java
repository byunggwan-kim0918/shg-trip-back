package com.shg.trip.shgtrip.domain.place.controller;

import com.shg.trip.shgtrip.domain.place.client.GooglePlacesProperties;
import com.shg.trip.shgtrip.domain.place.dto.PlaceResponse;
import com.shg.trip.shgtrip.domain.place.service.PlaceService;
import com.shg.trip.shgtrip.global.response.ApiResponse;
import com.shg.trip.shgtrip.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceService placeService;
    private final GooglePlacesProperties googlePlacesProperties;
    private final RestClient restClient;

    @GetMapping("/{id}")
    public ApiResponse<PlaceResponse> getPlace(@PathVariable Long id) {
        return ApiResponse.success(placeService.getPlace(id));
    }

    /**
     * GET /api/places/{id}/photo
     * Google Places 이미지 프록시 — API 키를 서버에서만 사용, 클라이언트에 노출 없음.
     */
    @GetMapping("/{id}/photo")
    public void getPhoto(@PathVariable Long id, HttpServletResponse response) throws IOException {
        String ref = placeService.getPhotoReference(id);
        if (ref == null || !ref.startsWith("places/")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이미지가 없습니다.");
        }

        // Places API (New): photoReference = "places/{placeId}/photos/{photoId}"
        // 사진 URL: https://places.googleapis.com/v1/{photoName}/media
        String googleUrl = "https://places.googleapis.com/v1/" + ref
                + "/media?maxWidthPx=800&key=" + googlePlacesProperties.apiKey();

        byte[] imageBytes = restClient.get()
                .uri(googleUrl)
                .retrieve()
                .body(byte[].class);

        if (imageBytes == null || imageBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이미지를 가져올 수 없습니다.");
        }

        response.setContentType(MediaType.IMAGE_JPEG_VALUE);
        response.setContentLength(imageBytes.length);
        response.getOutputStream().write(imageBytes);
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
