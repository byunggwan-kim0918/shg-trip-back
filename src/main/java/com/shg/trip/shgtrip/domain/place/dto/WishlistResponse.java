package com.shg.trip.shgtrip.domain.place.dto;

import com.shg.trip.shgtrip.domain.place.entity.UserPlaceWishlist;

import java.time.OffsetDateTime;

public record WishlistResponse(
        Long wishlistId,
        PlaceResponse place,
        OffsetDateTime createdAt
) {
    public static WishlistResponse from(UserPlaceWishlist wishlist) {
        return new WishlistResponse(
                wishlist.getId(),
                PlaceResponse.from(wishlist.getPlace()),
                wishlist.getCreatedAt()
        );
    }
}
