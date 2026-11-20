package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.PlaceCandidate;

import java.math.BigDecimal;
import java.util.*;

/** 후보 장소의 좌표와 중심점. */
final class CandidateGeo {

    private CandidateGeo() {
    }

    static double[] coordsOf(PlaceCandidate c) {
        if (c == null || c.latitude() == null || c.longitude() == null) return null;
        // DB에 남아 있는 좌표 (0,0) 행은 좌표 없음으로 본다. 아니면 거리 계산에 (0,0)이 낀다.
        if (c.latitude().compareTo(BigDecimal.ZERO) == 0 && c.longitude().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return new double[]{c.latitude().doubleValue(), c.longitude().doubleValue()};
    }

    /** 하루 거리 예산에 넣을 좌표. 해외 관문은 null이다({@link InvariantChecker#isOverseasHubTransfer}와 같은 기준). */
    static double[] budgetCoords(PlaceCandidate c) {
        return c != null && PlaceCategoryConstants.isOverseasHub(c.country(), c.category()) ? null : coordsOf(c);
    }
}
