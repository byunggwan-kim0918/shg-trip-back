package com.shg.trip.shgtrip.domain.routing;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 일정 하나를 만드는 동안 쓸 수 있는 외부 API 호출 수와 조회 기한. 자체 서버(OTP·Valhalla)와 캐시 적중은 호출 수에
 * 세지 않는다. 다 쓰거나 기한이 지나면 공급자를 건너뛰고 조회 불가로 남긴다. 추정으로 바꾸는 것은 호출부의 몫이다.
 */
public final class CallAllowance {

    private final AtomicInteger remaining;
    private final Instant deadline;

    private CallAllowance(AtomicInteger remaining, Instant deadline) {
        this.remaining = remaining;
        this.deadline = deadline;
    }

    public static CallAllowance of(int calls) {
        return new CallAllowance(new AtomicInteger(calls), Instant.MAX);
    }

    public static CallAllowance unlimited() {
        return of(Integer.MAX_VALUE);
    }

    boolean take() {
        return remaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    public int remaining() {
        return remaining.get();
    }

    /**
     * 같은 호출 수를 나눠 쓰고 이 시각 뒤에는 공급자를 부르지 않는 묶음. 대체 공급자·끝점 옮기기·접근 구간 조회도 같은 기한을
     * 쓴다. 묶음마다 따로 만들어야 앞 묶음에서 버린 조회가 뒤 묶음 기한으로 돌지 않는다.
     */
    public CallAllowance until(Instant at) {
        return new CallAllowance(remaining, at == null ? Instant.MAX : at);
    }

    public Instant deadline() {
        return deadline;
    }
}
