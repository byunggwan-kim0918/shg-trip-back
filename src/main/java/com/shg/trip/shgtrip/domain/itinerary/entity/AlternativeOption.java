package com.shg.trip.shgtrip.domain.itinerary.entity;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "alternative_options")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class AlternativeOption extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_id", nullable = false)
    @Setter
    private ItineraryStep step;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "place_id")
    private Place place;

    @Column(nullable = false)
    private Integer optionOrder;

    /** 이 대안 장소에서의 추천 활동/팁 */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** 이 대안 장소의 예상 비용(원) */
    @Column(precision = 10, scale = 2)
    private BigDecimal estimatedCost;

    @Column(nullable = false)
    @Builder.Default
    private OffsetDateTime generatedAt = OffsetDateTime.now();
}
