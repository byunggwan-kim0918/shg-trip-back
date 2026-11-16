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

    /** 이 대안을 고르면 스텝 이야기가 되는 글. 교체로 빠진 장소의 이야기나 레거시 경로에서 모델이 쓴 팁이다. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** 예상 비용(원) */
    @Column(precision = 10, scale = 2)
    private BigDecimal estimatedCost;

    @Column(nullable = false)
    @Builder.Default
    private OffsetDateTime generatedAt = OffsetDateTime.now();
}
