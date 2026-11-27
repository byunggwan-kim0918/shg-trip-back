package com.shg.trip.shgtrip.domain.routing;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 일정 하나를 만드는 동안 쓸 수 있는 외부 API 호출 수. 자체 서버(OTP·Valhalla)와 캐시 적중은 세지 않는다.
 * 다 쓰면 외부 공급자를 건너뛰고 조회 불가로 남긴다. 추정으로 바꾸는 것은 호출부의 몫이다.
 */
public final class CallAllowance {

    private final AtomicInteger remaining;

    private CallAllowance(int calls) {
        this.remaining = new AtomicInteger(calls);
    }

    public static CallAllowance of(int calls) {
        return new CallAllowance(calls);
    }

    public static CallAllowance unlimited() {
        return new CallAllowance(Integer.MAX_VALUE);
    }

    boolean take() {
        return remaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    public int remaining() {
        return remaining.get();
    }
}
