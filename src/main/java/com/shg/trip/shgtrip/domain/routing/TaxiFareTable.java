package com.shg.trip.shgtrip.domain.routing;

import com.shg.trip.shgtrip.global.util.GeoUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 지역별 택시 거리 요금표로 낸 요금 추정. 출발지에서 가장 가까운 기준점의 지역 요금 중 출발일에 시행 중인 것을 쓴다.
 * 시간 요금(막힘·대기)과 할증은 넣지 않아 실제보다 적게 나온다. 기준점이 멀면 요금을 모르는 것으로 둔다.
 */
public class TaxiFareTable {

    /**
     * 요금표 한 행. 기본 거리까지 기본요금, 넘으면 stepMeters마다 stepAmount를 더한다.
     *
     * @param validFrom 시행일. null이면 시행일을 모르고 이미 시행 중으로 본다
     */
    public record TaxiFare(String country, String area, GeoPoint anchor, String currency, long baseAmount,
                           int baseMeters, int stepMeters, long stepAmount, LocalDate validFrom) {
        public TaxiFare {
            if (stepMeters <= 0) throw new IllegalArgumentException(area + ": step_meters는 0보다 커야 한다");
        }
    }

    private final List<TaxiFare> fares;
    private final double maxAnchorKm;

    public TaxiFareTable(List<TaxiFare> fares, double maxAnchorKm) {
        this.fares = List.copyOf(fares);
        this.maxAnchorKm = maxAnchorKm;
    }

    public static TaxiFareTable empty() {
        return new TaxiFareTable(List.of(), 0);
    }

    public Optional<Fare> estimate(GeoPoint origin, int meters, LocalDate date) {
        Optional<TaxiFare> nearest = fares.stream()
                .filter(f -> km(origin, f.anchor()) <= maxAnchorKm)
                .min(Comparator.comparingDouble(f -> km(origin, f.anchor())));
        if (nearest.isEmpty()) return Optional.empty();
        String country = nearest.get().country();
        String area = nearest.get().area();
        return fares.stream()
                .filter(f -> f.country().equals(country) && f.area().equals(area))
                .filter(f -> f.validFrom() == null || !f.validFrom().isAfter(date))
                .max(Comparator.comparing(f -> f.validFrom() == null ? LocalDate.MIN : f.validFrom()))
                .map(f -> new Fare(BigDecimal.valueOf(amount(f, meters)), f.currency(), Fare.Kind.TAXI_TABLE));
    }

    static long amount(TaxiFare f, int meters) {
        int over = Math.max(0, meters - f.baseMeters());
        long steps = (over + f.stepMeters() - 1L) / f.stepMeters();
        return f.baseAmount() + steps * f.stepAmount();
    }

    private static double km(GeoPoint a, GeoPoint b) {
        return GeoUtils.haversine(a.lat(), a.lng(), b.lat(), b.lng());
    }

    /**
     * 열: country,area,lat,lng,currency,base_amount,base_meters,step_meters,step_amount,valid_from,source.
     * 지역 이름에 쉼표가 들어가므로 따옴표로 감싼 칸을 읽는다.
     */
    public static List<TaxiFare> readCsv(InputStream in) {
        List<TaxiFare> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            r.readLine();
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank()) continue;
                List<String> c = split(line);
                out.add(new TaxiFare(c.get(0), c.get(1), new GeoPoint(Double.parseDouble(c.get(2)),
                        Double.parseDouble(c.get(3))), c.get(4), Long.parseLong(c.get(5)), Integer.parseInt(c.get(6)),
                        Integer.parseInt(c.get(7)), Long.parseLong(c.get(8)),
                        c.get(9).isBlank() ? null : LocalDate.parse(c.get(9))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static List<String> split(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells;
    }
}
