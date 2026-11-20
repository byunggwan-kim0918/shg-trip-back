package com.shg.trip.shgtrip.domain.planning.service;

import java.time.LocalDate;

/**
 * 일출·일몰 시각(현지 시각, 자정부터 분). NOAA 근사식이라 국내에서 실제와 2~3분 안쪽으로 맞는다.
 * 야외 장소는 해 지기 전, 야경은 해 진 뒤라는 판단이 이 시각에 걸린다.
 */
final class SolarClock {

    private static final double SUN_ALTITUDE = 90.833;

    private SolarClock() {
    }

    /** @param country 시간대를 정할 나라 코드. 모르면 한국 시간이다 */
    static int sunset(LocalDate date, double lat, double lon, String country) {
        return (int) Math.round(solarNoon(date, lon, country) + 4 * hourAngle(date, lat));
    }

    static int sunrise(LocalDate date, double lat, double lon, String country) {
        return (int) Math.round(solarNoon(date, lon, country) - 4 * hourAngle(date, lat));
    }

    private static double solarNoon(LocalDate date, double lon, String country) {
        return 720 - 4 * lon - equationOfTime(gamma(date)) + TripCalendar.utcOffsetMinutes(country);
    }

    private static double gamma(LocalDate date) {
        return 2 * Math.PI / 365 * (date.getDayOfYear() - 1);
    }

    private static double equationOfTime(double g) {
        return 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
    }

    private static double hourAngle(LocalDate date, double lat) {
        double g = gamma(date);
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double latRad = Math.toRadians(lat);
        double cos = Math.cos(Math.toRadians(SUN_ALTITUDE)) / (Math.cos(latRad) * Math.cos(decl))
                - Math.tan(latRad) * Math.tan(decl);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }
}
