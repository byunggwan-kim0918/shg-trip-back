package com.shg.trip.shgtrip.domain.place.client;

/**
 * Place Details(New) ID 직조회가 HTTP 404를 반환한 경우 — place_id가 폐기됐음을 의미.
 * 호출부는 이 예외를 잡아 googlePlaceId를 무효화하고 Text Search로 1회 재매칭한다.
 * 타임아웃·5xx 등 일시 장애({@link com.shg.trip.shgtrip.global.exception.BusinessException})와 구분된다.
 */
public class PlaceIdNotFoundException extends RuntimeException {
    public PlaceIdNotFoundException(String placeId) {
        super("Google place_id not found (deprecated): " + placeId);
    }
}
