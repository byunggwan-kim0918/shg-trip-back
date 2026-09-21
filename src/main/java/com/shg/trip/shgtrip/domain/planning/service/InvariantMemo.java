package com.shg.trip.shgtrip.domain.planning.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 최종 불변식 패스의 패스 간 기억. 보충했다가 거리·시간 가드에 잘렸거나, 상위 불변식을 깨서
 * 취소된 (day, 장소) 조합을 기록해 같은 조합을 다시 시도하지 않게 한다 — 없으면
 * "보충 → 트림 → 보충"이 매 패스 반복되며 수렴하지 않는다.
 */
class InvariantMemo {
    private final Set<String> rejected = new HashSet<>();
    private final List<int[]> insertedThisPass = new ArrayList<>();
    /**
     * 필수 슬롯(식사·숙소)로 넣은 조합. 잘리더라도 영구 거부하지 않는다 — 거부하면 다음 패스에
     * 그 식당을 다시 못 써서 "끼니 없는 day"가 굳어진다. 잘렸다는 건 다른 제약과 충돌했다는
     * 뜻이고, 완화 단계(L2/L3)에서 다시 시도할 여지를 남겨야 한다.
     */
    private final Set<String> requiredSlot = new HashSet<>();

    boolean isRejected(int dayNumber, int index) {
        return rejected.contains(dayNumber + ":" + index);
    }

    void recordInsert(int dayNumber, int index) {
        insertedThisPass.add(new int[]{dayNumber, index});
    }

    /** 필수 슬롯 삽입 기록 — settle/rejectAll의 영구 거부 대상에서 제외된다. */
    void recordRequiredSlotInsert(int dayNumber, int index) {
        recordInsert(dayNumber, index);
        requiredSlot.add(dayNumber + ":" + index);
    }

    /** 이번 패스에 삽입한 (day, index) 목록 — 롤백 시 거부 목록으로 넘긴다. */
    List<int[]> pendingIndices() {
        return List.copyOf(insertedThisPass);
    }

    /** 재스케줄 후 호출 — 삽입분이 day에 남아있지 않으면 가드에 잘린 것이므로 거부 목록에 넣는다. */
    void settle(List<DayState> days) {
        for (int[] entry : insertedThisPass) {
            DayState day = days.stream().filter(d -> d.dayNumber == entry[0]).findFirst().orElse(null);
            boolean survived = day != null && day.placeIndices.contains(entry[1]);
            if (!survived && !requiredSlot.contains(entry[0] + ":" + entry[1])) {
                rejected.add(entry[0] + ":" + entry[1]);
            }
        }
        insertedThisPass.clear();
    }

    /** 우선순위 역전으로 취소된 수리 — 그 조합은 다시 시도하지 않는다. */
    void rejectAll(List<int[]> entries) {
        for (int[] entry : entries) rejected.add(entry[0] + ":" + entry[1]);
    }

    void clearPending() {
        insertedThisPass.clear();
    }
}
