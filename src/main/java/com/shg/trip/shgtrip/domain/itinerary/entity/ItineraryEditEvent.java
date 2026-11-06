package com.shg.trip.shgtrip.domain.itinerary.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/** 생성된 일정에 사용자가 가한 변경 한 건. payload에는 id·순서만 담는다(자유 텍스트 금지). */
@Entity
@Table(name = "itinerary_edit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItineraryEditEvent {

    public enum Type { DELETE_ITINERARY, FINALIZE, SELECT_ALTERNATIVE, REORDER, DELETE_STEP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long itineraryId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Type type;

    private Integer dayNumber;

    private Long stepId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static ItineraryEditEvent of(Long itineraryId, Long userId, Type type, Integer dayNumber, Long stepId,
                                        Map<String, Object> payload) {
        ItineraryEditEvent e = new ItineraryEditEvent();
        e.itineraryId = itineraryId;
        e.userId = userId;
        e.type = type;
        e.dayNumber = dayNumber;
        e.stepId = stepId;
        e.payload = payload;
        return e;
    }
}
