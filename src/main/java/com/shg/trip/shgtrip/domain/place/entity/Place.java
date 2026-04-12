package com.shg.trip.shgtrip.domain.place.entity;

import com.shg.trip.shgtrip.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "places")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Place extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String address;

    @Column(nullable = false, precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(nullable = false, length = 100)
    private String category;

    @Column(length = 100)
    private String region;

    @Column(length = 100)
    private String country;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(precision = 2, scale = 1)
    private BigDecimal rating;

    private Integer priceLevel;

    private String openingHours;

    private String imageUrl;

    // Google Places photo_reference (API 키 없이 저장, 응답 시 키 조합)
    private String photoReference;

    private String sourceUrl;

    @Column(nullable = false)
    private OffsetDateTime savedAt;

    @PrePersist
    protected void onSave() {
        if (savedAt == null) savedAt = OffsetDateTime.now();
    }

    public boolean isStale() {
        return savedAt.isBefore(OffsetDateTime.now().minusDays(7));
    }

    public void update(String address, double lat, double lng, Double rating,
                       Integer priceLevel, String openingHours, String photoReference, String sourceUrl) {
        this.address = address;
        this.latitude = BigDecimal.valueOf(lat);
        this.longitude = BigDecimal.valueOf(lng);
        if (rating != null) this.rating = BigDecimal.valueOf(rating);
        if (priceLevel != null) this.priceLevel = priceLevel;
        if (openingHours != null) this.openingHours = openingHours;
        if (photoReference != null) this.photoReference = photoReference;
        if (sourceUrl != null) this.sourceUrl = sourceUrl;
        this.savedAt = OffsetDateTime.now();
    }
}
