package com.shg.trip.shgtrip.domain.planning.service;

import com.shg.trip.shgtrip.domain.planning.dto.SelectionOutput;
import java.util.ArrayList;
import java.util.List;

class DayState {
    final int dayNumber;
    Integer arrivalHubIndex;
    final List<Integer> placeIndices;
    Integer accommodationIndex;
    Integer departureHubIndex;
    /**
     * 그날의 요일. 영업시간 판정(정기휴무 + 여는/닫는 시각)에 쓴다.
     * 여행 시작일을 모르면 null이고, 그때는 영업시간 판정을 건너뛴다(열림 가정).
     */
    java.time.DayOfWeek dayOfWeek;
    /**
     * 사용자가 고른 카테고리를 <b>일정 전체에서 혼자 대표하는</b> 장소 인덱스.
     *
     * <p>거리 트림의 보호 대상이다. {@code ensureCategoryCoverage}가 스케줄 전에 카테고리를
     * 채워 넣어도, 그 뒤 거리 예산 트림이 다시 빼버려 "최종 커버리지 미충족(예산·자리 제약으로
     * 삽입·교체 불가): cafe, viewpoint" 로그로 끝났다. 사용자가 고른 것을 거리 때문에 0개로
     * 만들 수는 없다.
     *
     * <p>itinerary 전체를 봐야 정할 수 있는 값이라 day마다 같은 집합을 들고 있다 —
     * {@link #dayOfWeek}와 같은 패턴이다(시그니처로 끌고 내려가는 대신 day가 알게 한다).
     * 대표가 2곳 이상인 카테고리는 넣지 않는다 — 하나는 빼도 커버리지가 유지된다.
     */
    java.util.Set<Integer> coverageCriticalIndices = java.util.Set.of();
    /**
     * 그날 점심과 저녁 사이에 들르는 카페·디저트({@code ScheduleConfig.cafeStop}일 때만). 오후 버킷에
     * 고정되고 거리 트림에서 보호된다 — 짧게 들르는 곳이라 우회 기여만 보면 가장 먼저 잘린다
     * (실측 115 day4: 수애기베이커리·미쁜제과가 거리 트림으로 빠졌다).
     */
    Integer cafeStopIndex;

    DayState(SelectionOutput.DayPlan plan) {
        this.dayNumber = plan.dayNumber();
        this.arrivalHubIndex = plan.arrivalHubIndex();
        this.placeIndices = new ArrayList<>(plan.placeIndices() != null ? plan.placeIndices() : List.of());
        this.accommodationIndex = plan.accommodationIndex();
        this.departureHubIndex = plan.departureHubIndex();
    }
}
