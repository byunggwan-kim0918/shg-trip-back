package com.shg.trip.shgtrip.domain.routing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class TaxiFareTableTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 26);

    private static TaxiFareTable real() throws IOException {
        try (InputStream in = Objects.requireNonNull(
                TaxiFareTableTest.class.getResourceAsStream("/routing/taxi-fares.csv"))) {
            return new TaxiFareTable(TaxiFareTable.readCsv(in), 40);
        }
    }

    @Test
    void 기본_거리_안은_기본요금이고_넘으면_단위마다_더한다() {
        var seoul = new TaxiFareTable.TaxiFare("KR", "서울", new GeoPoint(37.566, 126.978), "KRW", 4800, 1600, 131, 100,
                null);

        assertThat(TaxiFareTable.amount(seoul, 1600)).isEqualTo(4800);
        assertThat(TaxiFareTable.amount(seoul, 1601)).isEqualTo(4900);
        assertThat(TaxiFareTable.amount(seoul, 1600 + 131 * 10)).isEqualTo(5800);
    }

    @Test
    void 가장_가까운_기준점의_지역_요금을_쓴다() throws IOException {
        TaxiFareTable table = real();

        assertThat(table.estimate(new GeoPoint(35.6896, 139.7006), 1000, DAY))
                .contains(new Fare(BigDecimal.valueOf(500), "JPY", Fare.Kind.TAXI_TABLE));
        assertThat(table.estimate(new GeoPoint(34.9858, 135.7588), 900, DAY))
                .contains(new Fare(BigDecimal.valueOf(500), "JPY", Fare.Kind.TAXI_TABLE));
        assertThat(table.estimate(new GeoPoint(37.5547, 126.9707), 1600, DAY))
                .contains(new Fare(BigDecimal.valueOf(4800), "KRW", Fare.Kind.TAXI_TABLE));
    }

    @Test
    void 출발일에_시행_중인_요금을_쓴다() throws IOException {
        TaxiFareTable table = real();
        GeoPoint cheongju = new GeoPoint(36.642, 127.489);

        assertThat(table.estimate(cheongju, 1800, LocalDate.of(2026, 12, 18)).orElseThrow().amount())
                .isEqualByComparingTo("4000");
        assertThat(table.estimate(cheongju, 1800, LocalDate.of(2026, 12, 19)).orElseThrow().amount())
                .isEqualByComparingTo("4700");
    }

    @Test
    void 기준점이_멀면_요금을_모른다() throws IOException {
        assertThat(real().estimate(new GeoPoint(48.8566, 2.3522), 5000, DAY)).isEmpty();
    }

    @Test
    void 따옴표로_감싼_쉼표를_한_칸으로_읽는다() {
        String csv = "country,area,lat,lng,currency,base_amount,base_meters,step_meters,step_amount,valid_from,source\n"
                + "JP,\"京葉, 東葛\",35.6,140.1,JPY,500,1000,232,100,,x\n";

        List<TaxiFareTable.TaxiFare> rows = TaxiFareTable.readCsv(new java.io.ByteArrayInputStream(
                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(rows).singleElement().satisfies(f -> {
            assertThat(f.area()).isEqualTo("京葉, 東葛");
            assertThat(f.validFrom()).isNull();
        });
    }
}
