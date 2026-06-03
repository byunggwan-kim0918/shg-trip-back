package com.shg.trip.shgtrip.domain.place.repository;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    /**
     * 장소명 + 주소로 기존 장소 조회 (AI 응답 → DB 매핑용)
     */
    Optional<Place> findByNameAndAddress(String name, String address);

    /**
     * 키워드로 장소 검색 (이름, 주소, 설명 포함)
     */
    @Query("""
            SELECT p FROM Place p
            WHERE LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(p.address) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(p.description) LIKE LOWER(CONCAT('%', :keyword, '%'))
            """)
    Page<Place> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 좌표 반경 내 장소 검색 (Haversine 근사)
     */
    @Query("""
            SELECT p FROM Place p
            WHERE (6371 * acos(
                cos(radians(:lat)) * cos(radians(p.latitude)) *
                cos(radians(p.longitude) - radians(:lng)) +
                sin(radians(:lat)) * sin(radians(p.latitude))
            )) <= :radiusKm
            """)
    Page<Place> searchByRadius(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusKm") double radiusKm,
            Pageable pageable
    );

    /**
     * 카테고리 + 키워드 복합 검색
     */
    @Query("""
            SELECT p FROM Place p
            WHERE (:category IS NULL OR p.category = :category)
              AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(p.address) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Place> searchByKeywordAndCategory(
            @Param("keyword") String keyword,
            @Param("category") String category,
            Pageable pageable
    );

    /**
     * 비동기 S3 이미지 URL 복구에 사용되는 메서드.
     * Place 엔티티의 imageUrl 필드를 직접 업데이트한다.
     */
    @Modifying
    @Query("UPDATE Place p SET p.imageUrl = :url WHERE p.id = :id")
    @Transactional
    void updateImageUrl(@Param("id") Long id, @Param("url") String url);

    /**
     * 국가, 지역, source='foursquare'로 장소 목록 조회.
     * FoursquareSeeder에서 upsert 시 기존 장소 일괄 조회에 사용된다.
     */
    @Query("SELECT p FROM Place p WHERE p.country = :country AND p.region = :region AND p.source = 'foursquare'")
    java.util.List<Place> findAllByCountryAndRegionAndSourceFoursquare(
            @Param("country") String country,
            @Param("region") String region
    );
}
