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
     * 선택된 대안과 메인 장소를 맞바꾸고 notes·estimatedCost도 함께 교체한다.
     * 기존 메인은 대안 목록 끝으로 내려간다.
     */
    public void selectAlternative(AlternativeOption selected) {
        Place previousPlace = this.place;
        String previousNotes = this.notes;
        BigDecimal previousCost = this.estimatedCost;

        this.place = selected.getPlace();
        this.notes = selected.getNotes();
        this.estimatedCost = selected.getEstimatedCost();

        // remove를 먼저 해 Hibernate DELETE를 예약한다.
        this.alternatives.remove(selected);

        // 삭제된 요소와 같은 optionOrder가 되면 orphanRemoval flush가 충돌하므로 원래 order보다 크게 잡는다.
        int newOrder = selected.getOptionOrder() + this.alternatives.size() + 1;

        if (previousPlace != null) {
            AlternativeOption swapped = AlternativeOption.builder()
                    .place(previousPlace)
                    .notes(previousNotes)
                    .estimatedCost(previousCost)
                    .optionOrder(newOrder)
                    .generatedAt(java.time.OffsetDateTime.now())
                    .build();
            addAlternative(swapped);
        }
    }

    /** 시간(startTime/endTime)은 건드리지 않는다. */
    public void assignOrder(int order) {
        this.stepOrder = order;
    }

    /**
     * 이동한 스텝이 그 슬롯의 stepOrder와 시간을 함께 물려받는다.
     * 시간을 스텝에 붙여 옮기면 타임라인이 비단조(15:00이 09:00 위)가 된다.
     */
    public void assignSlot(int order, String startTime, String endTime) {
        this.stepOrder = order;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public void updateTransportationDistance(BigDecimal distance) {
        this.transportationDistance = distance;
    }

    public void updateTransportation(String mode, Integer durationMin, BigDecimal distanceKm, BigDecimal cost) {
        this.transportationMode = mode;
        this.transportationDuration = durationMin;
        this.transportationDistance = distanceKm;
        this.transportationCost = cost;
    }
}
