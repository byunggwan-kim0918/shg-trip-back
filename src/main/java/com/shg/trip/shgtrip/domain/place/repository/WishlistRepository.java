package com.shg.trip.shgtrip.domain.place.repository;

import com.shg.trip.shgtrip.domain.place.entity.UserPlaceWishlist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WishlistRepository extends JpaRepository<UserPlaceWishlist, Long> {

    boolean existsByUserIdAndPlaceId(Long userId, Long placeId);

    Optional<UserPlaceWishlist> findByUserIdAndPlaceId(Long userId, Long placeId);

    @Query(value = "SELECT w FROM UserPlaceWishlist w JOIN FETCH w.place WHERE w.user.id = :userId",
           countQuery = "SELECT count(w) FROM UserPlaceWishlist w WHERE w.user.id = :userId")
    Page<UserPlaceWishlist> findByUserIdWithPlace(@Param("userId") Long userId, Pageable pageable);

    @Query(value = "SELECT w FROM UserPlaceWishlist w JOIN FETCH w.place p " +
           "WHERE w.user.id = :userId AND p.region = :region",
           countQuery = "SELECT count(w) FROM UserPlaceWishlist w JOIN w.place p " +
           "WHERE w.user.id = :userId AND p.region = :region")
    Page<UserPlaceWishlist> findByUserIdAndRegion(@Param("userId") Long userId,
                                                  @Param("region") String region,
                                                  Pageable pageable);
}
