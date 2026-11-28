package com.shg.trip.shgtrip.domain.planning.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 이동 한 구간의 근거. 일정에 저장하고 화면에 보낸다. 거리로 추정한 구간도 추정이라고 남긴다.
 *
 * @param confidence  RouteConfidence 이름. ESTIMATED·UNAVAILABLE이면 그 시각 그 편을 확정하지 않은 구간이다
 * @param basis       RouteBasis 이름. 경로를 받지 못했으면 null
 * @param status      경로 조회 상태(RouteStatus 이름)
 * @param provider    답한 공급자. 추정이면 null
 * @param dataVersion 답한 그래프의 판. 모르면 null
 * @param departAt    앞 장소를 떠나는 시각(ISO-8601, 시간대 포함)
 * @param arriveAt    도착 시각. 경로를 받지 못했으면 null
 * @param waitMinutes 첫 탑승과 환승을 기다리는 시간 합. 대중교통이 아니면 null
 * @param fare        구간 요금. 경로 근거와 따로 판정한다. 시간표를 확인한 구간도 운임은 모를 수 있다. 근거가 없는 옛 행은 null
 * @param checkedAt   경로를 확인한 시각
 * @param validUntil  이 시각 뒤에는 다시 확인해야 한다. 모르면 null
 * @param note        다른 수단으로 바꾼 이유나 조회하지 못한 이유. 여행자에게 그대로 보이지 않는다
 * @param attribution 경로를 낸 공급자의 데이터 출처 표기. 화면이 저작권·출처를 함께 보인다
 * @param unreachable 여행자가 고른 수단으로는 그 시각에 이어지는 편이 없다. 시간은 거리 추정이고 일정이 이 구간과 충돌한다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransportRoute(
        String confidence,
        String basis,
        String status,
        String provider,
        String dataVersion,
        Long fromPlaceId,
        Long toPlaceId,
        String departAt,
        String arriveAt,
        Integer waitMinutes,
        Integer transfers,
        List<Leg> legs,
        Fare fare,
        String checkedAt,
        String validUntil,
        String note,
        String attribution,
        boolean unreachable
) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public TransportRoute {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }

    public TransportRoute(String confidence, String basis, String status, String provider, String dataVersion,
                          Long fromPlaceId, Long toPlaceId, String departAt, String arriveAt, Integer waitMinutes,
                          Integer transfers, List<Leg> legs, Fare fare, String checkedAt, String validUntil,
                          String note) {
        this(confidence, basis, status, provider, dataVersion, fromPlaceId, toPlaceId, departAt, arriveAt, waitMinutes,
                transfers, legs, fare, checkedAt, validUntil, note, null, false);
    }

    public TransportRoute withNote(String newNote) {
        return new TransportRoute(confidence, basis, status, provider, dataVersion, fromPlaceId, toPlaceId, departAt,
                arriveAt, waitMinutes, transfers, legs, fare, checkedAt, validUntil, newNote, attribution,
                unreachable);
    }

    /** 확인한 시각과 일정의 출발 시각이 다른 근거. 그 시각 그 편을 확정하지 않고, 갈 수 없다는 판정도 옮기지 않는다. */
    public TransportRoute downgraded(String newNote) {
        return new TransportRoute("ESTIMATED", basis, status, provider, dataVersion, fromPlaceId, toPlaceId, departAt,
                arriveAt, waitMinutes, transfers, legs, fare, checkedAt, validUntil, newNote, attribution, false);
    }

    /** 화면에 보낼 근거. 조회 실패 사유 같은 내부 메모는 뺀다. */
    public TransportRoute forTraveler() {
        return withNote(null);
    }

    /** 고른 수단으로는 그 시각에 갈 수 없다고 표시한다. */
    public TransportRoute markedUnreachable() {
        return new TransportRoute(confidence, basis, status, provider, dataVersion, fromPlaceId, toPlaceId, departAt,
                arriveAt, waitMinutes, transfers, legs, fare, checkedAt, validUntil, note, attribution, true);
    }

    public TransportRoute movedTo(String newDepartAt, String newArriveAt, List<Leg> newLegs) {
        return new TransportRoute(confidence, basis, status, provider, dataVersion, fromPlaceId, toPlaceId, newDepartAt,
                newArriveAt, waitMinutes, transfers, newLegs, fare, checkedAt, validUntil, note, attribution,
                unreachable);
    }

    public TransportRoute withFare(Fare newFare) {
        return new TransportRoute(confidence, basis, status, provider, dataVersion, fromPlaceId, toPlaceId, departAt,
                arriveAt, waitMinutes, transfers, legs, newFare, checkedAt, validUntil, note, attribution, unreachable);
    }

    /**
     * 구간 요금 한 건. 금액은 현지 통화로 두고 원화는 따로 적어 통화가 다른 금액을 섞어 더하지 않는다.
     *
     * @param amount     한 단위(사람 한 명·차 한 대) 요금. 모르면 null이고 0은 무료다
     * @param currency   ISO 4217 통화. 금액을 모르면 null
     * @param per        PERSON(사람마다)·VEHICLE(차 한 대마다)
     * @param units      요금을 곱한 사람 수·차 수. 인원을 반영하기 전이면 1
     * @param travellers 여행 인원. 모르면 null이고 units는 1이다
     * @param total      amount × units(현지 통화). 금액을 모르면 null
     * @param totalWon   total의 원화. 금액을 모르거나 환율이 없는 통화면 null
     * @param wonPerUnit 환산에 쓴 통화 1단위의 원화
     * @param ratesAsOf  환율표 기준일(ISO-8601 날짜)
     * @param basis      FREE(도보처럼 요금이 없음)·CONFIRMED(공급자가 확정한 운임)·OFFICIAL_TABLE(공식 요금표로 계산)·
     *                   PUBLIC_DATA(공개 요금 데이터로 계산)·ESTIMATED(거리·시간 추정)·UNKNOWN(모름)
     * @param source     요금을 낸 공급자 id나 요금표
     * @param excludes   금액에 넣지 못한 항목(TOLL·PARKING·NIGHT_SURCHARGE·TIME_CHARGE·RENTAL_FEE). 하나라도 있으면 금액은
     *                   그 구간 전체 비용이 아니다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fare(BigDecimal amount, String currency, String per, int units, Integer travellers,
                       BigDecimal total, BigDecimal totalWon, BigDecimal wonPerUnit, String ratesAsOf, String basis,
                       String source, String checkedAt, String validUntil, List<String> excludes) {

        public static final String PERSON = "PERSON";
        public static final String VEHICLE = "VEHICLE";
        public static final String UNKNOWN = "UNKNOWN";

        public Fare {
            excludes = excludes == null ? List.of() : List.copyOf(excludes);
        }

        /** 한 단위 요금. 원화는 지금 환율표로 바꾼다. */
        public static Fare of(BigDecimal amount, String currency, String per, String basis, String source,
                              String checkedAt, String validUntil, List<String> excludes) {
            return new Fare(amount, amount == null ? null : currency, per, 1, null, null, null, null, null,
                    amount == null ? UNKNOWN : basis, source, checkedAt, validUntil, excludes).priced();
        }

        public static Fare unknown(String per, String source, String checkedAt) {
            return of(null, null, per, UNKNOWN, source, checkedAt, null, List.of());
        }

        @JsonIgnore
        public boolean known() {
            return amount != null;
        }

        /**
         * 여행 인원을 반영한다. 사람마다 내는 요금은 인원수, 차 요금은 탈 차 수만큼 곱한다.
         *
         * @param partySize        모르면 null이고 한 단위만 센다
         * @param seatsPerVehicle  차 한 대에 타는 여행자 수
         */
        public Fare forParty(Integer partySize, int seatsPerVehicle) {
            int n = partySize == null || partySize < 1 ? 1 : partySize;
            int u = VEHICLE.equals(per) ? (n + seatsPerVehicle - 1) / seatsPerVehicle : n;
            return new Fare(amount, currency, per, u, partySize, null, null, null, null, basis, source, checkedAt,
                    validUntil, excludes).priced();
        }

        private Fare priced() {
            BigDecimal t = amount == null ? null : amount.multiply(BigDecimal.valueOf(units));
            BigDecimal rate = currency == null ? null
                    : com.shg.trip.shgtrip.domain.place.destination.KrwRates.wonPerUnit(currency).orElse(null);
            BigDecimal won = t == null ? null : t.signum() == 0 ? BigDecimal.ZERO
                    : rate == null ? null : t.multiply(rate).setScale(0, RoundingMode.HALF_UP);
            return new Fare(amount, currency, per, units, travellers, t, won, rate,
                    rate == null ? null : com.shg.trip.shgtrip.domain.place.destination.KrwRates.RATES_AS_OF.toString(),
                    basis, source, checkedAt, validUntil, excludes);
        }
    }

    /** 경로 한 토막. 시각은 ISO-8601이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Leg(String mode, String line, String operator, String from, String to, String departAt,
                      String arriveAt, int minutes) {}

    /** 스텝 transportation_route 열에 넣는 JSON. */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("route json", e);
        }
    }

    /** 저장된 열을 읽는다. 비었거나 형식이 다르면 null이다(근거 없이 추정으로 본다). */
    public static TransportRoute fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, TransportRoute.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 시간표·경로망으로 확인하지 못해 화면에 추정으로 보여야 하는 구간. */
    @JsonIgnore
    public boolean estimated() {
        return confidence == null || "ESTIMATED".equals(confidence) || "UNAVAILABLE".equals(confidence);
    }
}
