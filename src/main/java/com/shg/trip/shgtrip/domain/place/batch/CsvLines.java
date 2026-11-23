package com.shg.trip.shgtrip.domain.place.batch;

import java.util.ArrayList;
import java.util.List;

/** 큰따옴표로 감싼 필드와 두 겹 큰따옴표("")를 받는 CSV 한 줄 해석. 줄바꿈이 든 필드는 받지 않는다. */
final class CsvLines {

    private CsvLines() {}

    /** @return 필드 배열, 빈 줄이면 null */
    static String[] parse(String line) {
        if (line == null || line.isBlank()) return null;

        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { current.append('"'); i++; }
                    else inQuotes = false;
                } else current.append(c);
            } else {
                if (c == '"') inQuotes = true;
                else if (c == ',') { fields.add(current.toString()); current.setLength(0); }
                else current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
