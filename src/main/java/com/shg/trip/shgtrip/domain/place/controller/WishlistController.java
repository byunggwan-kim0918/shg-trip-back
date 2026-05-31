package com.shg.trip.shgtrip.domain.place.controller;

import com.shg.trip.shgtrip.domain.place.dto.WishlistResponse;
import com.shg.trip.shgtrip.domain.place.service.WishlistService;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import com.shg.trip.shgtrip.global.response.ApiResponse;
import com.shg.trip.shgtrip.global.response.PageResponse;
import com.shg.trip.shgtrip.global.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistService wishlistService;

    /** GET /api/wishlist?region= */
    @GetMapping
    public ApiResponse<PageResponse<WishlistResponse>> getWishlist(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String region,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        return ApiResponse.success(PageResponse.from(wishlistService.getWishlist(principal.id(), region, pageable)));
    }

    /** POST /api/wishlist/{placeId} */
    @PostMapping("/{placeId}")
    public ResponseEntity<ApiResponse<WishlistResponse>> addWishlist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long placeId
    ) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(wishlistService.addWishlist(principal.id(), placeId)));
    }

    /** DELETE /api/wishlist/{placeId} */
    @DeleteMapping("/{placeId}")
    public ResponseEntity<Void> removeWishlist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long placeId
    ) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "인증 정보가 없습니다.");
        }
        wishlistService.removeWishlist(principal.id(), placeId);
        return ResponseEntity.noContent().build();
    }
}
