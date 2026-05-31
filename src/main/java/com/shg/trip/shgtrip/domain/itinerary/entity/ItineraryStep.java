package com.shg.trip.shgtrip.domain.itinerary.entity;

import com.shg.trip.shgtrip.domain.place.entity.Place;
import com.shg.trip.shgtrip.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "itinerary_steps")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ItineraryStep extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "itinerary_id", nullable = false)
    @Setter
    private Itinerary itinerary;

    @Column(nullable = false)
    private Integer stepOrder;

    @Column(nullable = false)
    private Integer dayNumber;

    @Column(nullable = false, length = 5)
    private String startTime;

    @Column(nullable = false, length = 5)
    private String endTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id")
    private Place place;

    @Column(length = 20)
    private String transportationMode;

    private Integer transportationDuration;

    @Column(precision = 10, scale = 2)
    private BigDecimal transportationDistance;

    @Column(precision = 10, scale = 2)
    private BigDecimal transportationCost;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String transportationRoute;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(columnDefinition = "TEXT")
    private String userNotes;

    @Column(precision = 10, scale = 2)
    private BigDecimal estimatedCost;

    @OneToMany(mappedBy = "step", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("optionOrder ASC")
    @org.hibernate.annotations.BatchSize(size = 30)
    @Builder.Default
    private List<AlternativeOption> alternatives = new ArrayList<>();

    public void addAlternative(AlternativeOption alt) {
        alternatives.add(alt);
        alt.setStep(this);
    }

    /**
     * 대안 장소 선택 적용.
     * 선택된 대안이 메인으로 올라오고, 기존 메인 장소는 대안 목록 끝으로 내려감 (swap).
     * notes/estimatedCost도 함께 교체하여 step 정보가 선택 장소 기준으로 갱신됨.
     * remove 후 optionOrder를 재계산하여 orphanRemoval flush 순서 충돌 방지.
     */
    public void selectAlternative(AlternativeOption selected) {
        Place previousPlace = this.place;
        String previousNotes = this.notes;
        BigDecimal previousCost = this.estimatedCost;

        // 선택된 대안을 메인으로
        this.place = selected.getPlace();
        this.notes = selected.getNotes();
        this.estimatedCost = selected.getEstimatedCost();

        // remove 먼저 — Hibernate DELETE 예약
        this.alternatives.remove(selected);

        // remove 이후 남은 목록 기준으로 새 optionOrder 계산 (충돌 방지)
        int newOrder = this.alternatives.stream()
                .mapToInt(AlternativeOption::getOptionOrder)
                .max().orElse(0) + 1;

        if (previousPlace != null) {
            AlternativeOption swapped = AlternativeOption.builder()
                    .place(previousPlace)
                    .notes(previousNotes)
                    .estimatedCost(previousCost)
                    .optionOrder(newOrder)
                    .generatedAt(java.time.OffsetDateTime.now())
                    .build();
            addAlternative(swapped);  // addAlternative가 step 참조를 설정함
        }
    }

    /** 교통 거리 업데이트 (대안 선택 시 재계산용) */
    public void updateTransportationDistance(BigDecimal distance) {
        this.transportationDistance = distance;
    }
}
