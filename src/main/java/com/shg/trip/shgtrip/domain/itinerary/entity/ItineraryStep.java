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

        // remove 이후 새 optionOrder 계산: selected의 원래 order + 남은 리스트 크기 + 1
        // 이렇게 하면 빈 리스트에서도 selected.getOptionOrder() + 1 이상이 되어
        // removed element와 동일한 optionOrder를 피할 수 있음 (Hibernate orphanRemoval flush 충돌 방지)
        int newOrder = selected.getOptionOrder() + this.alternatives.size() + 1;

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

    /** 스텝 순서 재배정 (삭제 후 남은 스텝 재시퀀싱용). 시간(startTime/endTime)은 건드리지 않는다. */
    public void assignOrder(int order) {
        this.stepOrder = order;
    }

    /**
     * 드래그 재정렬 시 시간 슬롯 고정 — 이동한 스텝이 그 위치(슬롯)의 stepOrder와 시간을 함께 물려받는다.
     * 시간을 스텝에 붙여 옮기면 타임라인이 비단조(예: 15:00이 09:00 위)로 보이므로, 슬롯의 시간을 재배정한다.
     */
    public void assignSlot(int order, String startTime, String endTime) {
        this.stepOrder = order;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /** 교통 거리 업데이트 (대안 선택 시 재계산용) */
    public void updateTransportationDistance(BigDecimal distance) {
        this.transportationDistance = distance;
    }

    /** 교통 정보 일괄 업데이트 (대안 선택 시 거리·시간·비용·모드를 함께 재계산) */
    public void updateTransportation(String mode, Integer durationMin, BigDecimal distanceKm, BigDecimal cost) {
        this.transportationMode = mode;
        this.transportationDuration = durationMin;
        this.transportationDistance = distanceKm;
        this.transportationCost = cost;
    }
}
