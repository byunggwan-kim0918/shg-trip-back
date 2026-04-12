package com.shg.trip.shgtrip.domain.place.service;

import com.shg.trip.shgtrip.domain.place.dto.WishlistResponse;
import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.domain.place.entity.UserPlaceWishlist;
import com.shg.trip.shgtrip.domain.place.repository.PlaceRepository;
import com.shg.trip.shgtrip.domain.place.repository.WishlistRepository;
import com.shg.trip.shgtrip.domain.user.entity.User;
import com.shg.trip.shgtrip.domain.user.repository.UserRepository;
import com.shg.trip.shgtrip.global.exception.BusinessException;
import com.shg.trip.shgtrip.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final PlaceRepository placeRepository;
    private final UserRepository userRepository;

    /** 찜 목록 조회 (region이 있으면 필터, 없으면 전체) */
    public Page<WishlistResponse> getWishlist(Long userId, String region, Pageable pageable) {
        if (region != null && !region.isBlank()) {
            return wishlistRepository.findByUserIdAndRegion(userId, region, pageable)
                    .map(WishlistResponse::from);
        }
        return wishlistRepository.findByUserIdWithPlace(userId, pageable)
                .map(WishlistResponse::from);
    }

    /** 찜하기 */
    @Transactional
    public WishlistResponse addWishlist(Long userId, Long placeId) {
        if (wishlistRepository.existsByUserIdAndPlaceId(userId, placeId)) {
            throw new BusinessException(ErrorCode.WISHLIST_ALREADY_EXISTS);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLACE_NOT_FOUND));

        UserPlaceWishlist wishlist = UserPlaceWishlist.of(user, place);
        return WishlistResponse.from(wishlistRepository.save(wishlist));
    }

    /** 찜 취소 */
    @Transactional
    public void removeWishlist(Long userId, Long placeId) {
        UserPlaceWishlist wishlist = wishlistRepository.findByUserIdAndPlaceId(userId, placeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WISHLIST_NOT_FOUND));
        wishlistRepository.delete(wishlist);
    }

    /** 찜 여부 확인 */
    public boolean isWishlisted(Long userId, Long placeId) {
        return wishlistRepository.existsByUserIdAndPlaceId(userId, placeId);
    }
}
