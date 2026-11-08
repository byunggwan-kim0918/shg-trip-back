package com.shg.trip.shgtrip.domain.planning.service;

import java.util.List;

/** 하위 우선순위 수리면 이름과 삽입 인덱스를 남겨 롤백에 쓴다. */
record RepairOutcome(boolean changed, String lowestPriorityRepair, List<int[]> insertedIndices) {
    static RepairOutcome none() {
        return new RepairOutcome(false, null, List.of());
    }

    static RepairOutcome highPriority(List<int[]> inserted) {
        return new RepairOutcome(true, null, inserted);
    }

    static RepairOutcome lowPriority(String name, List<int[]> inserted) {
        return new RepairOutcome(true, name, inserted);
    }
}
