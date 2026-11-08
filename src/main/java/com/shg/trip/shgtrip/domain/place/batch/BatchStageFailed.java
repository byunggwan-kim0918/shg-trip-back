package com.shg.trip.shgtrip.domain.place.batch;

/** 배치 단계가 끝까지 돌았지만 일부를 처리하지 못했을 때. 러너가 모아 비정상 종료 코드로 끝낸다. */
public class BatchStageFailed extends RuntimeException {

    public BatchStageFailed(String message) {
        super(message);
    }
}
