package com.shg.trip.shgtrip.domain.place.batch;

/** 테스트용: 보내는 프롬프트(규칙 system + 장소 user)를 고정본과 비교할 한 덩어리로 잇는다. */
final class EnrichPrompts {

    private EnrichPrompts() {}

    /** 장소 정보 뒤에 규칙을 이은 글. 고정본(enrich-prompt/*.txt)이 이 순서로 적혀 있다. */
    static String text(EnrichPrompt prompt) {
        return prompt.user() + "\n" + prompt.system();
    }
}
