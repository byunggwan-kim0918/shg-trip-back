package com.shg.trip.shgtrip.domain.planning.service;

import java.util.List;
import java.util.Set;

/**
 * 날짜별 확정에서 그날 먼저 쓸 장소를 고른다. 고른 장소는 {@link DayPicker}가 같은 거리 고리 안에서 먼저 쓰고,
 * 거리·시간·영업·반복 조건은 그대로 지킨다. 아무것도 고르지 않으면 그날은 {@link DayPicker}가 혼자 고른다.
 */
@FunctionalInterface
public interface DaySelector {

    /** 끼니·관광 후보를 몇 곳까지 보여 줄지. 날 선택기의 순위(요청 의도, 유명도와 거리)로 자른다. */
    int MEAL_OPTIONS = 8;
    int SIGHT_OPTIONS = 15;

    DaySelector NONE = options -> Set.of();

    /** @return 그날 먼저 쓸 장소 번호. 실패하면 빈 집합이다 */
    Set<Integer> choose(DayOptions options);

    /**
     * @param kind        식당의 음식 종류 키. 식당이 아니면 null
     * @param km          기준 장소에서의 직선 거리. 좌표가 없으면 null
     * @param timing      시간대 성향(DAYTIME·EVENING·MORNING_ONLY·FLEXIBLE)
     * @param recommended 선택 단계가 고른 장소인지
     * @param requested   사용자가 고르거나 요청 의도로 꼭 넣을 장소인지
     */
    record Option(int index, String name, String category, String kind, Double km, int minutes, String timing,
                  Integer sitelinks, boolean recommended, boolean requested) {}

    /**
     * @param base   그날 기준 장소
     * @param fixed  고르기 전에 그날에 이미 놓인 장소(기준 장소·사용자가 고른 장소)
     * @param lunch  점심 후보. 점심이 이미 있거나 그날 점심 시간이 없으면 비어 있다
     * @param dinner 저녁 후보. 저녁을 고르지 않는 날이면 비어 있다
     * @param earlier 앞날마다 놓인 장소 이름. 같은 종류 식당·비슷한 볼거리를 다시 고르지 않는 데 쓴다
     */
    record DayOptions(int day, int dayCount, boolean lastDay, Option base, List<Option> fixed, List<Option> lunch,
                      List<Option> dinner, List<Option> sights, int minSights, int maxSights, List<String> earlier) {

        boolean allows(int index) {
            return lunch.stream().anyMatch(o -> o.index() == index) || dinner.stream().anyMatch(o -> o.index() == index)
                    || sights.stream().anyMatch(o -> o.index() == index);
        }
    }
}
