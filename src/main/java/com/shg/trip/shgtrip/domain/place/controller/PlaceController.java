package com.shg.trip.shgtrip.domain.place.controller;

import com.shg.trip.shgtrip.domain.place.client.GooglePlacesClient;
import com.shg.trip.shgtrip.domain.place.dto.PlaceResponse;
import com.shg.trip.shgtrip.domain.place.service.PlaceRefreshService;
import com.shg.trip.shgtrip.domain.place.service.PlaceService;
import com.shg.trip.shgtrip.global.response.ApiResponse;
import com.shg.trip.shgtrip.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;

@Slf4j
@RestController
@RequestMapping("/api/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceService placeService;
    private final PlaceRefreshService placeRefreshService;
    private final GooglePlacesClient googlePlacesClient;

    @GetMapping("/{id}")
    public ApiResponse<PlaceResponse> getPlace(@PathVariable Long id) {
        return ApiResponse.success(placeService.getPlace(id));
    }

    /**
     * GET /api/places/{id}/photo
     * 이미지 프록시 (S3 우선, Google Places API 폴백).
     * 1. S3 imageUrl이 있으면 302 리다이렉트
     * 2. Google photoReference로 다운로드 시도
     * 3. 성공 시 이미지 스트리밍 + 비동기 S3 업로드 트리거
     * 4. 실패 시 비동기 갱신 후 404
     */
    @GetMapping("/{id}/photo")
    public void getPhoto(@PathVariable Long id, HttpServletResponse response) throws IOException {
        // 1) S3 imageUrl 확인 — 있으면 리다이렉트
        String imageUrl = placeService.getImageUrl(id);
        if (imageUrl != null) {
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader(HttpHeaders.LOCATION, imageUrl);
            return;
        }

        // 2) Google photoReference 확인
        String ref = placeService.getPhotoReference(id);
        if (ref == null || !ref.startsWith("places/")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이미지가 없습니다.");
        }

        // 3) Google Places Photo API 다운로드
        Optional<byte[]> result = googlePlacesClient.downloadPhotoBytes(ref);

        if (result.isEmpty()) {
            log.debug("Photo download failed for placeId={}, ref={}, triggering async refresh", id, ref);
            PlaceResponse place = placeService.getPlace(id);
            placeRefreshService.refreshAsync(id, place.name());
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "이미지를 가져올 수 없습니다.");
            return;
        }

        // 4) 성공: 이미지 스트리밍 + 비동기 S3 업로드
        byte[] imageBytes = result.get();
        response.setContentType(MediaType.IMAGE_JPEG_VALUE);
        response.setContentLength(imageBytes.length);
        response.getOutputStream().write(imageBytes);
        placeRefreshService.uploadPhotoIfAbsent(id, ref);
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
